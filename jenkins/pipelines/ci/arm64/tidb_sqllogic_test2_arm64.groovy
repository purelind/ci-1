properties([
        parameters([
                string(
                        defaultValue: '591ebdd9263694d88d6efc365dba14db9e8c7439',
                        name: 'TIDB_COMMIT',
                        trim: true
                ),
                string(
                        defaultValue: 'master',
                        name: 'RELEASE_BRANCH',
                        trim: true
                ),
        ])
])

echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    RELEASE_BRANCH = params.getOrDefault("release_test__release_branch", "")
    TIDB_COMMIT = params.getOrDefault("release_test__tidb_commit", "")
}

TIDB_TEST_BRANCH = RELEASE_BRANCH
TIDB_BRANCH = RELEASE_BRANCH

K8S_NAMESPACE = "jenkins-tidb"
BRANCH_NEED_GO1160 = ["master", "release-5.1"]

ARCH = "x86" // [ x86 | arm64 ]
OS = "linux" // [ centos7 | kylin_v10 | darwin ]


def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

is_need_go1160 = isBranchMatched(BRANCH_NEED_GO1160, RELEASE_BRANCH)

def run_with_pod(arch, os, Closure body) {
    def label = ""
    def cloud = "kubernetes"
    def pod_go_docker_image = ""
    def jnlp_docker_image = ""
    if (is_need_go1160) {
        if (arch == "x86") {
            label = "tidb-sqllogic_test2"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-sqllogic_test2-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            cloud = "kubernetes-arm64"
        }
    } else {
        if (arch == "x86") {
            label = "tidb-sqllogic_test2"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-sqllogic_test2-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13-arm64:latest"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            cloud = "kubernetes-arm64"
        }
    }
    podTemplate(label: label,
            cloud: cloud,
            namespace: 'jenkins-tidb',
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: true,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            resourceLimitCpu: '30000m', resourceLimitMemory: "20Gi",
                            command: '/bin/sh -c', args: 'cat',
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],

    ) {
        node(label) {
            println "debug command:\nkubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

def run_build(arch, os) {
    run_with_pod(arch, os) {
        def ws = pwd()

        dir("go/src/github.com/pingcap/tidb") {
            container("golang") {
                def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
                def tidb_done_url =  "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${TIDB_COMMIT}/centos7/done"
                if (arch == "arm64") {
                    tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/tidb-linux-arm64.tar.gz"
                }
                timeout(10) {
                    sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        curl ${tidb_url} | tar xz
                        # use tidb-server with ADMIN_CHECK as default
                        mkdir -p ${ws}/go/src/github.com/pingcap/tidb-test/sqllogic_test/
                        mv bin/tidb-server-check ${ws}/go/src/github.com/pingcap/tidb-test/sqllogic_test/tidb-server
                        """
                }
            }
        }

        dir("go/src/github.com/pingcap/tidb-test") {
            container("golang") {
                timeout(5) {
                    def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                    def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                    def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
                    if (arch == "arm64") {
                        tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test-linux-arm64.tar.gz"
                    }
                    sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                        curl ${tidb_test_url} | tar xz
                        cd sqllogic_test && ./build.sh
                        """
                }
            }
        }

        stash includes: "go/src/github.com/pingcap/tidb-test/sqllogic_test/**", name: "tidb-test-${os}-${arch}"
    }
}

def run_test(arch, os) {
    def run = { sqllogictest, parallelism, enable_cache ->
        run_with_pod(arch, os) {
            deleteDir()
            unstash "tidb-test-${os}-${arch}"

            dir("go/src/github.com/pingcap/tidb-test/sqllogic_test") {
                container("golang") {
                    timeout(10) {
                        try {
                            sh """
                                    set +e
                                    killall -9 -r tidb-server
                                    killall -9 -r tikv-server
                                    killall -9 -r pd-server
                                    rm -rf /tmp/tidb
                                    set -ex
                                    sleep 30
        
                                    SQLLOGIC_TEST_PATH=${sqllogictest} \
                                    TIDB_PARALLELISM=${parallelism} \
                                    TIDB_SERVER_PATH=`pwd`/tidb-server \
                                    CACHE_ENABLED=${enable_cache} \
                                    ./test.sh
                                    """
                        } catch (err) {
                            throw err
                        }finally{
                            sh """
                                    set +e
                                    killall -9 -r tidb-server
                                    killall -9 -r tikv-server
                                    killall -9 -r pd-server
                                    rm -rf /tmp/tidb
                                    """
                        }
                    }
                }
            }
        }
    }

    def run_two = { sqllogictest_1, parallelism_1, sqllogictest_2, parallelism_2, enable_cache ->
        run_with_pod(arch, os) {
            deleteDir()
            unstash "tidb-test-${os}-${arch}"

            dir("go/src/github.com/pingcap/tidb-test/sqllogic_test") {
                container("golang") {
                    timeout(10) {
                        try{
                            sh """
                                    set +e
                                    killall -9 -r tidb-server
                                    killall -9 -r tikv-server
                                    killall -9 -r pd-server
                                    rm -rf /tmp/tidb
                                    set -ex
                                    
                                    sleep 30
        
                                    SQLLOGIC_TEST_PATH=${sqllogictest_1} \
                                    TIDB_PARALLELISM=${parallelism_1} \
                                    TIDB_SERVER_PATH=`pwd`/tidb-server \
                                    CACHE_ENABLED=${enable_cache} \
                                    ./test.sh
                                    
                                    set +e
                                    killall -9 -r tidb-server
                                    killall -9 -r tikv-server
                                    killall -9 -r pd-server
                                    rm -rf /tmp/tidb
                                    set -ex
                                    
                                    sleep 30
        
                                    SQLLOGIC_TEST_PATH=${sqllogictest_2} \
                                    TIDB_PARALLELISM=${parallelism_2} \
                                    TIDB_SERVER_PATH=`pwd`/tidb-server \
                                    CACHE_ENABLED=${enable_cache} \
                                    ./test.sh
                                    """
                        }catch(err){
                            throw err
                        }finally{
                            sh """
                                    set +e
                                    killall -9 -r tidb-server
                                    killall -9 -r tikv-server
                                    killall -9 -r pd-server
                                    rm -rf /tmp/tidb
                                    """
                        }
                    }
                }
            }
        }
    }

    def tests = [:]

    tests["SQLLogic Random Aggregates_n1 Test"] = {
        run('/git/sqllogictest/test/random/aggregates_n1', 8, 0)
    }

    tests["SQLLogic Random Aggregates_n2 Test"] = {
        run('/git/sqllogictest/test/random/aggregates_n2', 8, 0)
    }

    tests["SQLLogic Random Expr Test"] = {
        run('/git/sqllogictest/test/random/expr', 8, 0)
    }

    tests["SQLLogic Random Select_n1 Test"] = {
        run('/git/sqllogictest/test/random/select_n1', 8, 0)
    }

    tests["SQLLogic Random Select_n2 Test"] = {
        run('/git/sqllogictest/test/random/select_n2', 8, 0)
    }

    tests["SQLLogic Select Groupby Test"] = {
        run_two('/git/sqllogictest/test/select', 8, '/git/sqllogictest/test/random/groupby', 8, 0)
    }

    tests["SQLLogic Index Between 1 10 Test"] = {
        run_two('/git/sqllogictest/test/index/between/1', 10, '/git/sqllogictest/test/index/between/10', 8, 0)
    }

    tests["SQLLogic Index Between 100 Test"] = {
        run('/git/sqllogictest/test/index/between/100', 8, 0)
    }

    tests["SQLLogic Index Between 1000 Test"] = {
        run('/git/sqllogictest/test/index/between/1000', 8, 0)
    }

    tests["SQLLogic Index commute 10 Test"] = {
        run('/git/sqllogictest/test/index/commute/10', 8, 0)
    }

    tests["SQLLogic Index commute 100 Test"] = {
        run('/git/sqllogictest/test/index/commute/100', 8, 0)
    }

    tests["SQLLogic Index commute 1000_n1 Test"] = {
        run('/git/sqllogictest/test/index/commute/1000_n1', 8, 0)
    }

    tests["SQLLogic Index commute 1000_n2 Test"] = {
        run('/git/sqllogictest/test/index/commute/1000_n2', 8, 0)
    }

    if (TIDB_BRANCH == "master" || TIDB_BRANCH.startsWith("release-3") || TIDB_BRANCH.startsWith("release-4")) {
        tests["SQLLogic Random Aggregates_n1 Cache Test"] = {
            run('/git/sqllogictest/test/random/aggregates_n1', 8, 1)
        }

        tests["SQLLogic Random Aggregates_n2 Cache Test"] = {
            run('/git/sqllogictest/test/random/aggregates_n2', 8, 1)
        }

        tests["SQLLogic Random Expr Cache Test"] = {
            run('/git/sqllogictest/test/random/expr', 8, 1)
        }

        tests["SQLLogic Random Select_n1 Cache Test"] = {
            run('/git/sqllogictest/test/random/select_n1', 8, 1)
        }

        tests["SQLLogic Random Select_n2 Cache Test"] = {
            run('/git/sqllogictest/test/random/select_n2', 8, 1)
        }

        tests["SQLLogic Select Groupby Cache Test"] = {
            run_two('/git/sqllogictest/test/select', 8, '/git/sqllogictest/test/random/groupby', 8, 1)
        }

        tests["SQLLogic Index Between 1 10 Cache Test"] = {
            run_two('/git/sqllogictest/test/index/between/1', 10, '/git/sqllogictest/test/index/between/10', 8, 1)
        }

        tests["SQLLogic Index Between 100 Cache Test"] = {
            run('/git/sqllogictest/test/index/between/100', 8, 1)
        }

        tests["SQLLogic Index Between 1000 Cache Test"] = {
            run('/git/sqllogictest/test/index/between/1000', 8, 1)
        }

        tests["SQLLogic Index commute 10 Cache Test"] = {
            run('/git/sqllogictest/test/index/commute/10', 8, 1)
        }

        tests["SQLLogic Index commute 100 Cache Test"] = {
            run('/git/sqllogictest/test/index/commute/100', 8, 1)
        }

        tests["SQLLogic Index commute 1000_n1 Cache Test"] = {
            run('/git/sqllogictest/test/index/commute/1000_n1', 8, 1)
        }

        tests["SQLLogic Index commute 1000_n2 Cache Test"] = {
            run('/git/sqllogictest/test/index/commute/1000_n2', 8, 1)
        }
    }

    parallel tests
}


// Start main
try {
    stage("x86") {
        stage("x86 build") {
            run_build("x86", "centos7")
        }
        stage("x86 test") {
            run_test("x86", "centos7")
        }
    }
    stage("arm64") {
        stage("arm64 build") {
            run_build("arm64", "centos7")
        }
        stage("arm64 test") {
            run_test("arm64", "centos7")
        }
    }
} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    println "catch_exception FlowInterruptedException"
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
} catch (hudson.AbortException e) {
    println "catch_exception AbortException"
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
    } else {
        currentBuild.result = "FAILURE"
    }
} catch (InterruptedException e) {
    println "catch_exception InterruptedException"
    println e
    currentBuild.result = "ABORTED"
} catch (Exception e) {
    println "catch_exception Exception"
    if (e.getMessage().equals("hasBeenTested")) {
        currentBuild.result = "SUCCESS"
    } else {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
    }
}
finally {
    echo "Job finished..."
}

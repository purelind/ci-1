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

TIDB_TEST_STASH_FILE = "tidb_test_${UUID.randomUUID().toString()}.tar"
TIDB_TEST_ARM64_STASH_FILE = "tidb_test_${UUID.randomUUID().toString()}_linux_arm64.tar"


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
    def pod_java_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13_java:cached"
    def jnlp_docker_image = ""
    if (arch == "arm64") {
        pod_java_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13_java-arm64:latest"
    }
    if (is_need_go1160) {
        if (arch == "x86") {
            label = "tidb-common-test"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-common-test-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            cloud = "kubernetes-arm64"
        }
    } else {
        if (arch == "x86") {
            label = "tidb-common-test"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-common-test-arm64"
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
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            resourceLimitCpu: '30000m', resourceLimitMemory: "20Gi",
                            command: '/bin/sh -c', args: 'cat',
                    ),
                    containerTemplate(
                            name: 'java', alwaysPullImage: false,
                            image: "${pod_java_docker_image}", ttyEnabled: true,
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
        def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
        def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
        def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
        def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
        def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/done"
        if (arch == "arm64") {
            tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test-linux-arm64.tar.gz"
            tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/tidb-linux-arm64.tar.gz"
        }
         container("golang") {
            dir("go/src/github.com/pingcap/tidb") {
                timeout(10) {
                    retry(3){
                        deleteDir()
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        curl ${tidb_url} | tar xz
                        """
                    }
                }
            }

            dir("go/src/github.com/pingcap/tidb-test") {
                timeout(10) {
                    sh """
                    while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 5; done
                    curl ${tidb_test_url} | tar xz
                    export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                    # export GOPROXY=http://goproxy.pingcap.net
                    cd tidb_test && ./build.sh && cd ..
                    cd mysql_test && ./build.sh && cd ..
                    cd randgen-test && ./build.sh && cd ..
                    cd analyze_test && ./build.sh && cd ..
                    if [ \"${TIDB_BRANCH}\" != \"release-2.0\" ]; then
                        cd randgen-test && ls t > packages.list
                        split packages.list -n r/3 packages_ -a 1 --numeric-suffixes=1
                        cd ..
                    fi
                    """
                    def STASH_FILE = ""
                    if (arch == "x86") {
                        STASH_FILE = TIDB_TEST_STASH_FILE
                    }
                    if (arch == "arm64") {
                        STASH_FILE = TIDB_TEST_ARM64_STASH_FILE
                    }
                    sh """
                    echo "stash tidb-test"
                    cd .. && tar -cf $STASH_FILE tidb-test/
                    curl -F builds/pingcap/tidb-test/tmp/${STASH_FILE}=@${STASH_FILE} ${FILE_SERVER_URL}/upload
                    """
                }
            }
        }
    }
}

def run_test(arch, os, tidb_test_url, tidb_url, STASH_FILE) {
    def tests = [:]

    def run_with_log = { test_dir, log_path ->
        run_with_pod(arch, os) {
            def ws = pwd()
            deleteDir()
            println "work space path:\n${ws}"

            container("golang") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(10) {
                        retry(3){
                            deleteDir()
                            sh """
		                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
		                        curl ${tidb_url} | tar xz
		                        """
                        }
                    }
                }
                dir("go/src/github.com/pingcap") {
                    sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${STASH_FILE} | tar x
                        """
                }

                dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                    try {
                        timeout(10) {
                            sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                awk 'NR==2 {print "set -x"} 1' test.sh > tmp && mv tmp test.sh && chmod +x test.sh

                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                ./test.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                        }
                    } catch (err) {
                        sh "cat ${log_path}"
                        sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                        throw err
                    }
                }
            }
        }
    }

    def run = { test_dir ->
        if (test_dir == "mysql_test"){
            run_with_log("mysql_test", "mysql-test.out*")
        } else{
            run_with_log(test_dir, "tidb*.log")
        }
    }

    def run_split = { test_dir, chunk ->
        run_with_pod(arch, os) {
            def ws = pwd()
            deleteDir()
            println "work space path:\n${ws}"

            container("golang") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(10) {
                        retry(3){
                            deleteDir()
                            sh """
		                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
		                        curl ${tidb_url} | tar xz
		                        """
                        }
                    }
                }

                dir("go/src/github.com/pingcap") {
                    sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${STASH_FILE} | tar x
                        """
                }

                dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                    try {
                        timeout(10) {
                            sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                awk 'NR==2 {print "set -x"} 1' test.sh > tmp && mv tmp test.sh && chmod +x test.sh
                                if [ \"${TIDB_BRANCH}\" != \"release-2.0\" ]; then
                                    mv t t_bak
                                    mkdir t
                                    cd t_bak
                                    cp \$(cat ../packages_${chunk}) ../t
                                    cd ..
                                fi
                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                ./test.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                        }
                    } catch (err) {
                        sh "cat tidb*.log*"
                        sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                        throw err
                    }
                }
            }

        }
    }

    def run_cache_log = { test_dir, log_path ->
        run_with_pod(arch, os) {
            def ws = pwd()
            deleteDir()
            println "work space path:\n${ws}"

            container("golang") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(10) {
                        retry(3){
                            deleteDir()
                            sh """
		                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
		                        curl ${tidb_url} | tar xz
		                        """
                        }
                    }
                }

                dir("go/src/github.com/pingcap") {
                    sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${STASH_FILE} | tar x
                        """
                }

                dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                    try {
                        timeout(10) {
                            sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e

                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                CACHE_ENABLED=1 ./test.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                        }
                    } catch (err) {
                        sh "cat ${log_path}"
                        sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                        throw err
                    }
                }
            }
        }
    }

    def run_cache = { test_dir ->
        run_cache_log(test_dir, "tidb*.log*")
    }

    def run_vendor = { test_dir ->
        run_with_pod(arch, os) {
            def ws = pwd()
            deleteDir()
            println "work space path:\n${ws}"

            container("golang") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(10) {
                        retry(3){
                            sh """
		                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
		                        curl ${tidb_url} | tar xz
		                        """
                        }
                        sh """
                            if [ -f go.mod ]; then
                                GO111MODULE=on go mod vendor -v
                            fi
                            """
                    }
                }

                dir("go/src/github.com/pingcap/tidb_gopath") {
                    sh """
                        mkdir -p ./src
                        cp -rf ../tidb/vendor/* ./src
                        mv ../tidb/vendor ../tidb/_vendor
                        """
                }

                dir("go/src/github.com/pingcap") {
                    sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${STASH_FILE} | tar x
                        """
                }

                dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                    try {
                        timeout(10) {
                            sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e

                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                GOPATH=${ws}/go/src/github.com/pingcap/tidb-test/_vendor:${ws}/go/src/github.com/pingcap/tidb_gopath:${ws}/go \
                                ./test.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                        }
                    } catch (err) {
                        sh "cat tidb*.log"
                        sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                        throw err
                    }
                }
            }
            deleteDir()
        }
    }

    def run_jdbc = { test_dir, testsh ->
        run_with_pod(arch, os) {
            def ws = pwd()
            deleteDir()
            println "work space path:\n${ws}"

            container("java") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(10) {
                        retry(3){
                            sh """
		                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
		                        curl ${tidb_url} | tar xz
		                        """
                        }
                    }
                }

                dir("go/src/github.com/pingcap") {
                    sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${STASH_FILE} | tar x
                        """
                }

                dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                    try {
                        timeout(10) {
                            sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                mkdir -p ~/.m2 && cat <<EOF > ~/.m2/settings.xml
<settings>
  <mirrors>
    <mirror>
      <id>alimvn-central</id>
      <name>aliyun maven mirror</name>
      <url>https://maven.aliyun.com/repository/central</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
EOF
                                
                                cat ~/.m2/settings.xml || true

                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                GOPATH=disable GOROOT=disable ${testsh}
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                        }
                    } catch (err) {
                        sh "cat tidb*.log"
                        sh "cat *tidb.log"
                        sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                        throw err
                    }
                }
            }
        }
    }

    tests["TiDB Test"] = {
        run("tidb_test")
    }

    tests["Randgen Test 1"] = {
        run_split("randgen-test",1)
    }

    tests["Randgen Test 2"] = {
        run_split("randgen-test",2)
    }

    tests["Randgen Test 3"] = {
        run_split("randgen-test",3)
    }

    tests["Analyze Test"] = {
        run("analyze_test")
    }

    tests["Mysql Test"] = {
        run("mysql_test", "mysql-test.out*")
    }

    if ( TIDB_BRANCH == "master" || TIDB_BRANCH.startsWith("release-3") ) {
        tests["Mysql Test Cache"] = {
            run_cache_log("mysql_test", "mysql-test.out*")
        }
    }

    tests["JDBC Fast"] = {
        run_jdbc("jdbc_test", "./test_fast.sh")
    }

    tests["JDBC Slow"] = {
        run_jdbc("jdbc_test", "./test_slow.sh")
    }

    tests["Gorm Test"] = {
        run("gorm_test")
    }

    tests["Go SQL Test"] = {
        run("go-sql-test")
    }

    tests["DDL ETCD Test"] = {
        run_vendor("ddl_etcd_test")
    }

    parallel tests
}


// Start main
try {

    stage("prepare") {
        node("master") {
            tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
            tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
            tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/done"
        }
    }


    stage("x86") {
        def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
        def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
        def STASH_FILE = TIDB_TEST_STASH_FILE

        stage("x86 build") {
            run_build("x86", "centos7")
        }
        stage("x86 test") {
            run_test("x86", "centos7", tidb_test_url, tidb_url, STASH_FILE)
        }
    }
    stage("arm64") {
        def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test-linux-arm64.tar.gz"
        def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/tidb-linux-arm64.tar.gz"
        def STASH_FILE = TIDB_TEST_ARM64_STASH_FILE

        stage("arm64 build") {
            run_build("arm64", "centos7")
        }
        stage("arm64 test") {
            run_test("arm64", "centos7", tidb_test_url, tidb_url, STASH_FILE)
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

properties([
        parameters([
                string(
                        defaultValue: '029becc06b032412dbf00844e10a229598e9a956',
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

TIKV_BRANCH = RELEASE_BRANCH
PD_BRANCH = RELEASE_BRANCH
TIDB_TEST_BRANCH = RELEASE_BRANCH
TIDB_BRANCH = RELEASE_BRANCH
TIDB_OLD_BRANCH = RELEASE_BRANCH

if (params.containsKey("release_test") && params.triggered_by_upstream_ci == null) {
    TIKV_BRANCH = params.release_test__tikv_commit
    PD_BRANCH = params.release_test__pd_commit
    TIDB_OLD_BRANCH = params.getOrDefault('release_test__tidb_old_commit', TIDB_COMMIT)
}

K8S_NAMESPACE = "jenkins-tidb"
BRANCH_NEED_GO1160 = ["master", "release-5.1"]

ARCH = "x86" // [ x86 | arm64 ]
OS = "linux" // [ centos7 | kylin_v10 | darwin ]

tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
tidb_done_url =  "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/done"

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/done"

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
            label = "tidb-integration-common"
            pod_go_docker_image = "hub.pingcap.net/pingcap/centos7_golang-1.16:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-integration-common-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            cloud = "kubernetes-arm64"
        }
    } else {
        if (arch == "x86") {
            label = "tidb-integration-common"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-integration-common-arm64"
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

        container("golang") {
            def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
            def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
            def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
            def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
            def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
            if (arch == "arm64") {
                tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server-linux-arm64.tar.gz"
            }

            dir("go/src/github.com/pingcap/tidb") {
                timeout(10) {
                    sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        curl ${tidb_url} | tar xz
                        """
                }
            }
            dir("go/src/github.com/pingcap/tidb-test") {
                timeout(30) {
                    sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                        curl ${tidb_test_url} | tar xz
                        cd compatible_test && ./build.sh
                        """
                }
            }
        }
        stash includes: "go/src/github.com/pingcap/tidb-test/compatible_test/**", name: "compatible_test"

    }
}

def run_test(arch, os) {
    stage("${arch}  Integration compabible Test") {
        def ws = pwd()
        deleteDir()
        unstash 'compatible_test'

        dir("go/src/github.com/pingcap/tidb-test/compatible_test") {
            container("golang") {
                def tidb_old_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_OLD_BRANCH}/sha1"
                def tidb_old_sha1 = sh(returnStdout: true, script: "curl ${tidb_old_refs}").trim()
                sh """
                time curl ${tidb_old_refs}
                """
                def tidb_old_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_old_sha1}/centos7/tidb-server.tar.gz"

                def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                def pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"

                if (arch == "arm64") {
                    tidb_old_url = ""
                    tikv_url = ""
                    pd_url = ""
                }

                timeout(10) {
                    sh """
                        while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
                        curl ${tikv_url} | tar xz

                        while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                        curl ${pd_url} | tar xz

                        mkdir -p ./tidb-old-src
                        echo ${tidb_old_url}
                        echo ${tidb_old_refs}
                        while ! curl --output /dev/null --silent --head --fail ${tidb_old_url}; do sleep 15; done
                        curl ${tidb_old_url} | tar xz -C ./tidb-old-src

                        mkdir -p ./tidb-src
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        curl ${tidb_url} | tar xz -C ./tidb-src

                        mv tidb-old-src/bin/tidb-server bin/tidb-server-old
                        mv tidb-src/bin/tidb-server ./bin/tidb-server
                        """
                }

                timeout(10) {
                    try {
                        sh """
                            set +e 
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            rm -rf /tmp/tidb
                            set -e

                            export log_level=debug
                            TIKV_PATH=./bin/tikv-server \
                            TIDB_PATH=./bin/tidb-server \
                            PD_PATH=./bin/pd-server \
                            UPGRADE_PART=tidb \
                            NEW_BINARY=./bin/tidb-server \
                            OLD_BINARY=./bin/tidb-server-old \
                            ./test.sh 2>&1
                            """
                    } catch (err) {
                        sh "cat tidb*.log"
                        throw err
                    } finally {
                        sh """
                            set +e 
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            set -e
                            """
                    }
                }
            }
        }
    }
}


// Start main
try {
    parallel (
            test_x86: {
                stage("build") {
                    run_build("x86", "centos7")
                }
                stage("test") {
                    run_test("x86", "centos7")
                }
            },
            test_arm64_centos7: {

            },
            test_arm64_kylin_v10: {

            },
    )
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


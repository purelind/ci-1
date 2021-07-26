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

TIKV_BRANCH = RELEASE_BRANCH
PD_BRANCH = RELEASE_BRANCH
TIDB_BRANCH = RELEASE_BRANCH
COPR_TEST_BRANCH = RELEASE_BRANCH

if (params.containsKey("release_test") && params.triggered_by_upstream_ci == null) {
    TIKV_BRANCH = params.release_test__tikv_commit
    PD_BRANCH = params.release_test__pd_commit
}

K8S_NAMESPACE = "jenkins-tidb"
BRANCH_NEED_GO1160 = ["master", "release-5.1"]

//ARCH = "x86" // [ x86 | arm64 ]
//OS = "linux" // [ centos7 | kylin_v10 | darwin ]

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
            label = "tidb-integration-corp-test"
            pod_go_docker_image = "hub.pingcap.net/pingcap/centos7_golang-1.16:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-integration-corp-test-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            cloud = "kubernetes-arm64"
        }
    } else {
        if (arch == "x86") {
            label = "tidb-integration-corp-test"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-integration-corp-test-arm64"
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


def run_test(arch, os) {
    run_with_pod(arch, os) {
        def ws = pwd()

        stage("${arch} Prepare") {
            dir("copr-test") {
                timeout(30) {
                    checkout(changelog: false, poll: false, scm: [
                            $class: "GitSCM",
                            branches: [ [ name: COPR_TEST_BRANCH ] ],
                            userRemoteConfigs: [
                                    [
                                            url: 'https://github.com/tikv/copr-test.git',
                                            refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*/head:refs/remotes/origin/pr/*',
                                    ]
                            ],
                            extensions: [
                                    [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'],
                                    [$class: 'CloneOption', depth: 1, noTags: false, reference: '', shallow: true]
                            ],
                    ])
                }
            }
            container("golang") {
                def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
                def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                def pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
                def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/done"
                if (arch == "arm64") {
                    tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-linux-arm64.tar.gz"
                    tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/tidb-linux-arm64.tar.gz"
                    pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-linux-arm64.tar.gz"
                }

                dir("tikv"){
                    deleteDir()
                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
                        curl ${tikv_url} | tar xz
                        """
                    }
                }
                dir("pd") {
                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                        curl ${pd_url} | tar xz
                        """
                    }
                }
                dir("tidb") {
                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        curl ${tidb_url} | tar xz
                        """
                    }
                }
            }
        }

        stage("${arch}  Integration Push Down Test") {
            def pd_bin = "${ws}/pd/bin/pd-server"
            def tikv_bin = "${ws}/tikv/bin/tikv-server"
            def tidb_src_dir = "${ws}/tidb"
            dir('copr-test') {
                container('golang') {
                    try {
                        timeout(30) {
                            sh """
                            pd_bin=${pd_bin} tikv_bin=${tikv_bin} tidb_src_dir=${tidb_src_dir} make push-down-test
                            """
                        }
                    } catch (Exception e) {
                        def build_dir = "push-down-test/build"
                        sh "cat ${build_dir}/tidb_no_push_down.log || true"
                        sh "cat ${build_dir}/tidb_with_push_down.log || true"
                        sh "cat ${build_dir}/tikv_with_push_down.log || true"
                        sh "echo Test failed. Check out logs above."
                        throw e;
                    }

                }
            }
        }
    }
}

// Start main
try {
    stage("x86") {
        stage("x86 test") {
            run_test("x86", "centos7")
        }
    }
    stage("arm64") {
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





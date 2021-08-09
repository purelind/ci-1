properties([
        parameters([
                string(
                        defaultValue: '15e53d94e7812b16512fb674361224fe36571453',
                        name: 'TICDC_COMMIT',
                        trim: true
                ),
                string(
                        defaultValue: 'master',
                        name: 'RELEASE_BRANCH',
                        trim: true
                ),
                string(
                        defaultValue: 'master',
                        name: 'TIDB_BRANCH_OR_COMMIT',
                        trim: true
                ),
                string(
                        defaultValue: 'b32ee4afcb2932b0b4bec6ebeef24bd0547dbf5a',
                        name: 'TIKV_BRANCH_OR_COMMIT',
                        trim: true
                ),
                string(
                        defaultValue: 'master',
                        name: 'PD_BRANCH_OR_COMMIT',
                        trim: true
                ),
                string(
                        defaultValue: 'master',
                        name: 'TOOLS_BRANCH_OR_COMMIT',
                        trim: true
                ),
                string(
                        defaultValue: 'master/b56edc0b166effd89ed85f81df57a9fb620397fa',
                        name: 'TIFLASH_BRANCH_AND_COMMIT',
                        trim: true
                ),
        ])
])

echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    RELEASE_BRANCH = params.getOrDefault("release_test__release_branch", "")
    TICDC_COMMIT = params.getOrDefault("release_test__cdc_commit", "")
    TIDB_BRANCH_OR_COMMIT = params.getOrDefault("release_test__tidb_commit", "")
    TIKV_BRANCH_OR_COMMIT = params.getOrDefault("release_test__tikv_commit", "")
    PD_BRANCH_OR_COMMIT = params.getOrDefault("release_test__pd_commit", "")
    TIFLASH_BRANCH_AND_COMMIT = params.getOrDefault("release_test__tiflash_commit", "")
}


K8S_NAMESPACE = "jenkins-tidb"
BRANCH_NEED_GO1160 = ["master", "release-5.1"]

specStr = "+refs/heads/*:refs/remotes/origin/*"
ticdc_src_url = "git@github.com:pingcap/ticdc.git"


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


def run_test(arch, os) {
    node("${GO_TEST_SLAVE}") {
        def common_groovy_file_url = "https://raw.githubusercontent.com/purelind/ci-1/purelind/tidb-arm64-daily-build/jenkins/pipelines/ci/arm64/cdc_integration_test_arm64_common.groovy"
        sh "curl -L ${common_groovy_file_url} -o cdc_integration_test_arm64_common.grovy"
        def ws = pwd()

        def script_path = "${ws}/cdc_integration_test_arm64_common.grovy"
        def common = load script_path

        common.prepare_binaries(arch, os, is_need_go1160, "mysql")
        common.tests("mysql", arch, os)
        // common.debug_tests("mysql", arch, os)
    }

}


// Start main
try {

    stage("Checkout") {
        node("${GO_TEST_SLAVE}") {
            container("golang") {
                def ws = pwd()
                deleteDir()

                dir("${ws}/go/src/github.com/pingcap/ticdc") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/ticdc"
                        deleteDir()
                    }
                    try {
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/ticdc.git']]]
                    } catch (info) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 5
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/ticdc.git']]]
                        }
                    }
                    sh "git checkout -f ${TICDC_COMMIT}"
                }

                stash includes: "go/src/github.com/pingcap/ticdc/**", name: "ticdc", useDefaultExcludes: false
            }

        }
    }

    parallel(
        "x86": {
            stage("x86 test") {
                run_test("x86", "centos7")
            }
        },

        "arm64": {
            stage("arm64 test") {
                run_test("arm64", "centos7")
            }
        }
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


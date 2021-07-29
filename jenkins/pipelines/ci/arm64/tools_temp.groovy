def TIKV_BRANCH = "master"
def PD_BRANCH = "master"
def TIDB_BRANCH = "master"

if (params.containsKey("release_test")) {
    echo "this build is triggered by qa for release testing"
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tools_commit)
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
    TIDB_BRANCH = ghprbTargetBranch
    TIKV_BRANCH = ghprbTargetBranch
    PD_BRANCH = ghprbTargetBranch
}

def specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

// parse tikv branch
def m1 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIKV_BRANCH = "${m1[0][1]}"
}
m1 = null
println "TIKV_BRANCH=${TIKV_BRANCH}"
// parse pd branch
def m2 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    PD_BRANCH = "${m2[0][1]}"
}
m2 = null
println "PD_BRANCH=${PD_BRANCH}"
// parse tidb branch
def m3 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_BRANCH = "${m3[0][1]}"
}
m3 = null
println "TIDB_BRANCH=${TIDB_BRANCH}"

catchError {
    stage('Prepare') {
        node ("${GO_BUILD_SLAVE}") {
            container("golang") {
                def ws = pwd()
                deleteDir()
                // tidb-tools
                dir("/home/jenkins/agent/git/tidb-tools") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    try {
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb-tools.git']]]
                    } catch (error) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 60
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb-tools.git']]]
                        }
                    }
                }

                dir("go/src/github.com/pingcap/tidb-tools") {
                    sh """
                        cp -R /home/jenkins/agent/git/tidb-tools/. ./
                        git checkout -f ${ghprbActualCommit}
                    """
                }

                stash includes: "go/src/github.com/pingcap/tidb-tools/**", name: "tidb-tools", useDefaultExcludes: false

                // tikv
                def tikv_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1").trim()
                sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz | tar xz"
                // pd
                def pd_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1").trim()
                sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz | tar xz"
                // tidb
                def tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
                sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz"

                // tools
                sh "curl https://download.pingcap.org/tidb-enterprise-tools-nightly-linux-amd64.tar.gz | tar xz"
                sh "mv tidb-enterprise-tools-nightly-linux-amd64/bin/importer bin/"
                sh "mv tidb-enterprise-tools-nightly-linux-amd64/bin/loader bin/"
                sh "mv tidb-enterprise-tools-nightly-linux-amd64/bin/mydumper bin/"
                sh "rm -r tidb-enterprise-tools-nightly-linux-amd64"

                stash includes: "bin/**", name: "binaries"

                sh "ls -l ./"
                sh "ls -l ./bin"
            }
        }
    }

    stage('Integration Test') {
        def tests = [:]
        def label = "tools-integration-${UUID.randomUUID().toString()}"

        tests["Integration Test"] = {
            podTemplate(label: label, nodeSelector: "role_type=slave", containers: [
                    containerTemplate(name: 'golang',alwaysPullImage: true,image: 'hub.pingcap.net/jenkins/centos7_golang-1.13:cached', ttyEnabled: true, command: 'cat'),
                    containerTemplate(
                            name: 'mysql',
                            image: 'registry-mirror.pingcap.net/library/mysql:5.6',
                            ttyEnabled: true,
                            alwaysPullImage: false,
                            envVars: [
                                    envVar(key: 'MYSQL_ALLOW_EMPTY_PASSWORD', value: '1'),
                            ],
                            args: '--log-bin --binlog-format=ROW --server-id=1',
                    )
            ]) {
                node(label) {
                    container("golang") {
                        def ws = pwd()
                        deleteDir()
                        unstash "tidb-tools"
                        unstash 'binaries'
                        dir("go/src/github.com/pingcap/tidb-tools") {
                            sh "mv ${ws}/bin ./bin/"
                            try {
                                sh """
                               for i in {1..10} mysqladmin ping -h0.0.0.0 -P 3306 -uroot --silent; do if [ \$? -eq 0 ]; then break; else if [ \$i -eq 10 ]; then exit 2; fi; sleep 1; fi; done
                                export MYSQL_HOST=127.0.0.1
                                export MYSQL_PORT=3306
                                make integration_test
                                """
                            } catch (Exception e) {
                                sh """
                                for filename in `ls /tmp/tidb_tools_test/*/*.log`; do
                                    echo "**************************************"
                                    echo "\$filename"
                                    cat "\$filename"
                                done
                                echo "fix.sql"
                                cat /tmp/tidb_tools_test/sync_diff_inspector/fix.sql
                                """
                                throw e;
                            }
                        }
                    }
                }
            }
        }

        parallel tests
    }

    currentBuild.result = "SUCCESS"
}

stage('Summary') {
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "Integration Common Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }

}

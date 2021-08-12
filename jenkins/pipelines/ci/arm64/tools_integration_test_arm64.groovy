properties([
        parameters([
                string(
                        defaultValue: '591ebdd9263694d88d6efc365dba14db9e8c7439',
                        name: 'TOOLS_COMMIT',
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
    TOOLS_COMMIT = params.getOrDefault("release_test__tools_commit", "")
}

TIKV_BRANCH = RELEASE_BRANCH
PD_BRANCH = RELEASE_BRANCH
TIDB_BRANCH = RELEASE_BRANCH


if (params.containsKey("release_test") && params.triggered_by_upstream_ci == null) {
    TIKV_BRANCH = params.release_test__tikv_commit
    PD_BRANCH = params.release_test__pd_commit
    TIDB_BRANCH = params.release_test_tidb_commit
}

specStr = "+refs/heads/*:refs/remotes/origin/*"

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
    def pod_mysql_docker_image = ""
    def jnlp_docker_image = ""
    if (arch == "x86") {
        label = "tools-integration-test"
        pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
        pod_mysql_docker_image = "registry-mirror.pingcap.net/library/mysql:5.6"
        jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    }
    if (arch == "arm64") {
        label = "tools-integration-test-arm64"
        pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13-arm64:latest"
        pod_mysql_docker_image = "hub.pingcap.net/jenkins/mysql-arm64:5.7"
        jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
        cloud = "kubernetes-arm64"
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
                            name: 'mysql',
                            image: "${pod_mysql_docker_image}",
                            ttyEnabled: true,
                            alwaysPullImage: true,
                            envVars: [
                                    envVar(key: 'MYSQL_ALLOW_EMPTY_PASSWORD', value: '1'),
                            ],
                            args: '--log-bin --binlog-format=ROW --server-id=1',
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
                    git checkout -f ${TOOLS_COMMIT}
                """
            }

            stash includes: "go/src/github.com/pingcap/tidb-tools/**", name: "tidb-tools-${os}-${arch}", useDefaultExcludes: false

            // tikv
            def tikv_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1").trim()
            def tikv_file = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
            // pd
            def pd_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1").trim()
            def pd_file = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
            // tidb
            def tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
            def tidb_file = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"

            if (arch == "arm64") {
                tikv_file = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-linux-arm64.tar.gz"
                pd_file = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-linux-arm64.tar.gz"
                tidb_file = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-linux-arm64.tar.gz"
            }
            sh """
            curl ${tikv_file} | tar xz
            curl ${pd_file} | tar xz
            curl ${tidb_file} | tar xz
            """

            def tidb_enterprise_tools_file = "https://download.pingcap.org/tidb-enterprise-tools-nightly-linux-amd64.tar.gz"
            if (arch == "arm64") {
                // TODO missing tidb-enterprise-tools linux arm64 tar
                tidb_enterprise_tools_file = " http://fileserver.pingcap.net/download/builds/pingcap/test/tidb-enterprise-tools/f505ab3ce55cd9cbb29e2346317164055a1b1c15/centos7/tidb-enterprise-tools-linux-arm64.tar.gz"
            }

            // tools
            sh """
            curl ${tidb_enterprise_tools_file} | tar xz
            mv tidb-enterprise-tools-nightly-linux-amd64/bin/importer bin/
            mv tidb-enterprise-tools-nightly-linux-amd64/bin/loader bin/
            mv tidb-enterprise-tools-nightly-linux-amd64/bin/mydumper bin/
            rm -r tidb-enterprise-tools-nightly-linux-amd64
            ls -l ./ && ls -l ./bin
            """

            stash includes: "bin/**", name: "binaries-${os}-${arch}"

            sh "ls -l ./ && ls -l ./bin"
        }
    }
}

def run_test(arch, os) {
    run_with_pod(arch, os) {
        container("golang") {
            def ws = pwd()
            deleteDir()
            unstash "tidb-tools-${os}-${arch}"
            unstash "binaries-${os}-${arch}"

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


// Start main
try {
    stage("x86") {
        stage("build") {
            run_build("x86", "centos7")
        }
        stage("test") {
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


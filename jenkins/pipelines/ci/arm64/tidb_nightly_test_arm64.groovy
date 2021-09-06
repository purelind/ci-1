

specStr = "+refs/heads/*:refs/remotes/origin/*"
BUILD_URL = 'git@github.com:pingcap/tidb.git'

branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
PLUGIN_BRANCH = branch
pluginSpec = "+refs/heads/*:refs/remotes/origin/*"
specStr = "+refs/heads/*:refs/remotes/origin/*"
REPO_URL = 'git@github.com:pingcap/tidb.git'
slackcolor = 'good'
githash = ""

K8S_NAMESPACE = "jenkins-tidb"
BRANCH_NEED_GO1160 = ["master", "release-5.1"]

branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"


def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}
is_need_go1160 = isBranchMatched(BRANCH_NEED_GO1160, branch)


def do_checkout(branch, refspec) {
    checkout(
        changelog: false,
        poll: false,
        scm: [$class: 'GitSCM',
                branches: [[name: "${branch}"]],
                doGenerateSubmoduleConfigurations: false,
                extensions: [[$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
                    [$class: 'CloneOption', timeout: 2]],
                submoduleCfg: [],
                userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                    refspec: refspec,
                    url: 'git@github.com:pingcap/tidb.git']]]

    )
}

def checkout_tidb(branch) {
    def refspec = "+refs/heads/*:refs/remotes/origin/*"
    println "branch=${branch}"
    println "refspec=${refspec}"
    def ws = pwd()

    stage("Checkout") {
        dir("go/src/github.com/pingcap/tidb") {
            container("golang") {
                sh "whoami && go version"
                // update code
                dir("/home/jenkins/agent/code-archive") {
                    // delete to clean workspace in case of agent pod reused lead to conflict.
                    deleteDir()
                    // copy code from nfs cache
                    if (fileExists("/nfs/cache/git-test/src-tidb.tar.gz")) {
                        timeout(5) {
                            sh """
                                cp -R /home/jenkins/agent/ci-cached-code-daily/src-tidb.tar.gz*  ./
                                mkdir -p ${ws}/go/src/github.com/pingcap/tidb
                                tar -xzf src-tidb.tar.gz -C ${ws}/go/src/github.com/pingcap/tidb --strip-components=1
                            """
                        }
                    }
                }
                dir("go/src/github.com/pingcap/tidb") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/tidb"
                        echo "Clean dir then get tidb src code from file-server"
                        deleteDir()
                    }
                    if (!fileExists("${ws}/go/src/github.com/pingcap/tidb/Makefile")) {
                        dir("${ws}/go/src/github.com/pingcap/tidb") {
                            sh """
                            rm -rf /home/jenkins/agent/code-archive/tidb.tar.gz
                            rm -rf /home/jenkins/agent/code-archive/tidb
                            wget -O /home/jenkins/agent/code-archive/tidb.tar.gz  ${FILE_SERVER_URL}/download/source/tidb.tar.gz -q --show-progress
                            tar -xzf /home/jenkins/agent/code-archive/tidb.tar.gz -C ./ --strip-components=1
                        """
                        }
                    }
                    try {
                        do_checkout(branch, refspec)
                    } catch (info) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 5
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            do_checkout(branch, refspec)
                        }
                    }
                    timeout(5) {
                        sh """
                        mkdir -p ${ws}/go/src/github.com/pingcap/tidb-build-plugin/
                        cp -R ./* ${ws}/go/src/github.com/pingcap/tidb-build-plugin/
                        """
                    }
                    githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()

                    println githash
                }
            }
        }
    }
}

def run_with_pod(arch, os, Closure body) {
    def label = ""
    def cloud = "kubernetes"
    def pod_go_docker_image = ""
    def jnlp_docker_image = ""
    if (is_need_go1160) {
        if (arch == "x86") {
            label = "tidb-nightly-test"
            pod_go_docker_image = "hub.pingcap.net/pingcap/centos7_golang-1.16:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-nightly-test-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            cloud = "kubernetes-arm64"
        }
    } else {
        if (arch == "x86") {
            label = "tidb-nightly-test"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-nightly-test-arm64"
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
                            envVars: [containerEnvVar(key: 'GOMODCACHE', value: '/nfs/cache/mod'),
                                      containerEnvVar(key: 'GOPATH', value: '/go')],
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
            volumes: [
                    nfsVolume( mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false ),
                    nfsVolume( mountPath: '/nfs/cache', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs', readOnly: false ),
            ],
    ) {
        node(label) {
            println "debug command on ${os} ${arch}:\nkubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

def run_test(arch, os) {
    run_with_pod(arch, os) {
        checkout_tidb(branch)
        try {
            def ws = pwd()
            stage("Build tidb-server and plugin") {
                container("golang") {
                    stage("Build TiDB") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                sh """
                            nohup bash -c "if make importer ;then touch importer.done;else touch importer.fail; fi"  > importer.log &
                            nohup bash -c "if  WITH_CHECK=1 make TARGET=bin/tidb-server-check ;then touch tidb-server-check.done;else touch tidb-server-check.fail; fi" > tidb-server-check.log &	                                
                            make
                            touch tidb-server-check.done
                            """
                            }
                            waitUntil {
                                (fileExists('importer.done') || fileExists('importer.fail')) && (fileExists('tidb-server-check.done') || fileExists('tidb-server-check.fail'))
                            }
                            sh """
                            ls bin
                            """
                            if (fileExists('importer.fail')) {
                                sh """
                                cat importer.log
                                exit 1
                                """
                            }
                            if (fileExists('tidb-server-check.fail')) {
                                sh """
                                cat tidb-server-check.log
                                exit 1
                                """
                            }
                        }
                    }

                    stage("Build plugin") {
                        println "Start build tidb plugin"
                        dir("go/src/github.com/pingcap/tidb-build-plugin") {
                            timeout(20) {
                                sh """
                                cd cmd/pluginpkg
                                go build
                                """
                            }
                        }
                        dir("go/src/github.com/pingcap/enterprise-plugin") {
                            checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${PLUGIN_BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: pluginSpec, url: 'git@github.com:pingcap/enterprise-plugin.git']]]
                            pluginGithash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                            println "pluginGithash = ${pluginGithash}"
                        }
                        dir("go/src/github.com/pingcap/enterprise-plugin/whitelist") {
                            sh """
                            GO111MODULE=on go mod tidy
                            ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist
                            """
                        }
                        dir("go/src/github.com/pingcap/enterprise-plugin/audit") {
                            sh """
                            GO111MODULE=on go mod tidy
                            ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit
                            """
                        }
                    }

                    stage("Loading Plugin test") {
                        dir("go/src/github.com/pingcap/tidb") {
                            try {
                                sh """
                                rm -rf /tmp/tidb
                                mkdir -p plugin-so
                                cp ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit/audit-1.so ./plugin-so/
                                cp ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist/whitelist-1.so ./plugin-so/
                                ${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server -plugin-dir=${ws}/go/src/github.com/pingcap/tidb/plugin-so -plugin-load=audit-1,whitelist-1 > /tmp/loading-plugin.log 2>&1 &
            
                                sleep 5
                                for i in 1 2 3; do mysql -h 127.0.0.1 -P 4000 -u root -e "select tidb_version()"  && break || sleep 5; done
                                """
                            } catch (error) {
                                sh """
                                cat /tmp/loading-plugin.log
                                """
                                throw error
                            } finally {
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                set -e
                                """
                            }
                        }

                    }
                }
            }

            stage("Check") {
                stage("Build & Test") {
                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            // sh "make check"
                            sh "make test_part_1"
                            sh "EXTRA_TEST_ARGS='-timeout 9m'  make test_part_2"
                        }
                    }
                }
            }

            currentBuild.result = "SUCCESS"
        } catch (Exception e) {
            currentBuild.result = "FAILURE"
            slackcolor = 'danger'
            echo "${e}"
        }
    }
}

// Start main
try {
    parallel(
            x86: {
                stage("x86 test") {
                    run_test("x86", "centos7")
                }
            },
            arm64: {
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


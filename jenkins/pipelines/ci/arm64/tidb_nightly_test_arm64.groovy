def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = isBranchMatched(["master", "release-5.1"], env.BRANCH_NAME)
if (isNeedGo1160) {
    println "This build use go1.16"
    POD_GO_DOCKER_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
} else {
    println "This build use go1.13"
    POD_GO_DOCKER_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
}

JNLP_DOCKER_IMAGE = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"

def specStr = "+refs/heads/*:refs/remotes/origin/*"
def label = 'tidb-build-arm64'
def BUILD_URL = 'git@github.com:pingcap/tidb.git'
def slackcolor = 'good'
def githash
def golangciLintVer = 'v1.29.0'

def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
def PLUGIN_BRANCH = branch
pluginSpec = "+refs/heads/*:refs/remotes/origin/*"

def isNeedBuildPlugin = false
if (branch == "master" || (branch.startsWith("release") && branch != "release-2.0" && branch != "release-2.1")) {
    isNeedBuildPlugin = true
}



podTemplate(label: label,
        cloud: "kubernetes-arm64",
        namespace: 'jenkins-tidb',
        containers: [
                containerTemplate(
                        name: 'golang', alwaysPullImage: false,
                        image: "${POD_GO_DOCKER_IMAGE}", ttyEnabled: true,
                        resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                        resourceLimitCpu: '30000m', resourceLimitMemory: "20Gi",
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOMODCACHE', value: '/nfs/cache/mod'),
                                  containerEnvVar(key: 'GOPATH', value: '/go')],
                ),
                containerTemplate(
                        name: 'jnlp', image: "${JNLP_DOCKER_IMAGE}", alwaysPullImage: false,
                        resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                ),
        ],
        volumes: [
                nfsVolume( mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                        serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false ),
                nfsVolume( mountPath: '/nfs/cache', serverAddress: '172.16.5.22',
                        serverPath: '/mnt/ci.pingcap.net-nfs', readOnly: false ),
        ],
        idleMinutes: 10
) {
    node(label) {

        stage("debug info") {
            println "Current trigger branch=branch"
            println "ENTERPRISE_PLUGIN_BRANCH=${PLUGIN_BRANCH}"
            println "isNeedBuildPlugin=${isNeedBuildPlugin}"
            println "POD_GO_DOCKER_IMAGE=${POD_GO_DOCKER_IMAGE}"
            println "golangciLintVer"
        }
        try {

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
                                cp -R /nfs/cache/git-test/src-tidb.tar.gz*  ./
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
                                checkout changelog: false, poll: false,
                                        scm: [$class: 'GitSCM',
                                              branches: [[name: branch]],
                                              doGenerateSubmoduleConfigurations: false,
                                              extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]],
                                              submoduleCfg: [],
                                              userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                            } catch (info) {
                                retry(2) {
                                    echo "checkout failed, retry.."
                                    sleep 5
                                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                        deleteDir()
                                    }
                                    // if checkout one pr failed, we fallback to fetch all thre pr data
                                    checkout changelog: false, poll: false,
                                            scm: [$class: 'GitSCM',
                                                  branches: [[name: branch]],
                                                  doGenerateSubmoduleConfigurations: false,
                                                  extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']],
                                                  submoduleCfg: [],
                                                  userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                                }
                            }
                            timeout(5) {
                                sh """
                            mkdir -p ${ws}/go/src/github.com/pingcap/tidb-build-plugin/
                            cp -R ./* ${ws}/go/src/github.com/pingcap/tidb-build-plugin/
                            """
                            }
                            githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                        }
                    }
                }
            }

            stage("Build tidb-server and plugin") {
                container("golang") {

                    def builds = [:]

                    builds["Build and upload TiDB"] = {
                        stage("Build") {
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

//                    stage("Upload") {
//
//                        def filepath = "builds/pingcap/tidb/branch/${githash}/centos7-arm64/tidb-server.tar.gz"
//                        def donepath = "builds/pingcap/tidb/branch/${githash}/centos7-arm64/done"
//                        def refspath = "refs/pingcap/tidb/branch/${branch}/centos7-arm64/sha1"
//
//                        dir("go/src/github.com/pingcap/tidb") {
//                            timeout(10) {
//                                sh """
//                                rm -rf .git
//                                tar czvf tidb-server.tar.gz ./*
//                                curl -F ${filepath}=@tidb-server.tar.gz ${FILE_SERVER_URL}/upload
//                                echo "pr/${githash}" > sha1
//                                echo "done" > done
//                                # sleep 1
//                                curl -F ${donepath}=@done ${FILE_SERVER_URL}/upload
//                                curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
//                                """
//                            }
//                        }
//                    }
                    }

                    builds["Build plugin"] = {
                        if (isNeedBuildPlugin) {
                            println "Start build tidb plugin"
                            stage("Build plugins") {
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
                        } else {
                            println "Skip build tidb plugin due to current branch=${branch}"
                        }
                    }

                    parallel builds

                }
            }

            if (isNeedBuildPlugin) {
                stage("Loading Plugin test") {
                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            try {
                                sh """
                            rm -rf /tmp/tidb
                            mkdir -p plugin-so
                            cp ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit/audit-1.so ./plugin-so/
                            cp ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist/whitelist-1.so ./plugin-so/
                            ${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server -plugin-dir=${ws}/go/src/github.com/pingcap/tidb/plugin-so -plugin-load=audit-1,whitelist-1 > /tmp/loading-plugin.log 2>&1 &
        
                            sleep 5
                            mysql -h 127.0.0.1 -P 4000 -u root -e "select tidb_version()"
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
                        dir("/home/jenkins/agent/git/tools") {
                            if (!fileExists("/home/jenkins/agent/git/tools/bin/golangci-lint")) {
                                container("golang") {
                                    dir("/home/jenkins/agent/git/tools/") {
                                        sh """
                                            curl -sfL https://raw.githubusercontent.com/golangci/golangci-lint/master/install.sh| sh -s -- -b ./bin ${golangciLintVer}
                                        """
                                    }
                                }
                            }
                        }
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(30) {
                                sh """
                                mkdir -p tools/bin
                                cp /home/jenkins/agent/git/tools/bin/golangci-lint tools/bin/
                                ls -al tools/bin || true
                                # GOPROXY=http://goproxy.pingcap.net
                                """
                            }
                            try {
                                if (branch == "master") {
                                    def builds = [:]
                                    builds["check"] = {
                                        sh """
                                        go version && ls -alh tools/bin/
                                        ./tools/bin/golangci-lint --version
                                        """
                                        sh "make check"
                                    }
                                    builds["test_part_1"] = {
                                        try {
                                            sh "make test_part_1"
                                        } catch (err) {
                                            throw err
                                        } finally {
                                            sh "cat cmd/explaintest/explain-test.out || true"
                                        }
                                    }
                                    parallel builds
                                } else {
                                    sh "make dev"
                                }
                            } catch (err) {
                                throw err
                            } finally {
                                sh "cat cmd/explaintest/explain-test.out || true"
                            }
                        }
                    }
                }

                stage("Check go mod replace is removed") {
                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                sh """
                            if [ \"${branch}\" == \"master\" ] ;then ./tools/check/check_parser_replace.sh ;fi
                            """
                            }
                        }
                    }
                }
            }

            // TODO run check_2, this test need pd & tikv binaries
//        stage("Check2") {
//
//        }
//
            currentBuild.result = "SUCCESS"
        } catch (Exception e) {
            currentBuild.result = "FAILURE"
            slackcolor = 'danger'
            echo "${e}"
        }
    }
}



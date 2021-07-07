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
    // POD_GO_DOCKER_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
    POD_GO_DOCKER_IMAGE = "wulifu/go1164-arm64:latest"
} else {
    println "This build use go1.13"
    // POD_GO_DOCKER_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
    POD_GO_DOCKER_IMAGE = "wulifu/go1164-arm64:latest"
}

JNLP_DOCKER_IMAGE = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
// JNLP_DOCKER_IMAGE = "wulifu/jnlp-arm64:latest"

def specStr = "+refs/heads/*:refs/remotes/origin/*"
def label = 'pd-build-arm64'
def REPO_URL = 'git@github.com:pingcap/pd.git'
def slackcolor = 'good'
def githash

def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
def target = "pd-${branch}-linux-arm64"

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
        def ws = pwd()

        stage("debug info") {
            println '================= ALL DEBUG INFO ================='
            println "arm64 debug command:\nkubectl -n jenkins-tidb exec -ti ${NODE_NAME} bash"
            println "work space path:\n${ws}"
            println "Current trigger branch=${branch}"
            println "POD_GO_DOCKER_IMAGE=${POD_GO_DOCKER_IMAGE}"
        }
        try {
            stage("Checkout") {
                // update cache
                dir("go/src/github.com/pingcap/pd") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    checkout changelog: false, poll: false,
                            scm: [$class: 'GitSCM',
                                  branches: [[name: branch ]],
                                  doGenerateSubmoduleConfigurations: false,
                                  extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']],
                                  submoduleCfg: [],
                                  userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: REPO_URL ]]]

                    githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                }
            }

            stage("Build") {
                dir("go/src/github.com/pingcap/pd") {
                    container("golang") {
                        timeout(20) {
                            sh """
                            go version
                            WITH_RACE=1 make && mv bin/pd-server bin/pd-server-race
                            make
                            make tools
                            """
                        }
                    }
                }
            }

            stage("Check") {
                dir("go/src/github.com/pingcap/pd") {
                    container("golang") {
                        timeout(20) {
                            sh """
                            make ci
                            """
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
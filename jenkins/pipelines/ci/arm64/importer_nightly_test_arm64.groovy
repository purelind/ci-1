JNLP_DOCKER_IMAGE = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
RUST_DOCKER_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.13_rust-arm64:latest"

def specStr = "+refs/heads/*:refs/remotes/origin/*"
def label = 'importer-build-arm64'
def REPO_URL = 'git@github.com:tikv/importer.git'
def slackcolor = 'good'
def githash

def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
def target = "importer-${branch}-linux-arm64"

podTemplate(label: label,
        cloud: "kubernetes-arm64",
        namespace: 'jenkins-tidb',
        containers: [
                containerTemplate(
                        name: 'rust', alwaysPullImage: true,
                        image: "${RUST_DOCKER_IMAGE}", ttyEnabled: true,
                        resourceRequestCpu: '4000m', resourceRequestMemory: '10Gi',
                        command: '/bin/sh -c', args: 'cat',
                ),
                containerTemplate(
                        name: 'jnlp', image: "${JNLP_DOCKER_IMAGE}", alwaysPullImage: true,
                        resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                ),
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
            println "RUST_DOCKER_IMAGE=${RUST_DOCKER_IMAGE}"
        }
        try {
            stage("Checkout") {
                // update cache
                dir("go/src/github.com/pingcap/importer") {
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
                    println githash
                }
            }

            stage("Build") {
                dir("go/src/github.com/pingcap/importer") {
                    container("rust") {
                        timeout(20) {
                            sh """
                            grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                            if [[ ! "0.8.0" > "\\$grpcio_ver" ]]; then
                                echo using gcc 8
                                source /opt/rh/devtoolset-8/enable
                            fi
                            ROCKSDB_SYS_SSE=0 make release
                            """
                        }
                    }
                }
            }

            stage("Check") {
                dir("go/src/github.com/pingcap/importer") {
                    container("rust") {
                        timeout(20) {
                            sh """
                            grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                            if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                                echo using gcc 8
                                source /opt/rh/devtoolset-8/enable
                            fi
                            ROCKSDB_SYS_SSE=0 make test
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
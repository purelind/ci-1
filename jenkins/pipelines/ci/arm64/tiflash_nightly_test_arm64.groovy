

POD_RUST_DOCKER_IMAGE =  "hub.pingcap.net/jenkins/centos7_golang-1.13_rust-arm64:latest"
JNLP_DOCKER_IMAGE = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"


def specStr = "+refs/heads/*:refs/remotes/origin/*"
def label = 'tics-build-arm64'
def REPO_URL = 'git@github.com:pingcap/tics.git'
def slackcolor = 'good'
def githash

def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
def target = "tics-${branch}-linux-arm64"

podTemplate(label: label,
        cloud: "kubernetes-arm64",
        namespace: 'jenkins-tidb',
        containers: [
                containerTemplate(
                        name: 'rust', alwaysPullImage: false,
                        image: "${POD_RUST_DOCKER_IMAGE}", ttyEnabled: true,
                        resourceRequestCpu: '6000m', resourceRequestMemory: '8Gi',
                        resourceLimitCpu: '30000m', resourceLimitMemory: "20Gi",
                        command: '/bin/sh -c', args: 'cat',
                ),
                containerTemplate(
                        name: 'jnlp', image: "${JNLP_DOCKER_IMAGE}", alwaysPullImage: false,
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
            println "POD_RUST_DOCKER_IMAGE=${POD_RUST_DOCKER_IMAGE}"
        }
        try {
            stage("Checkout") {
                checkout(changelog: false, poll: true, scm: [
                        $class                           : "GitSCM",
                        branches                         : [
                                [name: "${branch}"],
                        ],
                        userRemoteConfigs                : [
                                [
                                        url          : REPO_URL,
                                        refspec      : specStr,
                                        credentialsId: "github-sre-bot-ssh",
                                ]
                        ],
                        extensions                       : [
                                [$class             : 'SubmoduleOption',
                                 disableSubmodules  : false,
                                 parentCredentials  : true,
                                 recursiveSubmodules: true,
                                 trackingSubmodules : false,
                                 reference          : ''],
                                [$class: 'PruneStaleBranch'],
                                [$class: 'CleanBeforeCheckout'],
                                [$class: 'LocalBranch']
                        ],
                        doGenerateSubmoduleConfigurations: false,
                ])
            }

            stage("Build") {
                container("rust") {
                    sh "NPROC=12 release-centos7/build/build-release.sh"
                    sh "ls release-centos7/build-release/"
                    sh "ls release-centos7/tiflash/"
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
properties([
        parameters([
                string(
                        defaultValue: '09e23b10f4687c8487d426815d18d436ec7d4f07',
                        name: 'TICS_COMMIT',
                        trim: true
                ),
                string(
                        defaultValue: 'master',
                        name: 'RELEASE_BRANCH',
                        trim: true
                ),
        ])
])

TIDB_BRANCH = RELEASE_BRANCH

pod_dockkerd_image = 'docker:18.09.6-dind'
// pod_docker_image ='hub.pingcap.net/zyguan/docker:build-essential-java'
pod_docker_image = 'hub.pingcap.net/jenkins/docker-arm64:build-essential'
pod_jnlp_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
pod_label = "tics-integration-test-arm64"
cloud = "kubernetes-arm64"


def doCheckout(commit, refspec) {
    checkout(changelog: false, poll: false, scm: [
        $class           : "GitSCM",
        branches         : [
                [name: "${commit}"],
        ],
        userRemoteConfigs: [
                [
                        url          : "git@github.com:pingcap/tics.git",
                        refspec      : refspec,
                        credentialsId: "github-sre-bot-ssh",
                ]
        ],
        extensions       : [
                [$class: 'PruneStaleBranch'],
                [$class: 'CleanBeforeCheckout'],
        ],
    ])
}

def checkoutTiCS(commit) {
    def refspec = "+refs/heads/*:refs/remotes/origin/*"
    try {
        doCheckout(commit, refspec)
    } catch (info) {
        retry(2) {
            echo "checkout failed, retry.."
            sleep 5
            if (sh(returnStatus: true, script: '[ -d .git ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                echo ".git already exist or not a valid git dir. Delete dir..."
                deleteDir()
            }
            doCheckout(commit, refspec)
        }
    }
}



def runClosure(label, Closure body) {
    podTemplate(name: label, 
                label: label, 
                instanceCap: 15, 
                cloud: cloud,
                containers: [
            containerTemplate(name: 'dockerd', image: pod_dockkerd_image, privileged: true,
                    resourceRequestCpu: '5000m', resourceRequestMemory: '10Gi',
                    resourceLimitCpu: '16000m', resourceLimitMemory: '32Gi'),
            containerTemplate(name: 'docker', image: pod_docker_image,
                    alwaysPullImage: true, envVars: [
                    envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
            ], ttyEnabled: true, command: 'cat'),
            containerTemplate(
                    name: 'jnlp', image: pod_jnlp_image, alwaysPullImage: false,
                    resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
            ),
    ]) {
        node(label) {
            body()
        }
    }
}

def runTest(label, testPath, TIDB_BRANCH) {
    runClosure(label) {
        stage("Unstash") {
            unstash 'git-code-tics'
            // dir("tics") {
            //     timeout(time: 5, unit: 'MINUTES') {
            //         container("docker") {
            //             sh """
            //             pwd
            //             DOWNLOAD_TAR=true COMMIT_HASH=${params.ghprbActualCommit} PULL_ID=${params.ghprbPullId} TAR_PATH=./tests/.build bash -e release-centos7/build/fetch-ci-build.sh
            //             """
            //         }
            //     }
            // }
        }
        def test_tics_image_tag = "arm64-${TICS_COMMIT}"
        def tics_image = "hub.pingcap.net/tiflash/tics:${test_tics_image_tag}"
        def dockerfile_url = "https://raw.githubusercontent.com/PingCAP-QE/ci/jenkins-pipelines/jenkins/Dockerfile/release/tiflash-arm64"

        def tics_binary_url = "http://fileserver.pingcap.net/download/builds/pingcap/test/tics/${TICS_COMMIT}/centos7/tics-linux-arm64.tar.gz"
        stage("Build tics image") {
            timeout(time: 5, unit: "MINUTES") {
                container("docker") {
                    sh """
                    mkdir tmp-tics-linux-arm64
                    cd tmp-tics-linux-arm64
                    curl ${tics_binary_url} | tar xz
                    cd ../
                    mv tmp-tics-linux-arm64/tiflash ./
                    curl ${dockerfile_url} -o Dockerfile
                    docker build  -t ${tics_image} -f Dockerfile .
                    """
                }
            }
        }

        dir(testPath) {
            stage("Test") {
                timeout(time: 60, unit: 'MINUTES') {
                    container("docker") {
                        try {
                            sh "pwd"
                            sh "TAG=${test_tics_image_tag} BRANCH=${TIDB_BRANCH} bash -xe ./run.sh"
                        } catch (e) {
                            archiveArtifacts(artifacts: "log/**/*.log", allowEmptyArchive: true)
                            sh "find log -name '*.log' | xargs tail -n 500"
                            sh "docker ps -a"
                            throw e
                        }
                    }
                }
            }
        }
    }
}


runClosure(pod_label) {
    def curws = pwd()
    dir("/home/jenkins/agent/code-archive") {
        container("golang") {
            if(fileExists("/nfs/cache/git/src-tics.tar.gz")){
                timeout(5) {
                    sh """
                    cp -R /nfs/cache/git/src-tics.tar.gz*  ./
                    mkdir -p ${curws}/tics
                    tar -xzf src-tics.tar.gz -C ${curws}/tics --strip-components=1
                    """
                }
            }
        }
        dir("${curws}/tics") {
            checkoutTiCS("${TICS_COMMIT}")
        }
        // timeout(time: 60, unit: 'MINUTES') {
        //     container("golang") {
        //         sh  """
        //         COMMIT_HASH=${params.ghprbActualCommit} PULL_ID=${params.ghprbPullId} TAR_PATH=${curws}/tics/tests/.build bash -e ${curws}/tics/release-centos7/build/fetch-ci-build.sh
        //         """
        //     }
        // }
    }
    stash includes: "tics/**", name: "git-code-tics", useDefaultExcludes: false
}

parallel (
    "tidb ci test": {
        def label = "tidb-ci-test"
        runTest(label, "tics/tests/tidb-ci", TIDB_BRANCH)
    },
    // "gtest": {
    //     def label = "gtest"
    //     runTest(label, "tics/tests/gtest", TIDB_BRANCH)
    // },
    // "delta merge test": {
    //     def label = "delta-merge-test"
    //     runTest(label, "tics/tests/delta-merge-test", TIDB_BRANCH)
    // },
    // "fullstack test": {
    //     def label = "fullstack-test"
    //     runTest(label, "tics/tests/fullstack-test", TIDB_BRANCH)
    // },
    // "fullstack test2": {
    //     def label = "fullstack-test2"
    //     runTest(label, "tics/tests/fullstack-test2", TIDB_BRANCH)
    // },
    // "mutable test": {
    //     def label = "mutable-test"
    //     runTest(label, "tics/tests/mutable-test", TIDB_BRANCH)
    // },
)

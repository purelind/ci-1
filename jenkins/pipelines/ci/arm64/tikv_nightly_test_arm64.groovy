
specStr = "+refs/heads/*:refs/remotes/origin/*"
REPO_URL = 'git@github.com:tikv/tikv.git'
githash = ""

K8S_NAMESPACE = "jenkins-tidb"

branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"


def run_with_pod(arch, os, Closure body) {
    def label = ""
    def cloud = "kubernetes"
    def pod_rust_docker_image = ""
    def jnlp_docker_image = ""

    if (arch == "x86") {
        label = "tikv-nightly-test"
        pod_rust_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13_rust:latest"
        jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    }
    if (arch == "arm64") {
        label = "tikv-nightly-test-arm64"
        pod_rust_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13_rust-arm64:latest"
        jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
        cloud = "kubernetes-arm64"
    }
    podTemplate(label: label,
            cloud: cloud,
            namespace: 'jenkins-tidb',
            containers: [
                    containerTemplate(
                            name: 'rust', alwaysPullImage: true,
                            image: "${pod_rust_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '4000m', resourceRequestMemory: '25Gi',
                            command: '/bin/sh -c', args: 'cat',
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
            volumes:[emptyDirVolume(mountPath: '/tmp', memory: true),
                     emptyDirVolume(mountPath: '/home/jenkins', memory: true)],
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

        stage("Checkout") {
            // update cache
            dir("go/src/github.com/pingcap/tikv") {
                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                    deleteDir()
                }
                checkout changelog: false, poll: false,
                        scm: [$class                           : 'GitSCM',
                              branches                         : [[name: branch]],
                              doGenerateSubmoduleConfigurations: false,
                              extensions                       : [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']],
                              submoduleCfg                     : [],
                              userRemoteConfigs                : [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: REPO_URL]]]

                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                println githash
            }
        }

        stage("Build") {
            dir("go/src/github.com/pingcap/tikv") {
                container("rust") {
                    sh """
                            rm ~/.gitconfig || true
                            rm -rf bin/*
                            rm -rf /home/jenkins/.target/*
                            grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                            if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                                echo using gcc 8
                                source /opt/rh/devtoolset-8/enable
                            fi
                            CARGO_TARGET_DIR=/home/jenkins/.target ROCKSDB_SYS_STATIC=1 make dist_release                            
                            ./bin/tikv-server --version
                        """
                }
            }
        }

        stage("Test") {
            dir("go/src/github.com/pingcap/tikv") {
                container("rust") {
                    sh """
                    grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                    if [[ ! "0.8.0" > "\\$grpcio_ver" ]]; then
                        echo using gcc 8
                        source /opt/rh/devtoolset-8/enable
                    fi
                    make dev
                    """
                }
            }
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
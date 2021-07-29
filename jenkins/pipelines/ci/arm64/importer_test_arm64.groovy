properties([
        parameters([
                string(
                        defaultValue: 'f02f7e35e3cac153d7144d0214d6d452951844fc',
                        name: 'IMPORTER_COMMIT',
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
    IMPORTER_COMMIT = params.getOrDefault("release_test__importer_commit", "")
}

refSpec = "+refs/heads/*:refs/remotes/origin/*"

K8S_NAMESPACE = "jenkins-tidb"
BRANCH_NEED_GO1160 = ["master", "release-5.1"]

ARCH = "x86" // [ x86 | arm64 ]
OS = "linux" // [ centos7 | kylin_v10 | darwin ]

def run_with_pod(arch, os, Closure body) {
    def label = ""
    def cloud = "kubernetes"
    def pod_go_docker_image = ""
    def pod_rust_docker_image = ""
    def jnlp_docker_image = ""
    if (arch == "x86") {
        label = "importer-test"
        pod_go_docker_image = "hub.pingcap.net/pingcap/centos7_golang-1.16:latest"
        pod_rust_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13_rust:latest"
        jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    }
    if (arch == "arm64") {
        label = "importer-test-arm64"
        pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
        pod_rust_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13_rust-arm64:latest"
        jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
        cloud = "kubernetes-arm64"
    }

    podTemplate(label: label,
            cloud: cloud,
            namespace: 'jenkins-tidb',
            containers: [
                    containerTemplate(
                            name: 'rust', alwaysPullImage: false,
                            image: "${pod_rust_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                            resourceLimitCpu: '30000m', resourceLimitMemory: "20Gi",
                            command: '/bin/sh -c', args: 'cat',
                    ),
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            resourceLimitCpu: '30000m', resourceLimitMemory: "20Gi",
                            command: '/bin/sh -c', args: 'cat',
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
        def ws = pwd()

        container("rust") {
            dir("${ws}/go/src/github.com/pingcap/importer") {
                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                    deleteDir()
                }
                try {
                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: refSpec, url: 'git@github.com:tikv/importer.git']]]
                } catch (info) {
                    retry(2) {
                        echo "checkout failed, retry.."
                        sleep 5
                        if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: refSpec, url: 'git@github.com:tikv/importer.git']]]
                    }
                }
                sh "git checkout -f ${IMPORTER_COMMIT}"
            }

            stash includes: "go/src/github.com/pingcap/importer/**", name: "importer-${os}-${arch}", useDefaultExcludes: false
        }
    }

}

def run_test(arch, os) {
    run_with_pod(arch, os) {
        container("rust") {
            def ws = pwd()
            unstash "importer-${os}-${arch}"

            dir("${ws}/go/src/github.com/pingcap/importer") {
                timeout(30) {
                    if (arch == "x86") {
                        sh """
                        uname -a
                        echo using gcc 8
                        source /opt/rh/devtoolset-8/enable
                        make test 
                        """
                    }
                    if (arch == "arm64") {
                        sh """
                        uname -a
                        echo using gcc 8
                        source /opt/rh/devtoolset-8/enable
                        ROCKSDB_SYS_SSE=0 make test
                        """
                    }
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


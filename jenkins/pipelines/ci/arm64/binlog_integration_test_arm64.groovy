properties([
        parameters([
                string(
                        defaultValue: '8d1aa3c53a9c783d39b7b9618d9dee2f5450c76e',
                        name: 'BINLOG_COMMIT',
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
    BINLOG_COMMIT = params.getOrDefault("release_test__binlog_commit", "")
}

def specStr = "+refs/heads/*:refs/remotes/origin/*"
TIKV_BRANCH = RELEASE_BRANCH
PD_BRANCH = RELEASE_BRANCH
TIDB_BRANCH = RELEASE_BRANCH
TIDB_TOOLS_BRANCH = RELEASE_BRANCH


//if (params.containsKey("release_test") && params.triggered_by_upstream_ci == null) {
//    TIKV_BRANCH = params.release_test__tikv_commit
//    PD_BRANCH = params.release_test__pd_commit
//    TIDB_BRANCH = params.release_test_tidb_commit
//}

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
    def jnlp_docker_image = ""
    def pod_zookeeper_docker_image = ""
    def pod_kafka_docker_image = ""
    if (is_need_go1160) {
        if (arch == "x86") {
            label = "binlog-integration-test"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
            pod_zookeeper_docker_image = "wurstmeister/zookeeper:latest"
            pod_kafka_docker_image = "wurstmeister/kafka:latest"
        }
        if (arch == "arm64") {
            label = "binlog-integration-test-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            // TODO these two image-arm64 not exist now
            pod_zookeeper_docker_image = "zookeeper:3.4.13"
            pod_kafka_docker_image = "hub.pingcap.net/jenkins/kafka-arm64:latest"
            cloud = "kubernetes-arm64"
        }
    } else {
        if (arch == "x86") {
            label = "binlog-integration-test"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
            pod_zookeeper_docker_image = "wurstmeister/zookeeper:latest"
            pod_kafka_docker_image = "wurstmeister/kafka:latest"
        }
        if (arch == "arm64") {
            label = "binlog-integration-test-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13-arm64:latest"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            // TODO these two image-arm64 not exist now
            pod_zookeeper_docker_image = "zookeeper:3.4.13"
            pod_kafka_docker_image = "hub.pingcap.net/jenkins/kafka-arm64:latest"
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
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
                    containerTemplate(name: 'zookeeper',alwaysPullImage: false, image: "${pod_zookeeper_docker_image}",
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            ttyEnabled: true),
                    containerTemplate(
                            name: 'kafka',
                            image: "${pod_kafka_docker_image}",
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            ttyEnabled: true,
                            alwaysPullImage: false,
                            envVars: [
                                    envVar(key: 'KAFKA_MESSAGE_MAX_BYTES', value: '1073741824'),
                                    envVar(key: 'KAFKA_REPLICA_FETCH_MAX_BYTES', value: '1073741824'),
                                    envVar(key: 'KAFKA_ADVERTISED_PORT', value: '9092'),
                                    envVar(key: 'KAFKA_ADVERTISED_HOST_NAME', value:'127.0.0.1'),
                                    envVar(key: 'KAFKA_BROKER_ID', value: '1'),
                                    envVar(key: 'ZK', value: 'zk'),
                                    envVar(key: 'KAFKA_ZOOKEEPER_CONNECT', value: 'localhost:2181'),
                            ]
                    )
            ],
            volumes:[
            emptyDirVolume(mountPath: '/tmp', memory: true),
            emptyDirVolume(mountPath: '/home/jenkins', memory: true)]
    ) {
        node(label) {
            println "debug arm64 command:\nkubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
            body()
        }
    }

}

def run_build(arch, os) {
    run_with_pod(arch, os) {
        container("golang") {
            def ws = pwd()
            deleteDir()
            dir("${ws}/go/src/github.com/pingcap/tidb-binlog") {
                container("golang") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    try {
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:pingcap/tidb-binlog.git']]]
                    } catch (error) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 5
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:pingcap/tidb-binlog.git']]]
                        }
                    }
                    sh """
                    git checkout -f ${BINLOG_COMMIT}
                    make build
                    ls -l ./bin && mv ./bin ${ws}/bin
                    """

                }
            }

            stash includes: "go/src/github.com/pingcap/tidb-binlog/**", name: "tidb-binlog-${os}-${arch}", useDefaultExcludes: false

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
                tikv_file = "${FILE_SERVER_URL}/download/builds/pingcap/test/tikv/${tikv_sha1}/centos7/tikv-linux-arm64.tar.gz"
                pd_file = "${FILE_SERVER_URL}/download/builds/pingcap/test/pd/${pd_sha1}/centos7/pd-linux-arm64.tar.gz"
                tidb_file = "${FILE_SERVER_URL}/download/builds/pingcap/test/tidb/${tidb_sha1}/centos7/tidb-linux-arm64.tar.gz"
            }

            // download binary file tikv / pd / tidb
            sh """
            curl ${tikv_file} | tar xz
            curl ${pd_file} | tar xz
            curl ${tidb_file} | tar xz
            pwd && ls -l ./bin
            """

            // binlogctl && sync_diff_inspector
            dir("go/src/github.com/pingcap/tidb-tools") {
                def tidb_tools_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb-tools/${TIDB_TOOLS_BRANCH}/sha1").trim()
                def tidb_tools_file = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/${tidb_tools_sha1}/centos7/tidb-tools.tar.gz"
                if (arch == "arm64") {
                    tidb_tools_file = "${FILE_SERVER_URL}/download/builds/pingcap/test/tidb-tools/${tidb_tools_sha1}/centos7/tidb-tools-linux-arm64.tar.gz"
                }

                if (os == "centos7-tidb-tools-v2.1.6") {
                    tidb_tools_file = "https://download.pingcap.org/tidb-tools-v2.1.6-linux-amd64.tar.gz"
                    sh """
                    curl ${tidb_tools_file} | tar xz
                    mkdir -p ./bin
                    mv tidb-tools-v2.1.6-linux-amd64/bin/sync_diff_inspector bin/
                    rm -r tidb-tools-v2.1.6-linux-amd64 || true
                    mv ${ws}/bin/* ./bin/
                    ls -l ./bin
                    """
                } else {
                    sh """
                    curl ${tidb_tools_file} | tar xz
                    ls -l ./bin
                    rm -f bin/{ddl_checker,importer}
                    mv ${ws}/bin/* ./bin/
                    ls -l ./bin
                    """
                }
            }

            stash includes: "go/src/github.com/pingcap/tidb-tools/bin/**", name: "binaries-${os}-${arch}"
        }
    }
}

def run_test(arch, os) {
    run_with_pod(arch, os) {
        container("golang") {
            def ws = pwd()
            deleteDir()
            unstash "tidb-binlog-${os}-${arch}"
            unstash "binaries-${os}-${arch}"

            dir("go/src/github.com/pingcap/tidb-binlog") {
                sh """
                ls -l ${ws}/go/src/github.com/pingcap/tidb-tools/bin
                mv ${ws}/go/src/github.com/pingcap/tidb-tools/bin ./bin
                ls -l ./bin
                """
                try {
                    sh """
                    hostname
                    docker ps || true
                    KAFKA_ADDRS=127.0.0.1:9092 GOPATH=\$GOPATH:${ws}/go make integration_test
                    """
                } catch (Exception e) {
                    sh "cat '/tmp/tidb_binlog_test/pd.log' || true"
                    sh "cat '/tmp/tidb_binlog_test/tikv.log' || true"
                    sh "cat '/tmp/tidb_binlog_test/tidb.log' || true"
                    sh "cat '/tmp/tidb_binlog_test/drainer.log' || true"
                    sh "cat '/tmp/tidb_binlog_test/pump_8250.log' || true"
                    sh "cat '/tmp/tidb_binlog_test/pump_8251.log' || true"
                    sh "cat '/tmp/tidb_binlog_test/reparo.log' || true"
                    sh "cat '/tmp/tidb_binlog_test/binlog.out' || true"
                    sh "cat '/tmp/tidb_binlog_test/kafka.out' || true"
                    throw e;
                } finally {
                    sh """
                    echo success
                    """
                }
            }
        }
    }
}


// Start main
try {

    parallel(
            "x86": {
                stage("build") {
                    run_build("x86", "centos7")
                }
                stage("test") {
                    run_test("x86", "centos7")
                }
            },
            "x86-old-tidb-tools-v2.1.6": {
                stage("build") {
                    run_build("x86", "centos7-tidb-tools-v2.1.6")
                }
                stage("test") {
                    run_test("x86", "centos7-tidb-tools-v2.1.6")
                }
            },
            "arm64": {
                stage("build") {
                    run_build("arm64", "centos7")
                }
                stage("test") {
                    run_test("arm64", "centos7")
                }
            }


    )
    currentBuild.result = "SUCCESS"
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

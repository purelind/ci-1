properties([
        parameters([
                string(
                        defaultValue: '591ebdd9263694d88d6efc365dba14db9e8c7439',
                        name: 'TIDB_COMMIT',
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
    TIDB_COMMIT = params.getOrDefault("release_test__tidb_commit", "")
}

TIDB_TEST_BRANCH = "master"
MYBATIS3_URL = "${FILE_SERVER_URL}/download/static/travis-tidb.zip"

K8S_NAMESPACE = "jenkins-tidb"

def run_with_pod(arch, os, Closure body) {
    def label = ""
    def cloud = "kubernetes"
    def pod_go_docker_image = ""
    def jnlp_docker_image = ""
    def pod_java_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13_java:cached"

    if (arch == "x86") {
        label = "tidb-integration-mybatis"
        pod_go_docker_image = "hub.pingcap.net/pingcap/centos7_golang-1.16:latest"
        jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    }
    if (arch == "arm64") {
        label = "tidb-integration-mybatis-arm64"
        pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
        jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
        cloud = "kubernetes-arm64"
        pod_java_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13_java-arm64:latest"
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
                            name: 'java', alwaysPullImage: true,
                            image: "${pod_java_docker_image}", ttyEnabled: true,
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

def run_test(arch, os) {
    stage("${arch}  Mybatis Test") {
        run_with_pod(arch, os) {
            container("java") {
                def ws = pwd()
                def url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
                if (arch == "arm64") {
                    url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/tidb-linux-arm64.tar.gz"
                }
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(10) {
                        sh """
                        set +e
                        killall -9 -r tidb-server
                        killall -9 -r tikv-server
                        killall -9 -r pd-server
                        rm -rf /tmp/tidb
                        set -e

                        curl ${url} | tar xz
                        rm -f bin/tidb-server
                        rm -f bin/tidb-server-race
                        cp bin/tidb-server-check bin/tidb-server
                        cat > config.toml << __EOF__
[performance]
join-concurrency = 1
__EOF__

                        bin/tidb-server -config config.toml > ${ws}/tidb_mybatis3_test.log 2>&1 &
                        
                        """
                    }
                    if (!RELEASE_BRANCH.startsWith("release-2")) {
                        retry(3) {
                            sh """
                                sleep 5
                                wget ${FILE_SERVER_URL}/download/mysql && chmod +x mysql
                                mysql -h 127.0.0.1 -P4000 -uroot -e 'set @@global.tidb_enable_window_function = 0'
                            """
                        }
                    }
                }

                try {
                    dir("mybatis3") {
                        timeout(10) {
                            sh """
                            curl -L ${MYBATIS3_URL} -o travis-tidb.zip && unzip travis-tidb.zip && rm -rf travis-tidb.zip
                            cp -R mybatis-3-travis-tidb/. ./ && rm -rf mybatis-3-travis-tidb
                            mvn -B clean test
                            """
                        }
                    }
                } catch (err) {
                    sh "cat ${ws}/tidb_mybatis3_test.log"
                    throw err
                } finally {
                    sh "killall -9 -r tidb-server || true"
                }
            }
        }
    }
}


// Start main
try {
    stage("x86") {
        stage("x86 test") {
            run_test("x86", "centos7")
        }
    }
    stage("arm64") {
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

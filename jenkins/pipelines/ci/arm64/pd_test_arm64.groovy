properties([
        parameters([
                string(
                        defaultValue: '9e157278c7464237783bf11adcedcd08d7f0d580',
                        name: 'PD_COMMIT',
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
    PD_COMMIT = params.getOrDefault("release_test__pd_commit", "")
}

PD_BRANCH = RELEASE_BRANCH


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
    if (is_need_go1160) {
        if (arch == "x86") {
            label = "pd-test"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "pd-test-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            cloud = "kubernetes-arm64"
        }
    } else {
        if (arch == "x86") {
            label = "pd-test"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "pd-test-arm64"
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
        deleteDir()
        dir("go/src/github.com/pingcap/pd") {
            container("golang") {
                def pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/pr/${PD_COMMIT}/centos7/pd-server.tar.gz"
                if (arch == "arm64") {
                    pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${PD_COMMIT}/centos7/pd-linux-arm64.tar.gz"
                }

                timeout(30) {
                    sh """
                        pwd
                        while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                        sleep 5
                        curl ${pd_url} | tar xz
                        rm -rf ./bin
                        export GOPATH=${ws}/go
                        # export GOPROXY=http://goproxy.pingcap.net
                        go list ./...
                        go list ./... > packages.list
                        cat packages.list
                        split packages.list -n r/5 packages_unit_ -a 1 --numeric-suffixes=1
                        echo 1
                        cat packages_unit_1
                        """

                    if (PD_BRANCH == "release-3.0" || PD_BRANCH == "release-3.1") {
                        sh """
                            make retool-setup
                            make failpoint-enable
                            """
                    } else {
                        sh """
                            make failpoint-enable
                            make deadlock-enable
    						"""
                    }
                }
            }
        }

        stash includes: "go/src/github.com/pingcap/pd/**", name: "pd-${os}-${arch}"
    }
}

def run_test(arch, os) {
    def run_unit_test = { chunk_suffix ->
        run_with_pod(arch, os) {
            def ws = pwd()
            deleteDir()
            unstash "pd-${os}-${arch}"

            dir("go/src/github.com/pingcap/pd") {
                container("golang") {
                    timeout(30) {
                        sh """
                               set +e
                               killall -9 -r tidb-server
                               killall -9 -r tikv-server
                               killall -9 -r pd-server
                               rm -rf /tmp/pd
                               set -e
                               cat packages_unit_${chunk_suffix}
                            """
                        if (fileExists("go.mod")) {
                            sh """
                               mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sfT \$GOPATH/pkg/mod ${ws}/go/pkg/mod || true
                               GOPATH=${ws}/go CGO_ENABLED=1 GO111MODULE=on go test -p 1 -race -cover \$(cat packages_unit_${chunk_suffix})
                               """
                        } else {
                            sh """
                               GOPATH=${ws}/go CGO_ENABLED=1 GO111MODULE=off go test -race -cover \$(cat packages_unit_${chunk_suffix})
                               """
                        }
                    }
                }
            }
        }
    }
    def tests = [:]

    tests["Unit Test Chunk #1"] = {
        run_unit_test(1)
    }

    tests["Unit Test Chunk #2"] = {
        run_unit_test(2)
    }

    tests["Unit Test Chunk #3"] = {
        run_unit_test(3)
    }

    tests["Unit Test Chunk #4"] = {
        run_unit_test(4)
    }

    tests["Unit Test Chunk #5"] = {
        run_unit_test(5)
    }

    parallel tests
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

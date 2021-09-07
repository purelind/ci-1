properties([
        parameters([
                string(
                        defaultValue: '4116dd248a1533c83eebd4eaacc690f4ebb63ff8',
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


TIDB_BRANCH = RELEASE_BRANCH

K8S_NAMESPACE = "jenkins-tidb"
BRANCH_NEED_GO1160 = ["master", "release-5.1"]

runExplainTest = true
goTestEnv = "CGO_ENABLED=1"
waitBuildDone = 0

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
            label = "tidb-unit-test"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-unit-test-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            cloud = "kubernetes-arm64"
        }
    } else {
        if (arch == "x86") {
            label = "tidb-unit-test"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-unit-test-arm64"
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
        def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
        def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/done"
        if (arch == "arm64") {
            tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/tidb-linux-arm64.tar.gz"
        }
        container("golang") {
            dir("go/src/github.com/pingcap/tidb") {
                timeout(10) {
                    retry(3){
                        deleteDir()
                        sh """
                    while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                    curl ${tidb_url} | tar xz
                    """
                        timeout(5) {
                            sh """
                            rm -rf ./bin
                            go list ./... | grep -v cmd/ddltest > packages.list
                            package_base=`grep module go.mod | head -n 1 | awk '{print \$2}'`
                            cat packages.list | grep -v "\${package_base}/ddl"|grep -v "\${package_base}/executor" |grep -v "\${package_base}/session" | grep -v "\${package_base}/expression" | grep -vE "\${package_base}/store\$" > packages.list.short
                            echo "\${package_base}/ddl" > packages_race_7
                            cat packages.list | grep "\${package_base}/ddl/" > packages_race_13
                            cat packages.list | grep -E "\${package_base}/store\$" >> packages_race_13

                            echo  "\${package_base}/session"  > packages_race_8
                            grep "\${package_base}/sessionctx" packages.list >>  packages.list.short

                            echo "\${package_base}/executor" > packages_race_9
                            cat packages.list | grep "\${package_base}/executor/" > packages_race_10 || cat packages_race_9 > packages_race_10
                            cat packages.list | grep -v "\${package_base}/executor" > packages.list.short2

                            grep  "\${package_base}/expression/" packages.list >> packages.list.short
                            echo  "\${package_base}/expression" > packages_race_12
                            grep "\${package_base}/planner/core" packages.list.short > packages_race_6
                            grep "\${package_base}/store/tikv" packages.list.short > packages_race_5 | true #store/tikv is removed from master
                            grep "\${package_base}/server" packages.list.short > packages_race_4

                            cat packages.list.short | grep -v "\${package_base}/planner/core" | grep -v "\${package_base}/store/tikv" | grep -v "\${package_base}/server" > packages.list.short.1
                            mv packages.list.short.1 packages.list.short

                            cat packages.list | grep -v "\${package_base}/planner/core" | grep -v "\${package_base}/server" | grep -v "\${package_base}/ddl" | grep -v "\${package_base}/executor" > packages.list.unit.leak

                            split packages.list.unit.leak -n r/3 packages_unit_ -a 1 --numeric-suffixes=1
                            cat packages.list | grep "\${package_base}/ddl" > packages_unit_4
                            echo "\${package_base}/executor" > packages_unit_5
                            cat packages.list | grep "\${package_base}/planner/core" > packages_unit_6
                            cat packages.list | grep "\${package_base}/server" > packages_unit_7
                            cat packages.list | grep "\${package_base}/executor/" > packages_unit_8

                            split packages.list.unit.leak -n r/3 packages_leak_ -a 1 --numeric-suffixes=1
                            cat packages.list | grep "\${package_base}/ddl" > packages_leak_4
                            echo "\${package_base}/executor" > packages_leak_5
                            cat packages.list | grep "\${package_base}/planner/core" > packages_leak_6
                            cat packages.list | grep "\${package_base}/server" > packages_leak_7
                            cat packages.list | grep "\${package_base}/executor/" > packages_leak_8

                            split packages.list.short -n r/3 packages_race_ -a 1 --numeric-suffixes=1

                            # failpoint-ctl => 3.0+
                            # gofail => 2.0, 2.1
                            set +e
                            grep "tools/bin/failpoint-ctl" Makefile
                            if [ \$? -lt 1 ]; then
                                failpoint_bin=tools/bin/failpoint-ctl
                            else
                                failpoint_bin=tools/bin/gofail
                            fi
                            set -e
                            echo "failpoint bin: \$failpoint_bin"
                            make \$failpoint_bin
                            find . -type d | grep -vE "(\\.git|_tools)" | xargs \$failpoint_bin enable
                            """
                        }
                    }
                }
            }
            stash includes: "go/src/github.com/pingcap/tidb/**", name: "tidb-${os}-${arch}"
            if (fileExists("go/src/github.com/pingcap/tidb/go.mod")) {
                goTestEnv = goTestEnv + " GO111MODULE=on"
            }
        }
    }
}

def run_test(arch, os) {
    def run_unit_test = { chunk_suffix ->
        run_with_pod(arch, os) {
            def ws = pwd()
            unstash "tidb-${os}-${arch}"

            dir("go/src/github.com/pingcap/tidb") {
                container("golang") {
                    try{
                        timeout(10) {
                            sh """
                                set +e
                                killall -9 -r -q tidb-server
                                killall -9 -r -q tikv-server
                                killall -9 -r -q pd-server
                                rm -rf /tmp/tidb
                                set -e
                                set -o pipefail
                                export log_level=info 
                                if [ -s packages_race_${chunk_suffix} ]; then time ${goTestEnv} go test -timeout 10m -v -p 5 -ldflags '-X "github.com/pingcap/tidb/config.checkBeforeDropLDFlag=1"' -cover \$(cat packages_unit_${chunk_suffix}); fi | tee test.log ||\\
                                (cat test.log | grep -Ev "^\\[[[:digit:]]{4}(/[[:digit:]]{2}){2}" | grep -A 30 "\\-------" | grep -A 29 "^FAIL:"; false)
                                """
                        }
                    }catch (err) {
                        throw err
                    }
                }
            }
        }
    }

    def run_race_test = { chunk_suffix ->
        run_with_pod(arch, os) {
            def ws = pwd()
            unstash "tidb-${os}-${arch}"

            dir("go/src/github.com/pingcap/tidb") {
                container("golang") {
                    try{
                        timeout(20) {
                            sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                set -o pipefail
                                export log_level=info
                                if [ -s packages_race_${chunk_suffix} ]; then time ${goTestEnv} go test -v -vet=off -p 5 -timeout 20m -race \$(cat packages_race_${chunk_suffix}); fi | tee test.log || \\
                                (cat test.log | grep -Ev "^\\[[[:digit:]]{4}(/[[:digit:]]{2}){2}" | grep -A 30 "\\-------" | grep -A 29 "^FAIL:"; false)
                                """
                        }
                    }catch (err) {
                        throw err
                    }
                }
            }
        }
    }


    def run_race_test_heavy_with_args = { chunk_suffix, extraArgs ->
        run_with_pod(arch, os) {
            def ws = pwd()
            unstash "tidb-${os}-${arch}"

            dir("go/src/github.com/pingcap/tidb") {
                container("golang") {
                    try {
                        timeout(20) {
                            sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                set -o pipefail
                                export log_level=info
                                if [ -s packages_race_${chunk_suffix} ]; then time GORACE="history_size=7" ${goTestEnv} go test -v -vet=off -p 5 -timeout 20m -race \$(cat packages_race_${chunk_suffix}) ${extraArgs}; fi  | tee test.log || \\
                                (cat test.log | grep -Ev "^\\[[[:digit:]]{4}(/[[:digit:]]{2}){2}" | grep -A 30 "\\-------" | grep -A 29 "^FAIL:"; false)
                                """
                        }
                    }catch (err) {
                        throw err
                    }
                }
            }
        }
    }

    def run_race_test_heavy = { chunk_suffix, extraArgs ->
        run_race_test_heavy_with_args(chunk_suffix, "")
    }

    def run_race_test_heavy_parallel = { chunk_suffix ->
        run_race_test_heavy_with_args(chunk_suffix, "-check.p")
    }

    def run_leak_test = { chunk_suffix ->
        run_with_pod(arch, os) {
            def ws = pwd()
            unstash "tidb-${os}-${arch}"

            dir("go/src/github.com/pingcap/tidb") {
                container("golang") {
                    try{
                        timeout(20) {
                            sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                set -o pipefail
                                export log_level=info 
                                if [ -s packages_race_${chunk_suffix} ]; then time ${goTestEnv} CGO_ENABLED=1 go test -v -p 5 -tags leak \$(cat packages_leak_${chunk_suffix}); fi | tee test.log || \\
                                (cat test.log | grep -Ev "^\\[[[:digit:]]{4}(/[[:digit:]]{2}){2}" | grep -A 30 "\\-------" | grep -A 29 "^FAIL:"; false)
                                """
                        }
                    }catch (err) {
                        throw err
                    }
                }
            }
        }
    }

    // 将执行较慢的 chunk 放在前面优先调度，以减轻调度的延迟对执行时间的影响
    def tests = [:]

    def suites = """testSuite1|testSuite|testSuite7|testSuite5|testSuiteAgg|
                            testSuiteJoin1|tiflashTestSuite|testFastAnalyze|testSuiteP2|testSuite2"""
    def cmd1 = String.format('-check.f "%s"', suites)
    def cmd2 = String.format('-check.exclude "%s"', suites)
    tests["Race Test Chunk #9 executor-part1"] = {
        run_race_test_heavy_with_args(9, cmd1)
    }
    tests["Race Test Chunk #9 executor-part2"] = {
        run_race_test_heavy_with_args(9, cmd2)
    }

    tests["Race Test Chunk #10"] = {
        run_race_test(10)
    }
    // run race #8 in parallel mode for master branch\
    if (TIDB_BRANCH == "master") {
        tests["Race Test Chunk #7 ddl-DBSuite|SerialDBSuite"] = {
            run_race_test_heavy_with_args(7, '-check.f "testDBSuite|testSerialDBSuite"')
        }

        tests["Race Test Chunk #7 ddl-other suite"] = {
            run_race_test_heavy_with_args(7, '-check.exclude "testDBSuite|testSerialDBSuite"')
        }

        tests["Race Test Chunk #6 planner/core-testIntegrationSerialSuite"] = {
            run_race_test_heavy_with_args(6, '-check.f "testIntegrationSerialSuite"')
        }
        tests["Race Test Chunk #6 planner/core-testIntegrationSuite"] = {
            run_race_test_heavy_with_args(6, '-check.f "testIntegrationSuite"')
        }
        tests["Race Test Chunk #6 planner/core-testPlanSuite"] = {
            run_race_test_heavy_with_args(6, '-check.f "testPlanSuite"')
        }
        tests["Race Test Chunk #6 planner/core-other suite"] = {
            run_race_test_heavy_with_args(6, '-check.exclude "testPlanSuite|testIntegrationSuite|testIntegrationSerialSuite"')
        }
        tests["Race Test Chunk #8 session"] = {
            run_race_test_heavy_parallel(8)
        }

    } else {
        tests["Race Test Chunk #7 ddl"] = {
            run_race_test_heavy(7)
        }

        tests["Race Test Chunk #6 planner/core"] = {
            run_race_test_heavy(6)
        }

        tests["Race Test Chunk #8 session"] = {
            run_race_test_heavy(8)
        }
    }

    tests["Race Test Chunk #12 expression"] = {
        run_race_test(12)
    }

    tests["Race Test Chunk #1"] = {
        run_race_test(1)
    }

    tests["Race Test Chunk #2"] = {
        run_race_test(2)
    }

    tests["Race Test Chunk #3"] = {
        run_race_test(3)
    }

    tests["Race Test Chunk #4"] = {
        run_race_test(4)
    }

    tests["Race Test Chunk #5"] = {
        run_race_test(5)
    }

    tests["Race Test Chunk #13"] = {
        run_race_test(13)
    }


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

    tests["Unit Test Chunk #6"] = {
        run_unit_test(6)
    }

    tests["Unit Test Chunk #7"] = {
        run_unit_test(7)
    }

    tests["Unit Test Chunk #8"] = {
        run_unit_test(8)
    }

    tests["Leak Test Chunk #1"] = {
        run_leak_test(1)
    }

    tests["Leak Test Chunk #2"] = {
        run_leak_test(2)
    }

    tests["Leak Test Chunk #3"] = {
        run_leak_test(3)
    }

    tests["Leak Test Chunk #4"] = {
        run_leak_test(4)
    }

    tests["Leak Test Chunk #5"] = {
        run_leak_test(5)
    }

    tests["Leak Test Chunk #6"] = {
        run_leak_test(6)
    }

    tests["Leak Test Chunk #7"] = {
        run_leak_test(7)
    }

    tests["Leak Test Chunk #8"] = {
        run_leak_test(8)
    }

    parallel tests
}

// Start main
try {
    stage("x86") {
        stage("x86 build") {
            run_build("x86", "centos7")
        }
        stage("x86 test") {
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


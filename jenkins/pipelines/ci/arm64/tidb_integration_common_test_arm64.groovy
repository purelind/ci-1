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

TIKV_BRANCH = RELEASE_BRANCH
PD_BRANCH = RELEASE_BRANCH
TIDB_TEST_BRANCH = RELEASE_BRANCH
TIDB_BRANCH = RELEASE_BRANCH

if (params.containsKey("release_test") && params.triggered_by_upstream_ci == null) {
    TIKV_BRANCH = params.release_test__tikv_commit
    PD_BRANCH = params.release_test__pd_commit
}

K8S_NAMESPACE = "jenkins-tidb"
BRANCH_NEED_GO1160 = ["master", "release-5.1"]

ARCH = "x86" // [ x86 | arm64 ]
OS = "linux" // [ centos7 | kylin_v10 | darwin ]

tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/done"

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
            label = "tidb-integration-common"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-integration-common-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            cloud = "kubernetes-arm64"
        }
    } else {
        if (arch == "x86") {
            label = "tidb-integration-common"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-integration-common-arm64"
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
        container("golang") {
            def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
            def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/done"
            def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
            def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
            def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
            if (arch == "arm64") {
                tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/tidb-linux-arm64.tar.gz"
                tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test-linux-arm64.tar.gz"

            }
            dir("go/src/github.com/pingcap/tidb") {
                timeout(10) {
                    sh """
                    while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                    curl ${tidb_url} | tar xz
                    """
                }
            }
            dir("go/src/github.com/pingcap/tidb-test") {
                timeout(10) {
                    sh """
                    curl ${tidb_test_url} | tar xz

                    export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                    cd tidb_test && ./build.sh && cd ..
                    cd mysql_test && ./build.sh && cd ..
                    cd analyze_test && ./build.sh && cd ..
                    if [ \"${TIDB_COMMIT}\" != \"release-2.0\" ]; then
                        cd randgen-test && ./build.sh && cd ..
                        cd randgen-test && ls t > packages.list
                        split packages.list -n r/3 packages_ -a 1 --numeric-suffixes=1
                        cd ..
                    fi
                    """
                }
            }

            if (arch == "x86") {
                stash includes: "go/src/github.com/pingcap/tidb-test/_helper.sh", name: "helper"
                stash includes: "go/src/github.com/pingcap/tidb-test/tidb_test/**", name: "tidb_test"
                stash includes: "go/src/github.com/pingcap/tidb-test/randgen-test/**", name: "randgen-test"
                stash includes: "go/src/github.com/pingcap/tidb-test/go-sql-test/**", name: "go-sql-test"
                stash includes: "go/src/github.com/pingcap/tidb-test/go.*,go/src/github.com/pingcap/tidb-test/util/**,go/src/github.com/pingcap/tidb-test/bin/**", name: "tidb-test"
                stash includes: "go/src/github.com/pingcap/tidb-test/_vendor/**", name: "tidb-test-vendor"
                stash includes: "go/src/github.com/pingcap/tidb-test/mysql_test/**", name: "mysql_test"
                stash includes: "go/src/github.com/pingcap/tidb-test/analyze_test/**", name: "analyze_test"
                stash includes: "go/src/github.com/pingcap/tidb-test/gorm_test/**", name: "gorm_test"
            }
            if (arch == "arm64") {
                stash includes: "go/src/github.com/pingcap/tidb-test/_helper.sh", name: "helper_arm64"
                stash includes: "go/src/github.com/pingcap/tidb-test/tidb_test/**", name: "tidb_test_arm64"
                stash includes: "go/src/github.com/pingcap/tidb-test/randgen-test/**", name: "randgen-test_arm64"
                stash includes: "go/src/github.com/pingcap/tidb-test/go-sql-test/**", name: "go-sql-test_arm64"
                stash includes: "go/src/github.com/pingcap/tidb-test/go.*,go/src/github.com/pingcap/tidb-test/util/**,go/src/github.com/pingcap/tidb-test/bin/**", name: "tidb-test_arm64"
                stash includes: "go/src/github.com/pingcap/tidb-test/_vendor/**", name: "tidb-test-vendor_arm64"
                stash includes: "go/src/github.com/pingcap/tidb-test/mysql_test/**", name: "mysql_test_arm64"
                stash includes: "go/src/github.com/pingcap/tidb-test/analyze_test/**", name: "analyze_test_arm64"
                stash includes: "go/src/github.com/pingcap/tidb-test/gorm_test/**", name: "gorm_test_arm64"
            }
        }
    }
}

def run_test(arch, os) {
    stage("${arch}  Integration Common Test") {
        def tests = [:]

        def run = { test_dir, mytest, test_cmd ->
            run_with_pod(arch, os) {
                def ws = pwd()
                deleteDir()
                if ( arch == "x86" ) {
                    unstash "tidb-test"
                    unstash "tidb-test-vendor"
                    unstash "helper"
                    unstash "${test_dir}"
                }
                if (arch == "arm64") {
                    unstash "tidb-test_arm64"
                    unstash "tidb-test-vendor_arm64"
                    unstash "helper_arm64"
                    unstash "${test_dir}_arm64"
                }

                dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                    container("golang") {
                        def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
                        def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/done"
                        def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                        def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                        def tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
                        def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                        def pd_sha1= sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                        def pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                        if (arch == "arm64") {
                            tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/tidb-linux-arm64.tar.gz"
                            tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-linux-arm64.tar.gz"
                            pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-linux-arm64.tar.gz"
                        }

                        timeout(10) {
                            retry(3){
                                sh """
	                            while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 1; done
	                            curl ${tikv_url} | tar xz bin
	
	                            while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 1; done
	                            curl ${pd_url} | tar xz bin
	
	                            mkdir -p ./tidb-src
	                            while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
	                            curl ${tidb_url} | tar xz -C ./tidb-src
	                            ln -s \$(pwd)/tidb-src "${ws}/go/src/github.com/pingcap/tidb"
	
	                            mv tidb-src/bin/tidb-server ./bin/tidb-server
	                            """
                            }
                        }

                        try {
                            timeout(10) {
                                sh """
                                ps aux
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                rm -rf ./tikv ./pd
                                set -e
                                
                                bin/pd-server --name=pd --data-dir=pd &>pd_${mytest}.log &
                                sleep 10
                                echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                bin/tikv-server -C tikv_config.toml --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                                sleep 10
                                if [ -f test.sh ]; then awk 'NR==2 {print "set -x"} 1' test.sh > tmp && mv tmp test.sh && chmod +x test.sh; fi

                                export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                export log_level=debug
                                TIDB_SERVER_PATH=`pwd`/bin/tidb-server \
                                TIKV_PATH='127.0.0.1:2379' \
                                TIDB_TEST_STORE_NAME=tikv \
                                ${test_cmd}
                                """
                            }
                        } catch (err) {
                            sh"""
                            cat mysql-test.out || true
                            """
                            sh """
                            cat pd_${mytest}.log
                            cat tikv_${mytest}.log
                            cat tidb*.log
                            """
                            throw err
                        } finally {
                            sh """
                            set +e
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            set -e
                            """
                        }
                    }
                }
            }
        }

        def run_split = { test_dir, mytest, test_cmd, chunk ->
            run_with_pod(arch, os) {
                def ws = pwd()

                if ( arch == "x86" ) {
                    unstash "tidb-test"
                    unstash "tidb-test-vendor"
                    unstash "helper"
                    unstash "${test_dir}"
                }
                if (arch == "arm64") {
                    unstash "tidb-test_arm64"
                    unstash "tidb-test-vendor_arm64"
                    unstash "helper_arm64"
                    unstash "${test_dir}_arm64"
                }

                dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                    def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
                    def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/done"
                    def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                    def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                    def tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
                    def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                    def pd_sha1= sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                    def pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                    if (arch == "arm64") {
                        tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/tidb-linux-arm64.tar.gz"
                        tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-linux-arm64.tar.gz"
                        pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-linux-arm64.tar.gz"
                    }
                    container("golang") {
                        timeout(10) {
                            retry(3){
                                sh """
	                            while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
	                            curl ${tikv_url} | tar xz
	
	                            while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
	                            curl ${pd_url} | tar xz
	
	                            mkdir -p ./tidb-src
	                            while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 15; done
	                            curl ${tidb_url} | tar xz -C ./tidb-src
	                            ln -s \$(pwd)/tidb-src "${ws}/go/src/github.com/pingcap/tidb"
	
	                            mv tidb-src/bin/tidb-server ./bin/tidb-server
	                            """
                            }
                        }

                        try {
                            timeout(10) {
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                rm -rf ./tikv ./pd
                                set -e
                                
                                bin/pd-server --name=pd --data-dir=pd &>pd_${mytest}.log &
                                sleep 10
                                echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                
                                bin/tikv-server -C tikv_config.toml --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                                sleep 10
                                if [ -f test.sh ]; then awk 'NR==2 {print "set -x"} 1' test.sh > tmp && mv tmp test.sh && chmod +x test.sh; fi
                                if [ \"${TIDB_BRANCH}\" != \"release-2.0\" ]; then
                                    mv t t_bak
                                    mkdir t
                                    cd t_bak
                                    cp \$(cat ../packages_${chunk}) ../t
                                    cd ..
                                    export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                    export log_level=debug
                                    TIDB_SERVER_PATH=`pwd`/bin/tidb-server \
                                    TIKV_PATH='127.0.0.1:2379' \
                                    TIDB_TEST_STORE_NAME=tikv \
                                    ${test_cmd}
                                fi
                                """
                            }
                        } catch (err) {
                            sh"""
                            cat mysql-test.out || true
                            """

                            sh """
                            cat pd_${mytest}.log
                            cat tikv_${mytest}.log
                            cat tidb*.log
                            """
                            throw err
                        } finally {
                            sh """
                            set +e
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            set -e
                            """
                        }
                    }
                }
            }
        }

        tests["Integration Randgen Test 1"] = {
            run_split("randgen-test", "randgentest", "./test.sh", 1)
        }
        tests["Integration Randgen Test 2"] = {
            run_split("randgen-test", "randgentest", "./test.sh", 2)
        }
        tests["Integration Randgen Test 3"] = {
            run_split("randgen-test", "randgentest", "./test.sh", 3)
        }
        tests["Integration Analyze Test"] = {
            run("analyze_test", "analyzetest", "./test.sh")
        }
        tests["Integration TiDB Test 1"] = {
            run("tidb_test", "tidbtest", "TEST_FILE=ql_1.t ./test.sh")
        }
        tests["Integration TiDB Test 2"] = {
            run("tidb_test", "tidbtest", "TEST_FILE=ql_2.t ./test.sh")
        }
        tests["Integration Go SQL Test"] = {
            run("go-sql-test", "gosqltest", "./test.sh")
        }
        tests["Integration GORM Test"] = {
            run("gorm_test", "gormtest", "./test.sh")
        }
        tests["Integration MySQL Test"] = {
            run("mysql_test", "mysqltest", "./test.sh")
        }
        tests["Integration MySQL Test Cached"] = {
            run("mysql_test", "mysqltest", "CACHE_ENABLED=1 ./test.sh")
        }
        tests["Integration Explain Test"] = {
            run_with_pod(arch, os) {
                def ws = pwd()

                dir("go/src/github.com/pingcap/tidb") {
                    def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
                    def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${TIDB_COMMIT}/centos7/done"
                    def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                    def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                    def tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
                    def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                    def pd_sha1= sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                    def pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                    if (arch == "arm64") {
                        tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/tidb-linux-arm64.tar.gz"
                        tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-linux-arm64.tar.gz"
                        pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-linux-arm64.tar.gz"
                    }
                    container("golang") {
                        try {
                            timeout(10) {
                                retry(3){
                                    deleteDir()
                                    sh """
	                                while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
	                                curl ${tidb_url} | tar xz
	                                """
                                }
                            }

                            timeout(20) {
                                sh """
                                if [ ! -d cmd/explaintest ]; then
                                    echo "no explaintest file found in 'cmd/explaintest'"
                                    exit -1
                                fi
                                cp bin/tidb-server cmd/explaintest
                                cp bin/importer cmd/explaintest
                                cd cmd/explaintest
                                GO111MODULE=on go build -o explain_test
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                ./run-tests.sh -s ./tidb-server -i ./importer -b n
                                """
                            }
                        } catch (err) {
                            sh """
                            cat tidb*.log || true
                            """
                            sh "cat explain-test.out || true"
                            throw err
                        } finally {
                            sh """
                            set +e
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            set -e
                            """
                        }
                    }
                }
            }
        }

        parallel tests
    }
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


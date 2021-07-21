properties([
        parameters([
                string(
                        defaultValue: '029becc06b032412dbf00844e10a229598e9a956',
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

K8S_NAMESPACE = "jenkins-tidb"
BRANCH_NEED_GO1160 = ["master", "release-5.1"]

ARCH = "x86" // [ x86 | arm64 ]
OS = "linux" // [ centos7 | kylin_v10 | darwin ]

tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
tidb_done_url =  "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/done"


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
            label = "tidb-integration-ddl"
            unstash_file = "tidb-ddl-test-linux"
            pod_go_docker_image = "hub.pingcap.net/pingcap/centos7_golang-1.16:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-integration-ddl-arm64"
            unstash_file = "tidb-ddl-test-linux-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            cloud = "kubernetes-arm64"
        }
    } else {
        if (arch == "x86") {
            label = "tidb-integration-ddl"
            unstash_file = "tidb-ddl-test-linux"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-integration-ddl-arm64"
            unstash_file = "tidb-ddl-test-linux-arm64"
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

def run_build(arch, os, stash_name) {
    run_with_pod(arch, os) {
        container("golang") {
            dir("go/src/github.com/pingcap/tidb") {
                def filepath = "builds/pingcap/tidb/ddl-test/centos7/${TIDB_COMMIT}/tidb-server-${os}-${arch}.tar"
                timeout(5) {
                    sh """
                    # while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 2; done
                    curl ${tidb_url} | tar xz -C ./
                    if [ \$(grep -E "^ddltest:" Makefile) ]; then
                        make ddltest
                    fi
                    ls bin
                    rm -rf bin/tidb-server-*
                    cd ..
                    tar -cf tidb-server-${os}-${arch}.tar tidb
                    curl -F ${filepath}=@tidb-server-${os}-${arch}.tar ${FILE_SERVER_URL}/upload
                    """
                }
            }


        }
    }
}

def run_test(arch, os, stash_name) {
    stage("${arch}  Integration DLL Test") {
        def tests = [:]
        def run = { test_dir, mytest, ddltest ->
            run_with_pod(arch, os) {
                println "debug command:\nkubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                def ws = pwd()
                container("golang") {
                    dir("go/src/github.com/pingcap/tidb-test") {
                        timeout(10) {
                            def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                            sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 10; done
                        """
                            def dir = pwd()
                            sh """
                        tidb_test_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"`
                        tidb_test_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/\${tidb_test_sha1}/centos7/tidb-test.tar.gz"

                        tidb_tar_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb/ddl-test/centos7/${TIDB_COMMIT}/tidb-server-${os}-${arch}.tar"

                        tikv_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"`
                        tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/\${tikv_sha1}/centos7/tikv-server.tar.gz"

                        pd_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"`
                        pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/\${pd_sha1}/centos7/pd-server.tar.gz"

                        while ! curl --output /dev/null --silent --head --fail \${tidb_test_url}; do sleep 10; done
                        curl \${tidb_test_url} | tar xz

                        cd ${test_dir}

                        while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 10; done
                        curl \${tikv_url} | tar xz

                        while ! curl --output /dev/null --silent --head --fail \${pd_url}; do sleep 10; done
                        curl \${pd_url} | tar xz

                        mkdir -p ${dir}/../tidb/
                        curl \${tidb_tar_url} | tar -xf - -C ${dir}/../
                        mv ${dir}/../tidb/bin/tidb-server ./bin/ddltest_tidb-server

                        cd ${dir}/../tidb/
                        export GOPROXY=https://goproxy.cn
                        GO111MODULE=on go mod vendor -v || true

                        mkdir -p ${dir}/../tidb_gopath/src
                        cd ${dir}/../tidb_gopath
                        if [ -d ../tidb/vendor/ ]; then cp -rf ../tidb/vendor/* ./src; fi

                        if [ -f ../tidb/go.mod ]; then mv ${dir}/../tidb/vendor ${dir}/../tidb/_vendor; fi
                        """
                        }
                    }

                    dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                        try {
                            timeout(10) {
                                sh """
                            set +e
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            killall -9 -r flash_cluster_manager
                            rm -rf /tmp/tidb
                            rm -rf ./tikv ./pd
                            set -e

                            bin/pd-server --name=pd --data-dir=pd &>pd_${mytest}.log &
                            sleep 10
                            echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                            bin/tikv-server -C tikv_config.toml --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                            sleep 10

                            export PATH=`pwd`/bin:\$PATH
                            export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                            if [ -f ${ws}/go/src/github.com/pingcap/tidb/bin/ddltest ]; then
                                export DDLTEST_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/ddltest
                            fi
                            export log_level=debug
                            export GOPROXY=https://goproxy.cn
                            TIDB_SERVER_PATH=`pwd`/bin/ddltest_tidb-server \
                            GO111MODULE=off GOPATH=${ws}/go/src/github.com/pingcap/tidb-test/_vendor:${ws}/go/src/github.com/pingcap/tidb_gopath:${ws}/go ./test.sh -check.f='${ddltest}' 2>&1
                            """
                            }
                        } catch (err) {
                            sh """
                        cat pd_${mytest}.log
                        cat tikv_${mytest}.log
                        cat ./ddltest/tidb_log_file_* || true
                        cat tidb_log_file_*
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

        tests["Integration DDL Insert Test"] = {
            run("ddl_test", "ddl_insert_test", "TestDDLSuite.TestSimple.*Insert")
        }

        tests["Integration DDL Update Test"] = {
            run("ddl_test", "ddl_update_test", "TestDDLSuite.TestSimple.*Update")
        }

        tests["Integration DDL Delete Test"] = {
            run("ddl_test", "ddl_delete_test", "TestDDLSuite.TestSimple.*Delete")
        }

        tests["Integration DDL Other Test"] = {
            run("ddl_test", "ddl_other_test", "TestDDLSuite.TestSimp(le\$|leMixed|leInc)")
        }

        tests["Integration DDL Column Test"] = {
            run("ddl_test", "ddl_column_index_test", "TestDDLSuite.TestColumn")
        }

        tests["Integration DDL Index Test"] = {
            run("ddl_test", "ddl_column_index_test", "TestDDLSuite.TestIndex")
        }

        parallel tests
    }
}


parallel (
    test_x86: {
        def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/tidb-server.tar.gz"
        def stash_name = "ddl-test-x86"
        stage("build") {
            run_build("x86", "centos7", stash_name)
        }
        stage("test") {
            run_test("x86", "centos7", stash_name)
        }
    },
    test_arm64_centos7: {
        def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_COMMIT}/centos7/tidb-server-linux-arm64.tar.gz"
        def stash_name = "ddl-test-arm64"
    },
    test_arm64_kylin_v10: {

    },

)
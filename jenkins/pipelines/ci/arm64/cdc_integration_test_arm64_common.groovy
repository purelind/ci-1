import com.fasterxml.jackson.databind.exc.InvalidDefinitionException

/**
 * The total number of integration test groups.
 */
TOTAL_COUNT = 0

/**
 * Integration testing number of tests per group.
 */
GROUP_SIZE = 2

/**
 * Partition the array.
 * @param array
 * @param size
 * @return Array partitions.
 */
static def partition(array, size) {
    def partitions = []
    int partitionCount = array.size() / size

    partitionCount.times { partitionNumber ->
        int start = partitionNumber * size
        int end = start + size - 1
        partitions << array[start..end]
    }

    if (array.size() % size) partitions << array[partitionCount * size..-1]
    return partitions
}

/**
 * Get repo binary download url by branch
 * @param repo [ tidb | tikv | pd | ticdc | tidb-binlog | tidb-tools | tiflash ]
 * @param target_branch [ master | release-5.1 | release-5.0 | release-4.0 ]
 * @param arch [ x86 | arm64 ]
 * @retun String binary download url.
*/
def get_binary_download_url(repo, target_branch, arch) {
    def hash = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/${repo}/${target_branch}/sha1").trim()
    def download_url = ""
    if (arch == "x86") {
        download_url = "${FILE_SERVER_URL}/download/builds/pingcap/${repo}/${hash}/centos7/${repo}.tar.gz"
        if (repo == "br") {
            download_url = "${FILE_SERVER_URL}/download/builds/pingcap/${repo}/${target_branch}/${hash}/centos7/br.tar.gz"
        }
        if (repo == "tiflash") {
            download_url = "${FILE_SERVER_URL}/download/builds/pingcap/${repo}/${target_branch}/${hash}/centos7/tiflash.tar.gz"
        }
    } else if (arch == "arm64") {
        download_url = ""
    } else {
        throw InvalidDefinitionException
    }
}


def run_with_pod(arch, os, is_need_go1160, sink_type_lable, Closure body) {
    def label = ""
    def cloud = "kubernetes"
    def pod_go_docker_image = ""
    def jnlp_docker_image = ""
    def pod_kafka_docker_image = ""
    def pod_zookeeper_docker_image = ""

    def KAFKA_TAG = "2.12-2.4.1"
    def KAFKA_VERSION = "2.4.1"



    if (is_need_go1160) {
        println "current test use go1.16.4"
        if (arch == "x86") {
            label = "ticdc-${sink_type_lable}-integration-test"
            cloud = "kubernetes"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
            pod_zookeeper_docker_image = "wurstmeister/zookeeper:latest"
            pod_kafka_docker_image = "wurstmeister/kafka:latest"
        }
        if (arch == "arm64") {
            label = "ticdc-${sink_type_lable}-integration-test-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:tini"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            cloud = "kubernetes-arm64"
            pod_zookeeper_docker_image = "zookeeper:3.4.13"
            pod_kafka_docker_image = "hub.pingcap.net/jenkins/kafka-arm64:latest"
        }
    } else {
        println "current test use go1.13.7"
        if (arch == "x86") {
            label = "ticdc-${sink_type_lable}-integration-test"
            cloud = "kubernetes"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
            pod_zookeeper_docker_image = "wurstmeister/zookeeper:latest"
            pod_kafka_docker_image = "wurstmeister/kafka:latest"
        }
        if (arch == "arm64") {
            label = "ticdc-${sink_type_lable}-integration-test-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:tini"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            cloud = "kubernetes-arm64"
            pod_zookeeper_docker_image = "zookeeper:3.4.13"
            pod_kafka_docker_image = "hub.pingcap.net/jenkins/kafka-arm64:latest"
        }
    }

    if (os == "kylin") {
        label = "ticdc-${sink_type_lable}-integration-test-arm64-kylin"
        cloud = "kubernetes-kylin-arm64"
    }

    if (sink_type_lable == "mysql") {
        podTemplate(label: label,
                    cloud: cloud,
                    idleMinutes: 60,
                    namespace: 'jenkins-tidb',
                    containers: [
                            containerTemplate(
                                    name: 'golang', alwaysPullImage: false,
                                    image: pod_go_docker_image, ttyEnabled: true,
                                    resourceRequestCpu: '20000m', resourceRequestMemory: '16Gi',
                                    args: 'cat',
                            ),
                            containerTemplate(
                                    name: 'jnlp', image: jnlp_docker_image, alwaysPullImage: false,
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
    if (sink_type_lable == "kafka") {

        println "KAFKA_VERSION=${KAFKA_VERSION}"
        env.KAFKA_VERSION = "${KAFKA_VERSION}"
        // HACK! Download jks by injecting RACK_COMMAND
        // https://git.io/JJZXX -> https://github.com/pingcap/ticdc/raw/6e62afcfecc4e3965d8818784327d4bf2600d9fa/tests/_certificates/kafka.server.keystore.jks
        // https://git.io/JJZXM -> https://github.com/pingcap/ticdc/raw/6e62afcfecc4e3965d8818784327d4bf2600d9fa/tests/_certificates/kafka.server.truststore.jks
        def download_jks = 'curl -sfL https://git.io/JJZXX -o /tmp/kafka.server.keystore.jks && curl -sfL https://git.io/JJZXM -o /tmp/kafka.server.truststore.jks'

        podTemplate(label: label, 
                cloud: cloud,
                idleMinutes: 60,
                containers: [
                        containerTemplate(
                                name: 'jnlp', image: jnlp_docker_image, alwaysPullImage: false,
                                resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                        ),
                        containerTemplate(name: 'golang',alwaysPullImage: false, image: pod_go_docker_image,
                                resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                                ttyEnabled: true, command: 'cat'),
                        containerTemplate(name: 'zookeeper',alwaysPullImage: true, image: pod_zookeeper_docker_image,
                                resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                                ttyEnabled: true),
                        containerTemplate(
                                name: 'kafka',
                                image: pod_kafka_docker_image,
                                resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                                ttyEnabled: true,
                                alwaysPullImage: true,
                                envVars: [
                                        envVar(key: 'KAFKA_MESSAGE_MAX_BYTES', value: '1073741824'),
                                        envVar(key: 'KAFKA_REPLICA_FETCH_MAX_BYTES', value: '1073741824'),
                                        envVar(key: 'KAFKA_BROKER_ID', value: '1'),
                                        envVar(key: 'RACK_COMMAND', value: download_jks),
                                        envVar(key: 'KAFKA_LISTENERS', value: 'SSL://127.0.0.1:9093,PLAINTEXT://127.0.0.1:9092'),
                                        envVar(key: 'KAFKA_ADVERTISED_LISTENERS', value: 'SSL://127.0.0.1:9093,PLAINTEXT://127.0.0.1:9092'),
                                        envVar(key: 'KAFKA_SSL_KEYSTORE_LOCATION', value: '/tmp/kafka.server.keystore.jks'),
                                        envVar(key: 'KAFKA_SSL_KEYSTORE_PASSWORD', value: 'test1234'),
                                        envVar(key: 'KAFKA_SSL_KEY_PASSWORD', value: 'test1234'),
                                        envVar(key: 'KAFKA_SSL_TRUSTSTORE_LOCATION', value: '/tmp/kafka.server.truststore.jks'),
                                        envVar(key: 'KAFKA_SSL_TRUSTSTORE_PASSWORD', value: 'test1234'),
                                        envVar(key: 'ZK', value: 'zk'),
                                        envVar(key: 'KAFKA_ZOOKEEPER_CONNECT', value: 'localhost:2181'),
                                ]
                        )],
                volumes:[
                        emptyDirVolume(mountPath: '/tmp', memory: true),
                        emptyDirVolume(mountPath: '/home/jenkins', memory: true)
                ]
                ) {
                    node(label) {
                        println "debug command:\nkubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                        sh "ls -l /tmp"
                        body()
                    }
                }
    } 
}


/**
 * Prepare the binary file for testing.
 */
def prepare_binaries(arch, os, is_need_go1160, sink_type_lable) {
    stage('Prepare Binaries') {
        def ticdc_bin_file = "${env.JOB_NAME}_${env.BUILD_NUMBER}-${os}-${arch}.tar.gz"
        run_with_pod(arch, os, is_need_go1160, sink_type_lable){
            container("golang") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                def ws = pwd()
                deleteDir()
                unstash 'ticdc'

                dir("go/src/github.com/pingcap/ticdc") {
                    sh """
                        GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make cdc
                        GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make integration_test_build
                        GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make kafka_consumer
                        GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make check_failpoint_ctl
                        tar czvf ticdc_bin.tar.gz bin/*
                        curl -F test/cdc/ci/${ticdc_bin_file}=@ticdc_bin.tar.gz http://fileserver.pingcap.net/upload
                    """
                }
                dir("go/src/github.com/pingcap/ticdc/tests") {
                    def cases_name = sh(
                            script: 'find . -maxdepth 2 -mindepth 2 -name \'run.sh\' | awk -F/ \'{print $2}\'',
                            returnStdout: true
                    ).trim().split().join(" ")
                    sh "echo ${cases_name} > CASES"
                }

                stash includes: "go/src/github.com/pingcap/ticdc/tests/CASES", name: "cases_name", useDefaultExcludes: false
            }
        }
    }
}

/**
 * Start running tests.
 * @param sink_type Type of Sink, optional value: mysql/kafaka.
 * @param node_label
 */
def tests(sink_type, arch, os) {
    stage("Tests") {
        // def test_cases = [:]
        // // Set to fail fast.
        // test_cases.failFast = true

        // // Start running unit tests.
        // test_cases["unit test"] = {
        //     run_with_pod(arch, os, is_need_go1160, sink_type){
        //         container("golang") {
        //             def ws = pwd()
        //             deleteDir()
        //             unstash 'ticdc'

        //             dir("go/src/github.com/pingcap/ticdc") {
        //                 sh """
        //                     rm -rf /tmp/tidb_cdc_test
        //                     mkdir -p /tmp/tidb_cdc_test
        //                     GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make test
        //                     rm -rf cov_dir
        //                     mkdir -p cov_dir
        //                     ls /tmp/tidb_cdc_test
        //                     cp /tmp/tidb_cdc_test/cov*out cov_dir
        //                 """
        //                 sh """
        //                     tail /tmp/tidb_cdc_test/cov*
        //                 """
        //             }

        //             stash includes: "go/src/github.com/pingcap/ticdc/cov_dir/**", name: "unit_test-${os}-${arch}", useDefaultExcludes: false
        //         }
        //     }
        // }

        // Start running integration tests.
        def run_integration_test = { step_name, case_names ->
            run_with_pod(arch, os, is_need_go1160, sink_type){
                container("golang") {
                    def ws = pwd()
                    deleteDir()
                    println "this step will run tests: ${case_names}"
                    unstash 'ticdc'

                    dir("go/src/github.com/pingcap/ticdc") {
                        download_binaries(arch, os)

                        try {
                            // jq binary download from  ${FILE_SERVER_URL}/download/builds/pingcap/test/jq-1.6/jq-linux64
                            //  is not executorable. use jq cmd in docker image hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:tini
                            if (arch == "arm64") {
                                sh """
                                cd ./bin
                                rm -rf jq
                                ln -s /usr/bin/jq jq
                                """
                            }
                            sh """
                                sudo pip install s3cmd
                                rm -rf /tmp/tidb_cdc_test
                                mkdir -p /tmp/tidb_cdc_test
                                echo "${env.KAFKA_VERSION}" > /tmp/tidb_cdc_test/KAFKA_VERSION

                                GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make integration_test_${sink_type} CASE="${case_names}"
                                rm -rf cov_dir
                                mkdir -p cov_dir
                                ls /tmp/tidb_cdc_test
                                cp /tmp/tidb_cdc_test/cov*out cov_dir || touch cov_dir/dummy_file_${step_name}
                            """
                            // cyclic tests do not run on kafka sink, so there is no cov* file.
                            sh """
                                tail /tmp/tidb_cdc_test/cov* || true
                            """
                        } catch (Exception e) {
                            sh """
                                echo "archive all log"
                                for log in `ls /tmp/tidb_cdc_test/*/*.log`; do
                                    dirname=`dirname \$log`
                                    basename=`basename \$log`
                                    mkdir -p "log\$dirname"
                                    tar zcvf "log\${log}.tgz" -C "\$dirname" "\$basename"
                                done
                            """
                            archiveArtifacts artifacts: "log/tmp/tidb_cdc_test/**/*.tgz", caseSensitive: false
                            throw e;
                        }
                    }

                    stash includes: "go/src/github.com/pingcap/ticdc/cov_dir/**", name: "integration_test_${step_name}_${os}_${arch}", useDefaultExcludes: false
                }
            }
        }


        // Gets the name of each case.
        unstash 'cases_name'
        def cases_name = sh(
                script: 'cat go/src/github.com/pingcap/ticdc/tests/CASES',
                returnStdout: true
        ).trim().split()

        // Run integration tests in groups.
        def step_cases = []
        def cases_namesList = partition(cases_name, GROUP_SIZE)
        TOTAL_COUNT = cases_namesList.size()
        cases_namesList.each { case_names ->
            step_cases.add(case_names)
        }
        // step_cases.eachWithIndex { case_names, index ->
        //     def step_name = "step_${index}"
        //     test_cases["integration test ${step_name}"] = {
        //         run_integration_test(step_name, case_names.join(" "))
        //     }
        // }

        step_cases.eachWithIndex { case_names, index ->
            def step_name = "step_${index}"
            run_integration_test(step_name, case_names.join(" "))
        }

        // parallel test_cases
    }
}

def debug_tests(sink_type, arch, os) {
    stage("Tests") {
        echo "hello world"
        def run_integration_test = { step_name, case_names ->
            run_with_pod(arch, os, is_need_go1160, sink_type){
                container("golang") {
                    def ws = pwd()
                    deleteDir()
                    println "this step will run tests: ${case_names}"
                    unstash 'ticdc'

                    dir("go/src/github.com/pingcap/ticdc") {
                        download_binaries(arch, os)

                        try {
                            sh """
                                sudo pip install s3cmd

                                rm -rf /tmp/tidb_cdc_test
                                mkdir -p /tmp/tidb_cdc_test
                                echo "${env.KAFKA_VERSION}" > /tmp/tidb_cdc_test/KAFKA_VERSION
                                
                                GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make integration_test_${sink_type} CASE="${case_names}"

                            """
                            // cyclic tests do not run on kafka sink, so there is no cov* file.
                            sh """
                                tail /tmp/tidb_cdc_test/cov* || true
                            """
                        } catch (Exception e) {
                            sh """
                                echo "archive all log"
                                for log in `ls /tmp/tidb_cdc_test/*/*.log`; do
                                    cat \$log
                                done
                            """
                            throw e;
                        }
                    }
                }
            }
        }

        run_integration_test("test1", "autorandom")
    }
}


get_commit_hash = { prj, branch_or_hash ->
    if (branch_or_hash.length() == 40) {
        return branch_or_hash
    }

    def hash = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/${prj}/${branch_or_hash}/sha1").trim()
    return hash
}

/**
 * Download the integration test-related binaries.
 */
def download_binaries(arch, os) {

    def tidb_sha1 = get_commit_hash("tidb", TIDB_BRANCH_OR_COMMIT)
    def tikv_sha1 = get_commit_hash("tikv", TIKV_BRANCH_OR_COMMIT)
    def pd_sha1 = get_commit_hash("pd", PD_BRANCH_OR_COMMIT)
    def tools_sha1 = get_commit_hash("tidb-tools", TOOLS_BRANCH_OR_COMMIT)
    def tiflash_branch_sha1 = TIFLASH_BRANCH_AND_COMMIT

    if (arch == "x86") {
        if (TIFLASH_BRANCH_AND_COMMIT == "master" || TIFLASH_BRANCH_AND_COMMIT == "release-5.1") {
            tiflash_branch_sha1 = "${TIFLASH_BRANCH_AND_COMMIT}/" + get_commit_hash("tiflash", TIFLASH_BRANCH_AND_COMMIT)
        }
    }
    if (arch == "arm64") {
        if (TIFLASH_BRANCH_AND_COMMIT == "master" || TIFLASH_BRANCH_AND_COMMIT == "release-5.1" ) {
            tiflash_branch_sha1 = get_commit_hash("tiflash", TIFLASH_BRANCH_AND_COMMIT)
        } else {
            tiflash_branch_sha1 = TIFLASH_BRANCH_AND_COMMIT.split("/")[1]
        }
    }

    def tidb_url  = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"
    def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
    def pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
    def tiflash_url = "${FILE_SERVER_URL}/download/builds/pingcap/tiflash/${tiflash_branch_sha1}/centos7/tiflash.tar.gz"
    
    def minio_url = "${FILE_SERVER_URL}/download/minio.tar.gz"
    def etcd_file = "etcd-v3.4.7-linux-amd64"
    def etcd_url = "${FILE_SERVER_URL}/download/builds/pingcap/cdc/etcd-v3.4.7-linux-amd64.tar.gz"
    def sync_diff_inspector_url = "${FILE_SERVER_URL}/download/builds/pingcap/cdc/sync_diff_inspector.tar.gz"
    def jq_url = "https://github.com/stedolan/jq/releases/download/jq-1.6/jq-linux64"
    def go_ycsb = "${FILE_SERVER_URL}/download/builds/pingcap/go-ycsb/test-br/go-ycsb"
    def ticdc_bin_url = "${FILE_SERVER_URL}/download/test/cdc/ci/${env.JOB_NAME}_${env.BUILD_NUMBER}-${os}-${arch}.tar.gz"

    if (arch == "arm64") {
            tidb_url  = "${FILE_SERVER_URL}/download/builds/pingcap/test/tidb/${tidb_sha1}/centos7/tidb-linux-arm64.tar.gz"
            tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/test/tikv/${tikv_sha1}/centos7/tikv-linux-arm64.tar.gz"
            pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/test/pd/${pd_sha1}/centos7/pd-linux-arm64.tar.gz"
            tiflash_url = "${FILE_SERVER_URL}/download/builds/pingcap/test/tics/${tiflash_branch_sha1}/centos7/tics-linux-arm64.tar.gz"
            
            minio_url = "${FILE_SERVER_URL}/download/builds/pingcap/test/minio/minio-linux-arm64.tar.gz"
            etcd_file = "etcd-v3.4.7-linux-arm64"
            etcd_url = "${FILE_SERVER_URL}/download/builds/pingcap/cdc/etcd-v3.4.7-linux-arm64.tar.gz"
            sync_diff_inspector_url = "${FILE_SERVER_URL}/download/builds/pingcap/cdc/sync_diff_inspector-linux-arm64.tar.gz"
            jq_url = "${FILE_SERVER_URL}/download/builds/pingcap/test/jq-1.6/jq-linux64"
            go_ycsb = "${FILE_SERVER_URL}/download/builds/pingcap/go-ycsb/test-br/go-ycsb-linux-arm64"
            ticdc_bin_url = "${FILE_SERVER_URL}/download/test/cdc/ci/${env.JOB_NAME}_${env.BUILD_NUMBER}-${os}-${arch}.tar.gz"
    }


    println "tidb_url=${tidb_url}"
    println "tikv_url=${tikv_url}"
    println "pd_url=${pd_url}"
    println "tiflash_url=${tiflash_url}"

    sh """
        mkdir -p third_bin
        mkdir -p tmp
        mkdir -p bin

        curl ${tidb_url} | tar xz -C ./tmp bin/tidb-server
        curl ${pd_url} | tar xz -C ./tmp bin/*
        curl ${tikv_url} | tar xz -C ./tmp bin/tikv-server
        curl ${minio_url} | tar xz -C ./tmp/bin minio

        mv tmp/bin/* third_bin

        curl ${tiflash_url} | tar xz -C third_bin
        mv third_bin/tiflash third_bin/_tiflash
        mv third_bin/_tiflash/* third_bin
        curl ${go_ycsb} -o third_bin/go-ycsb
        curl -L ${etcd_url} | tar xz -C ./tmp
        mv tmp/${etcd_file}/etcdctl third_bin
        curl -L ${sync_diff_inspector_url} | tar xz -C ./third_bin
        curl -L ${jq_url} -o jq
        mv jq third_bin
        chmod a+x third_bin/*
        rm -rf tmp
        curl -L ${ticdc_bin_url} | tar xvz -C .
        mv ./third_bin/* ./bin
        rm -rf third_bin

        ls -l ./bin
    """
}

/**
 * Collect and calculate test coverage.
 */
def coverage(arch, os) {
    stage('Coverage') {
        node("${GO_TEST_SLAVE}") {
            def ws = pwd()
            deleteDir()
            unstash 'ticdc'
            unstash 'unit_test'

            // unstash all integration tests.
            def step_names = []
            for ( int i = 1; i < TOTAL_COUNT; i++ ) {
                // step_names.add("integration_test_step_${i}")
                step_names.add("integration_test_step_${i}_${os}_${arch}")
                // "integration_test_${step_name}_${os}_${arch}"
            }
            step_names.each { item ->
                unstash item
            }

            dir("go/src/github.com/pingcap/ticdc") {
                container("golang") {
                    archiveArtifacts artifacts: 'cov_dir/*', fingerprint: true
                            
                    withCredentials([string(credentialsId: 'codecov-token-ticdc', variable: 'CODECOV_TOKEN')]) {
                        timeout(30) {
                            sh """
                            rm -rf /tmp/tidb_cdc_test
                            mkdir -p /tmp/tidb_cdc_test
                            cp cov_dir/* /tmp/tidb_cdc_test

                            set +x
                            BUILD_NUMBER=${env.BUILD_NUMBER} CODECOV_TOKEN="${CODECOV_TOKEN}" COVERALLS_TOKEN="${COVERALLS_TOKEN}" GOPATH=${ws}/go:\$GOPATH PATH=${ws}/go/bin:/go/bin:\$PATH JenkinsCI=1 make coverage
                            set -x
                            """
                        }
                    }
                }
            }
        }
    }
}

return this
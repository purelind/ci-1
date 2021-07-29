properties([
        parameters([
                string(
                        defaultValue: 'master',
                        name: 'TIDB_BRANCH_OR_COMMIT',
                        trim: true
                ),
                string(
                        defaultValue: 'master',
                        name: 'TIKV_BRANCH_OR_COMMIT',
                        trim: true
                ),
                string(
                        defaultValue: 'master',
                        name: 'PD_BRANCH_OR_COMMIT',
                        trim: true
                ),
                string(
                        defaultValue: 'master',
                        name: 'BINLOG_BRANCH_OR_COMMIT',
                        trim: true
                ),
                string(
                        defaultValue: 'master',
                        name: 'BR_BRANCH_AND_COMMIT',
                        trim: true
                ),
                string(
                        defaultValue: 'master',
                        name: 'TICDC_BRANCH_OR_COMMIT',
                        trim: true
                ),
                string(
                        defaultValue: 'master',
                        name: 'TOOLS_BRANCH_OR_COMMIT',
                        trim: true
                ),
                string(
                        defaultValue: 'master',
                        name: 'TIFLASH_BRANCH_AND_COMMIT',
                        trim: true
                ),
        ])
])

K8S_NAMESPACE = "jenkins-tidb"
BRANCH_NEED_GO1160 = ["master", "release-5.1"]

ARCH = "x86" // [ x86 | arm64 ]
OS = "linux" // [ centos7 | kylin_v10 | darwin ]



get_commit_hash = { prj, branch_or_hash ->
    if (branch_or_hash.length() == 40) {
        return branch_or_hash
    }

    def hash = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/${prj}/${branch_or_hash}/sha1").trim()
    return hash
}


def run_with_pod(arch, os, Closure body) {
    def label = ""
    def cloud = "kubernetes"
    def pod_go_docker_image = ""
    def jnlp_docker_image = ""

        if (arch == "x86") {
            label = "tidb-and-tools-e2e-test"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_tpcc:test"
            jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
        }
        if (arch == "arm64") {
            label = "tidb-and-tools-e2e-test-arm64"
            pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13-arm64:latest"
            jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
            cloud = "kubernetes-arm64"
        }

    podTemplate(label: label,
            cloud: cloud,
            namespace: 'jenkins-tidb',
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true, privileged: true,
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

    }
}

def run_test(arch, os) {
    run_with_pod(arch, os) {
        container('golang') {
            def ws = pwd()
            stage('Prepare') {
                sh """
                    set +e
                    killall -9 -r -q br
                    killall -9 -r -q cdc
                    killall -9 -r -q pump
                    killall -9 -r -q drainer
                    killall -9 -r -q tiflash
                    killall -9 -r -q tidb-server
                    killall -9 -r -q tikv-server
                    killall -9 -r -q pd-server
                    rm -rf /tmp/tidb
                    set -e
                    """
                deleteDir()

                timeout(10) {
                    def tidb_sha1 = get_commit_hash("tidb", TIDB_BRANCH_OR_COMMIT)
                    def tikv_sha1 = get_commit_hash("tikv", TIKV_BRANCH_OR_COMMIT)
                    def pd_sha1 = get_commit_hash("pd", PD_BRANCH_OR_COMMIT)
                    def binlog_sha1 = get_commit_hash("tidb-binlog", BINLOG_BRANCH_OR_COMMIT)
                    def br_sha1 = BR_BRANCH_AND_COMMIT
                    if (br_sha1 == "master" || br_sha1 == "") {
                        br_sha1 = "master/" + get_commit_hash("br", "master")
                    }
                    def cdc_sha1 = get_commit_hash("ticdc", TICDC_BRANCH_OR_COMMIT)
                    def tools_sha1 = get_commit_hash("tidb-tools", TOOLS_BRANCH_OR_COMMIT)
                    def tiflash_branch_sha1 = TIFLASH_BRANCH_AND_COMMIT
                    if (tiflash_branch_sha1 == "master" || tiflash_branch_sha1 == "") {
                        tiflash_branch_sha1 = "master/" + get_commit_hash("tiflash", "master")
                    }

                    if (arch == "x86") {
                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/ticdc/${cdc_sha1}/centos7/ticdc-linux-amd64.tar.gz | tar xz"
                        sh """
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz bin
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz | tar xz
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz | tar xz
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/${binlog_sha1}/centos7/tidb-binlog.tar.gz | tar xz
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/br/${br_sha1}/centos7/br.tar.gz | tar xz
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/${tools_sha1}/centos7/tidb-tools.tar.gz | tar xz bin
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/${tiflash_branch_sha1}/centos7/tiflash.tar.gz | tar xz
                        cp -R /home/jenkins/bin/go-tpc bin/
                        """
                    }
                    if (arch == "arm64") {
                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/ticdc/${cdc_sha1}/centos7/ticdc-linux-arm64.tar.gz | tar xz"
                        sh """
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/test/tidb/${tidb_sha1}/centos7/tidb-linux-arm64.tar.gz | tar xz bin
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/test/tikv/${tikv_sha1}/centos7/tikv-linux-arm64.tar.gz | tar xz
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/test/pd/${pd_sha1}/centos7/pd-linux-arm64.tar.gz | tar xz
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/test/tidb-binlog/${binlog_sha1}/centos7/tidb-binlog-linux-arm64.tar.gz | tar xz
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/test/br/${br_sha1}/centos7/br-linux-arm64.tar.gz | tar xz
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/test/tidb-tools/${tools_sha1}/centos7/tidb-tools-linux-arm64.tar.gz | tar xz bin
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/test/tiflash/${tiflash_branch_sha1}/centos7/tiflash-linux-arm64.tar.gz | tar xz
                        curl ${FILE_SERVER_URL}/download/build/pingcap/test/go-tpc/ae823e848af289e8a8b82b4bc975b1550a961079/go-tpc-linux-arm64 -o bin/go-tpc
                        """
                    }
                }
            }

            def start_tidb_cluster = { i, enable_binlog ->
                def pd_port = 2379 + 10 * i
                def peer_port = 2378 + 10 * i
                sh """
                    cat > pd.toml << __EOF__
name = "pd-${pd_port}"
data-dir = "pd-${pd_port}"
client-urls = "http://127.0.0.1:${pd_port}"
peer-urls = "http://127.0.0.1:${peer_port}"
initial-cluster-state = "new"

[replication]
max-replicas = 1
enable-placement-rules = true

__EOF__

                    """
                def tikv_port = 20160 + 10 * i
                def status_port = 20165 + 10 * i
                sh """
                    ${ws}/bin/pd-server --config ./pd.toml -force-new-cluster &> pd.log &
                    ${ws}/bin/tikv-server --pd=127.0.0.1:${pd_port} -s tikv-${tikv_port} --addr=0.0.0.0:${tikv_port} --advertise-addr=127.0.0.1:${tikv_port} --status-addr=0.0.0.0:${status_port} -f ./tikv.log &
                    sleep 5
                    """
                pump_port = 8250 + 10 * i
                def tidb_status_port = 10800 + i
                def tidb_port = 4000 + i
                if (enable_binlog) {
                    sh """
                        ${ws}/bin/pump -addr 127.0.0.1:${pump_port} -pd-urls  http://127.0.0.1:${pd_port} -log-file pump.log &
                        sleep 5
                        ${ws}/bin/tidb-server --store tikv --path=127.0.0.1:${pd_port} -P ${tidb_port} -enable-binlog -status ${tidb_status_port} --log-file ./tidb.log &
                        sleep 5
                        """
                } else {
                    sh """
                        ${ws}/bin/tidb-server --store tikv --path=127.0.0.1:${pd_port} -P ${tidb_port} -status ${tidb_status_port} --log-file ./tidb.log &
                        sleep 5
                        """
                }
                sh """
                    sleep 30
                    """

                sh """
                    mysql -uroot -h 127.0.0.1 -P ${tidb_port} -e "UPDATE mysql.tidb SET VARIABLE_VALUE = '720h' WHERE VARIABLE_NAME = 'tikv_gc_life_time';"
                    """
                def tiflash_tcp_port = 9110 + i
                def tiflash_http_port = 8223 + i
                def tiflash_service_addr = 5030 + i
                def tiflash_metrics_port = 8234 + i
                sh """
                        cat > tiflash.toml << __EOF__
default_profile = "default"
display_name = "TiFlash"
listen_host = "0.0.0.0"
mark_cache_size = 5368709120
tmp_path = "tiflash.tmp"
path = "tiflash.data"
tcp_port = ${tiflash_tcp_port}
http_port = ${tiflash_http_port}

[flash]
tidb_status_addr = "127.0.0.1:${tidb_status_port}"
service_addr = "127.0.0.1:${tiflash_service_addr}"

[flash.flash_cluster]
cluster_manager_path = "${ws}/tiflash/flash_cluster_manager"
log = "logs/tiflash_cluster_manager.log"
master_ttl = 60
refresh_interval = 20
update_rule_interval = 5

[status]
metrics_port = ${tiflash_metrics_port}

[logger]
errorlog = "logs/tiflash_error.log"
log = "logs/tiflash.log"
count = 20
level = "debug"
size = "1000M"

[application]
runAsDaemon = true

[raft]
pd_addr = "127.0.0.1:${pd_port}"
storage_engine = "tmt"

[quotas]

[quotas.default]

[quotas.default.interval]
duration = 3600
errors = 0
execution_time = 0
queries = 0
read_rows = 0
result_rows = 0

[users]

[users.default]
password = ""
profile = "default"
quota = "default"

[users.default.networks]
ip = "::/0"

[users.readonly]
password = ""
profile = "readonly"
quota = "default"

[users.readonly.networks]
ip = "::/0"

[profiles]

[profiles.default]
load_balancing = "random"
max_memory_usage = 10000000000
use_uncompressed_cache = 0

[profiles.readonly]
readonly = 1
__EOF__
                        """
                sh """
                        LD_LIBRARY_PATH=${ws}/tiflash ${ws}/tiflash/tiflash server --config-file ./tiflash.toml &
                        sleep 5
                        """

            }

            def gen_sync_diff_conf = { source, target ->
                sh """
                    cat > sync_diff.toml << __EOF__
log-level = "warn"
check-thread-count = 4
sample-percent = 10

[[check-tables]]
schema = "test"
tables = ["~.*"]

[[source-db]]
    host = "127.0.0.1"
    port = ${source}
    user = "root"
    instance-id = "source-1"

[target-db]
    host = "127.0.0.1"
    port = ${target}
    user = "root"
    instance-id = "target-1"
__EOF__
                    """
            }

            stage('Start Clusters') {
                // strat TiDB cluster
                dir('cluster-source') {
                    start_tidb_cluster(0, true)
                }

                dir('cluster-cdc') {
                    start_tidb_cluster(1, false)
                    gen_sync_diff_conf(4000, 4001)
                    sh """
                        ${ws}/bin/cdc server --log-level=info --pd http://127.0.0.1:2379 &
                        ${ws}/bin/cdc cli changefeed create --pd http://127.0.0.1:2379 --sink-uri mysql://root@127.0.0.1:4001/?max-txn-row=5000
                        """
                }

                dir("cluster-binlog") {
                    sh """
                        cat > drainer.toml << __EOF__
addr = "127.0.0.1:8249"
pd-urls = "http://127.0.0.1:2379"

[syncer]
ignore-schemas = "INFORMATION_SCHEMA,PERFORMANCE_SCHEMA,mysql"

[syncer.to]
host = "127.0.0.1"
user = "root"
port = 4002
__EOF__
                        """
                    start_tidb_cluster(2, false)
                    gen_sync_diff_conf(4000, 4002)
                    sh """
                        ../bin/drainer --config drainer.toml -log-file drainer.log & 
                        """
                }
            }

            stage('Run TPCC Test') {
                sh """
                    ./bin/go-tpc tpcc --warehouses 4 prepare
                    ./bin/go-tpc tpcc --warehouses 4 --time 600s run
                    """
            }

            stage('BR Full Backup & Restore') {
                dir('cluster-br') {
                    start_tidb_cluster(3, false)
                    gen_sync_diff_conf(4000, 4003)
                    sh """
                        mkdir backup-full
                        ${ws}/bin/br backup full --pd 127.0.0.1:2379 -s "local://${ws}/cluster-br/backup-full"
                        ${ws}/bin/br restore full --pd 127.0.0.1:2409 -s "local://${ws}/cluster-br/backup-full"
                        ${ws}/bin/br validate decode --field end-version --pd 127.0.0.1:2379 -s local://${ws}/cluster-br/backup-full > backup_full_ts
                        ${ws}/bin/sync_diff_inspector -config ./sync_diff.toml
                        """
                }
            }

            stage('Addational Tpcc 1min') {
                sh"""
                    ./bin/go-tpc tpcc --warehouses 4 --time 600s run
                    """
            }

            stage('BR Delta Backup & Restore') {
                dir('cluster-br') {
                    sh """
                        mkdir backup-delta
                        last_ts=`cat backup_full_ts`
                        ../bin/br backup full --pd 127.0.0.1:2379 -s "local://${ws}/cluster-br/backup-delta" --lastbackupts \${last_ts}
                        ../bin/br restore full --pd 127.0.0.1:2409 -s "local://${ws}/cluster-br/backup-delta"
                        ../bin/sync_diff_inspector -config ./sync_diff.toml
                        """
                }
            }

            stage("Check Binlog") {
                dir("cluster-binlog") {
                    timeout(20) {
                        sh """
                            end_ts=`../bin/br validate decode --field end-version --pd 127.0.0.1:2379 -s local://${ws}/cluster-br/backup-delta`
                            binlog_ts=`../bin/binlogctl -pd-urls=http://127.0.0.1:2379  -cmd drainers | grep -E -o "MaxCommitTS: [0-9]+"  | awk '{print \$2}'`
                            while [[ "\$binlog_ts" -le "\$end_ts" ]]; do
                                sleep 5
                                binlog_ts=`../bin/binlogctl -pd-urls=http://127.0.0.1:2379  -cmd drainers | grep -E -o "MaxCommitTS: [0-9]+"  | awk '{print \$2}'`
                            done
                            """
                    }
                    sh """
                        ../bin/sync_diff_inspector -config ./sync_diff.toml
                        """
                }
            }

            stage("Check CDC") {
                dir("cluster-cdc") {
                    // wait cdc finish
                    //cdc_ts=`curl http://127.0.0.1:8300/debug/info | grep -E -o "checkpoint-ts.:[0-9]+" | cut -d: -f2`
                    //cdc_ts=`curl http://127.0.0.1:8300/debug/info | grep -E -o "CheckpointTs:[0-9]+" | cut -d: -f2`
                    timeout(120) {
                        sh """
                            end_ts=`../bin/br validate decode --field end-version --pd 127.0.0.1:2379 -s local://${ws}/cluster-br/backup-delta`
                            cdc_ts=`curl http://127.0.0.1:8300/debug/info | grep -E -o "CheckpointTs:[0-9]+" | cut -d: -f2`
                            while [[ "\$cdc_ts" -le "\$end_ts" ]]; do
                                sleep 5
                                cdc_ts=`curl http://127.0.0.1:8300/debug/info | grep -E -o "CheckpointTs:[0-9]+" | cut -d: -f2`
                                if [[ "\$cdc_ts" -le "\$end_ts" ]]; 
                                then 
                                    cdc_ts=`curl http://127.0.0.1:8300/debug/info | grep -E -o "checkpoint-ts.:[0-9]+" | cut -d: -f2`
                                fi
                            done
                            """
                    }
                    sh """
                        ../bin/sync_diff_inspector -config ./sync_diff.toml
                        """
                }
            }

        }
    }
}


// Start main
try {
    stage("x86") {
        stage("test") {
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



// mmonitor
// lightning
// pd
// tidb
// tidb-binlog
// tikv


properties([
        parameters([
                booleanParam(
                        defaultValue: true,
                        name: 'PUSH_TO_DOCKER_HUB',
                ),
                string(
                        defaultValue: '-1',
                        name: 'PIPELINE_BUILD_ID',
                        description: '',
                        trim: true
                )
        ]),
        pipelineTriggers([
                parameterizedCron('''
                    0 18 * * * % PUSH_TO_DOCKER_HUB=false
                ''')
        ])
])


final defaultPodYaml = '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: builder
    image: hub.pingcap.net/jenkins/centos7_golang-1.19:latest
    args: ["sleep", "infinity"]
    resources:
      requests:
        memory: "8Gi"
        cpu: "4"
      limits:
        memory: "8Gi"
        cpu: "4"
  nodeSelector:
    kubernetes.io/arch: amd64
'''

final NIGHTLY_RELEASE_TAG="v7.4.0-alpha"

def tidb_hash = ''
def br_hash = ''
def lightning_hash = ''
def tikv_hash = ''
def pd_hash = ''
def tidb_binlog_hash = ''



pipeline {
    agent {
        kubernetes {
            yaml defaultPodYaml
            defaultContainer 'builder'
        }
    }
    parameters {
        string(name: 'Revision', defaultValue: 'main', description: 'branch or commit hash')
    }
    stages {
        stage ("get commit hash") {
            agent {
                kubernetes {
                    yaml defaultPodYaml
                    defaultContainer 'builder'
                }
            }
            steps{
                script {
                    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
                    tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${NIGHTLY_RELEASE_TAG}/sha1").trim()
                    br_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/br/${NIGHTLY_RELEASE_TAG}/sha1").trim()
                    lightning_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/br/${NIGHTLY_RELEASE_TAG}/sha1").trim()
                    tidb_binlog_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb-binlog/${NIGHTLY_RELEASE_TAG}/sha1").trim()
                    tikv_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tikv/${NIGHTLY_RELEASE_TAG}/sha1").trim()
                    pd_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/pd/${NIGHTLY_RELEASE_TAG}/sha1").trim()
                    sh """
                    echo "tidb: ${tidb_sha1}"
                    echo "br: ${br_sha1}"
                    echo "lightning: ${lightning_sha1}"
                    echo "tidb-binlog: ${tidb_binlog_sha1}"
                    echo "tikv: ${tikv_sha1}"
                    echo "pd: ${pd_sha1}"
                    """
                }
            }
        }
    }
}
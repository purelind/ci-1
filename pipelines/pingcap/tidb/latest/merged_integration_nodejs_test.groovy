// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_integration_nodejs_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            steps {
                container(name: 'nodejs') {
                    sh label: 'Debug info', script: """
                        printenv
                        echo "-------------------------"
                        echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                    """
                }
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/PingCAP-QE/tidb-test/rev-${REFS.base_sha}", restoreKeys: ['git/PingCAP-QE/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:PingCAP-QE/tidb-test.git', 'tidb-test', REFS.base_ref, "", GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    container("nodejs") {
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || make'
                        sh label: 'download binary', script: """
                        chmod +x ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/*.sh
                        ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/download_pingcap_artifact.sh --pd=${REFS.base_ref} --tikv=${REFS.base_ref}
                        mv third_bin/* bin/
                        rm -rf bin/bin
                        ls -alh bin/
                        """
                    }
                    
                }
                dir('tidb-test') {
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                        container("nodejs") {
                            sh label: 'cache tidb-test', script: """
                            touch ws-${BUILD_TAG}
                            mkdir -p bin
                            cp -r ../tidb/bin/{pd,tidb,tikv}-server bin/ && chmod +x bin/*
                            ./bin/pd-server -V
                            ./bin/tikv-server -V
                            ./bin/tidb-server -V
                            """
                        }
                    }
                }
            }
        }
        stage('Node.js Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_DIR'
                        values 'prisma_test', 'typeorm_test', 'sequelize_test'
                    }
                }
                agent {
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'nodejs'
                    }
                }
                stages {
                    stage("Test") {
                        steps {
                            dir('tidb-test') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                                    sh """
                                        ls -alh bin/
                                        ./bin/pd-server -V
                                        ./bin/tikv-server -V
                                        ./bin/tidb-server -V
                                    """
                                    sh label: "${TEST_DIR} ", script: """#!/usr/bin/env bash
                                        export TIDB_SERVER_PATH="\$(pwd)/bin/tidb-server"
                                        export TIDB_TEST_STORE_NAME="${TEST_STORE}"
                                        echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                        bash ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/start_tikv.sh
                                        export TIKV_PATH="127.0.0.1:2379"

                                        cd \${TEST_DIR} && chmod +x *.sh && ./test.sh
                                    """
                                }
                            }
                        }
                        post{
                            failure {
                                script {
                                    println "Test failed, archive the log"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

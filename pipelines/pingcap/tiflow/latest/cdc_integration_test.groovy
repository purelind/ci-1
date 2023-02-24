// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
// @Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/latest/pod-cdc_integration_test.yaml'

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
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
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("tiflow") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tiflow/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tiflow/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: ghprbActualCommit ]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                        url: "https://github.com/${GIT_FULL_REPO_NAME}.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage("prepare") {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("third_party_download") {
                    sh label: "download third_party", script: """
                        cd ../tiflow
                        make cdc_prepare_test_binaries 
                        make cdc_check_third_party_binary
                        mkdir -p bin && mv ../tiflow/bin/* ./bin/
                    """
                }
                dir("tiflow") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tiflow/cdc-integration-test-binarys-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tiflow/rev-']) { 
                        sh label: "prepare", script: """
                            make clean
                            make integration_test_build kafka_consumer storage_consumer cdc
                            ls -alh ./bin
                        """
                    }
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tiflow-cdc") { 
                        sh label: "prepare", script: """
                            cp -r ../third_party_download/bin/* ./bin/
                            ls -alh ./bin
                        """
                    }

                }
            }
        }

        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_CASE'
                        values 'check', "build"
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'golang'
                    }
                } 
                stages {
                    stage("Test") {
                        options { timeout(time: 40, unit: 'MINUTES') }
                        environment { 
                            TICDC_CODECOV_TOKEN = credentials('codecov-token-tiflow') 
                            TICDC_COVERALLS_TOKEN = credentials('coveralls-token-tiflow')    
                        }
                        steps {
                            dir('tiflow') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tiflow-cdc") {
                                    sh label: "${TEST_CASE}", script: """
                                        rm -rf /tmp/tidb_cdc_test && mkdir -p /tmp/tidb_cdc_test
                                        make integration_test_mysql CASE="${TEST_CASE}
                                    """
                                }
                            }
                        }
                        post {
                            always {
                                junit(testResults: "**/tiflow/*-junit-report.xml", allowEmptyResults : true)  
                            }
                        }
                    }
                }
            }        
        }
    }
}

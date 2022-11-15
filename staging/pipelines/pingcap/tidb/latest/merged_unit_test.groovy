// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'staging/pipelines/pingcap/tidb/latest/pod-merged_unit_test.yaml'


pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    triggers {
        pollSCM 'H/10 * * * *'
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 40, unit: 'MINUTES')
        parallelsAlwaysFailFast()
        skipDefaultCheckout()
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
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-master", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: "master"]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/heads/*:refs/remotes/origin/*",
                                        url: "https://github.com/${GIT_FULL_REPO_NAME}.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage('Tests') {
            stages {
                stage("Test") {
                    options { timeout(time: 25, unit: 'MINUTES') }
                    environment {TIDB_CODECOV_TOKEN = credentials('codecov-token-tidb')}
                    steps {
                        dir("tidb") {
                        sh "git status"
                        sh """
                        ./build/jenkins_unit_test.sh
                        """
                        }     
                    }
                    post{
                        always {
                            junit(testResults: "**/bazel.xml")
                        }
                        success {
                            archiveArtifacts artifacts: 'tidb/test_coverage/bazel.xml', fingerprint: true
                                sh """
                                cp tidb/coverage.dat tidb/test_coverage/coverage.dat
                                cd tidb/test_coverage
                                curl -Os https://uploader.codecov.io/latest/linux/codecov
                                chmod +x codecov
                                ./codecov --file coverage.dat --token ${TIDB_CODECOV_TOKEN}
                                """
                        }
                    }
                }
            }      
        }
    }
}
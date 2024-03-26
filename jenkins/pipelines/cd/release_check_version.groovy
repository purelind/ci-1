
podYaml = """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: check
    image: hub.pingcap.net/jenkins/release-check-version:v20240326
    imagePullPolicy: Always
    args: ["sleep", "infinity"]
    env:
    - name: DOCKER_HOST
      value: tcp://localhost:2375
  - name: dockerd
    image: hub.pingcap.net/jenkins/docker:20.10.14-dind
    args: ["--registry-mirror=https://registry-mirror.pingcap.net"]
    env:
    - name: DOCKER_TLS_CERTDIR
      value: ""
    - name: DOCKER_HOST
      value: tcp://localhost:2375
    securityContext:
      privileged: true
    tty: true
    readinessProbe:
      exec:
        command: ["docker", "info"]
      initialDelaySeconds: 10
      failureThreshold: 6
"""

pipeline {
    parameters {
        booleanParam(
          defaultValue: true,
          description: 'Whether this is a release candidate build check',
          name: 'IS_RC_BUILD'
        )
        string(
            defaultValue: 'https://raw.githubusercontent.com/purelind/test-ci/main/components.json' ,
            name: 'COMPONENT_JSON_URL', 
            description: 'The URL of the component json file',
            trim: true
        )
    }
    agent none
    options {
        timeout(time: 40, unit: 'MINUTES')
    }
    stages {
        stage("MultiPlatform check") {
            parallel {
                stage('linux/amd64 images') {
                    agent {
                        kubernetes {
                            yaml podYaml
                            defaultContainer 'check'
                            nodeSelector "kubernetes.io/arch=amd64"
                        }
                    }
                    steps {
                        dir("release-check-version") {
                            script {
                                sh """
                                cd /app
                                tiup --version
                                python3 --version
                                docker version
                                python3 main.py image --components_url="${params.COMPONENT_JSON_URL}"
                                """
                            }
                        }
                    }
                }
                stage('linux/arm64 images') {
                    agent {
                        kubernetes {
                            yaml podYaml
                            defaultContainer 'check'
                            nodeSelector "kubernetes.io/arch=arm64"
                        }
                    }
                    steps {
                        dir("release-check-version") {
                            script {
                                sh """
                                cd /app
                                tiup --version
                                python3 --version
                                docker version
                                python3 main.py image --components_url="${params.COMPONENT_JSON_URL}"
                                """
                            }
                        }
                    }
                }


                stage('darwin/arm64 tiup') {
                    agent {
                        node {
                            label 'darwin && arm64'
                        }
                    }
                    steps {
                        dir("release-check-tiup") {  
                            deleteDir()
                            sh """
                                hostname
                                ifconfig | grep 172
                                git clone --branch purelind/add-release-check-version --depth 1 https://github.com/purelind/ci-1.git .
                                cd scripts/ops/release-check-version
                                python3 -m venv .venv
                                source .venv/bin/activate
                                pip install -r requirements.txt
                                export PATH=\$PATH:\$HOME/.tiup/bin
                                which tiup
                                python3 main.py tiup --components_url='https://raw.githubusercontent.com/purelind/test-ci/main/components.json' 
                            """
                        }
                        
                    }
                }
                stage('darwin/amd64 tiup') {
                    agent {
                        node {
                            label 'darwin && amd64'
                        }
                    }
                    steps {
                        dir("release-check-tiup") { 
                            deleteDir()
                            sh """
                                hostname
                                ifconfig | grep 172
                                git clone --branch purelind/add-release-check-version --depth 1 https://github.com/purelind/ci-1.git .
                                cd scripts/ops/release-check-version
                                python3 -m venv .venv
                                source .venv/bin/activate
                                pip install -r requirements.txt
                                export PATH=\$PATH:\$HOME/.tiup/bin
                                which tiup
                                python3 main.py tiup --components_url='https://raw.githubusercontent.com/purelind/test-ci/main/components.json' 
                            """
                        }
                    }
                }
            }
        }
    }
}

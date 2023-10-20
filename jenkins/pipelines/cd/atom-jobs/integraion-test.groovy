properties([
        parameters([
                string(
                        defaultValue: 'master',
                        name: 'TARGET_BRANCH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TRIGGER_REPO',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TRIGGER_COMMIT',
                        trim: true
                ),
        ])
])

podYaml = """
apiVersion: v1
kind: Pod
spec:
  securityContext:
    fsGroup: 1000
  containers:
    - name: golang
      image: "hub.pingcap.net/wangweizhen/tidb_image:go12120230809"
      securityContext:
        privileged: true
      tty: true
      resources:
        limits:
          memory: 32Gi
          cpu: "8"
      volumeMounts:
        - mountPath: /home/jenkins/.tidb/tmp
          name: bazel-out-merged
        - name: bazel-out-lower
          subPath: tidb/go1.19.2
          mountPath: /bazel-out-lower
        - name: bazel-out-overlay
          mountPath: /bazel-out-overlay
        - name: gocache
          mountPath: /share/.cache/go-build
        - name: gopathcache
          mountPath: /share/.go
        - mountPath: /share/.cache/bazel-repository-cache
          name: bazel-repository-cache
        - name: bazel-rc
          mountPath: /data/
          readOnly: true
        - name: containerinfo
          mountPath: /etc/containerinfo
      lifecycle:
        postStart:
          exec:
            command:
              - /bin/sh
              - /data/bazel-prepare-in-container.sh
    - name: net-tool
      image: wbitt/network-multitool
      tty: true
      resources:
        limits:
          memory: 128Mi
          cpu: 100m
    - name: report
      image: hub.pingcap.net/jenkins/python3-requests:latest
      tty: true
      resources:
        limits:
          memory: 256Mi
          cpu: 100m
  volumes:
    - name: gopathcache
      persistentVolumeClaim:
        claimName: gopathcache
    - name: gocache
      persistentVolumeClaim:
        claimName: gocache
    - name: bazel-out-lower
      persistentVolumeClaim:
        claimName: bazel-out-data
    - name: bazel-out-overlay
      emptyDir: {}
    - name: bazel-out-merged
      emptyDir: {}
    - name: bazel-repository-cache
      persistentVolumeClaim:
        claimName: bazel-repository-cache
    - name: bazel-rc
      secret:
        secretName: bazel
    - name: containerinfo
      downwardAPI:
        items:
          - path: cpu_limit
            resourceFieldRef:
              containerName: golang
              resource: limits.cpu
          - path: cpu_request
            resourceFieldRef:
              containerName: golang
              resource: requests.cpu
          - path: mem_limit
            resourceFieldRef:
              containerName: golang
              resource: limits.memory
          - path: mem_request
            resourceFieldRef:
              containerName: golang
              resource: requests.memory
  affinity:
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
          - matchExpressions:
              - key: kubernetes.io/arch
                operator: In
                values:
                  - amd64
              - key: ci-nvme-high-performance
                operator: In
                values:
                  - "true"
"""


final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final FILESERVER_URL = 'http://fileserver.pingcap.net/'
// final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-ghpr_check2.yaml'

final tikv_sha1_url = "${FILESERVER_URL}/download/refs/pingcap/tikv/${TARGET_BRANCH}/sha1"
final pd_sha1_url = "${FILESERVER_URL}/download/refs/pingcap/pd/${TARGET_BRANCH}/sha1"
final tidb_sha1_url = "${FILESERVER_URL}/download/refs/pingcap/tidb/${TARGET_BRANCH}/sha1"

final tikv_sha1_verify_url = "${FILESERVER_URL}/download/refs/pingcap/tikv/${TARGET_BRANCH}/sha1.verify"
final pd_sha1_verify_url = "${FILESERVER_URL}/download/refs/pingcap/pd/${TARGET_BRANCH}/sha1.verify"
final tidb_sha1_verify_url = "${FILESERVER_URL}/download/refs/pingcap/tidb/${TARGET_BRANCH}/sha1.verify"

tikv_commit_sha = ""
pd_commit_sha = ""
tidb_commit_sha = ""
tikv_download_url = ""
pd_download_url = ""
tidb_download_url = ""

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            // yamlFile POD_TEMPLATE_FILE
            yaml podYaml
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
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
        stage("Download") {
            steps {
                dir('download') {
                    // TODO: download binary from fileserver against target branch
                    sh """
                    tikv_commit_sha=\$(curl -s ${tikv_sha1_url})
                    pd_commit_sha=\$(curl -s ${pd_sha1_url})
                    tidb_commit_sha=\$(curl -s ${tidb_sha1_url})
                    tikv_download_url="${FILESERVER_URL}/download/refs/pingcap/tikv/${TARGET_BRANCH}/\${tikv_commit_sha}/centos7/tikv-server.tar.gz"
                    pd_download_url="${FILESERVER_URL}/download/refs/pingcap/pd/${TARGET_BRANCH}/\${pd_commit_sha}/centos7/pd-server.tar.gz"
                    tidb_download_url="${FILESERVER_URL}/download/refs/pingcap/tidb/${TARGET_BRANCH}/\${tidb_commit_sha}/centos7/tidb-server.tar.gz"

                    mkdir -p tmp
                    mkdir -p third_bin
                    wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 -O "tmp/tikv-server.tar.gz" \${tikv_download_url}
                    tar -xz -C third_bin bin/tikv-server -f tmp/tikv-server.tar.gz && mv third_bin/bin/tikv-server third_bin/
                    wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 -O "tmp/pd-server.tar.gz" \${pd_download_url}
                    tar -xz -C third_bin 'bin/*' -f tmp/pd-server.tar.gz && mv third_bin/bin/* third_bin/
                    rm -rf third_bin/bin
                    ls -alh third_bin
                    """
                }
            }
        }
        stage('Checkout') {
            steps {
                dir('tidb') {
                    // checkout tidb src code as target branch
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                checkout(
                                    changelog: false,
                                    poll: true,
                                    scm: [
                                        $class: 'GitSCM',
                                        branches: [[name: TARGET_BRANCH]],
                                        doGenerateSubmoduleConfigurations: false,
                                        extensions: [
                                            [$class: 'PruneStaleBranch'],
                                            [$class: 'CleanBeforeCheckout'],
                                            [$class: 'CloneOption', timeout: 10],
                                        ], 
                                        submoduleCfg: [],
                                        userRemoteConfigs: [[
                                            credentialsId: "",
                                            refspec: "+refs/heads/$TARGET_BRANCH:refs/remotes/origin/$TARGET_BRANCH",
                                            url: "https://github.com/pingcap/tidb.git",
                                        ]]
                                    ]
                                )
                                sh label: "checkout tidb code", script: """
                                    git checkout ${TARGET_BRANCH}
                                    git checkout ${tidb_commit_sha}
                                """
                            }
                        }
                    }
                }
            }
        }
        stage("Prepare") {
            steps {
                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}") { 
                    dir('tidb') {
                        // TODO: download binary from fileserver against target branch
                        sh """
                        make
                        """
                        sh label: "prepare all binaries", script: """
                        touch rev-${tidb_commit_sha}
                        cp -f ../download/third_bin/* bin/
                        chmod +x bin/*
                        ./bin/tidb-server -V
                        ./bin/pd-server -V
                        ./bin/tikv-server -V
                        """
                    }
                }
            }
        }
        stage('Checks') {
            matrix {
                axes {
                    axis {
                        name 'SCRIPT_AND_ARGS'
                        values(
                            'integrationtest_with_tikv.sh y',
                            'integrationtest_with_tikv.sh n',
                            'run_real_tikv_tests.sh bazel_brietest',
                            'run_real_tikv_tests.sh bazel_pessimistictest',
                            'run_real_tikv_tests.sh bazel_sessiontest',
                            'run_real_tikv_tests.sh bazel_statisticstest',
                            'run_real_tikv_tests.sh bazel_txntest',
                            'run_real_tikv_tests.sh bazel_addindextest',
                            'run_real_tikv_tests.sh bazel_importintotest',
                            'run_real_tikv_tests.sh bazel_importintotest2',
                            'run_real_tikv_tests.sh bazel_importintotest3',
                            'run_real_tikv_tests.sh bazel_importintotest4',
                        )
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        defaultContainer 'golang'
                        // yamlFile POD_TEMPLATE_FILE
                        yaml podYaml
                    }
                }
                stages {
                    stage('Test')  {
                        options { timeout(time: 60, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}") {
                                    sh "ls -l rev-${tidb_commit_sha}" // will fail when not found in cache or no cached.
                                }
                                sh 'chmod +x ../scripts/pingcap/tidb/*.sh'
                                sh """
                                sed -i 's|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=/share/.cache/bazel-repository-cache|g' Makefile.common
                                git diff .
                                git status
                                """
                                sh "${WORKSPACE}/scripts/pingcap/tidb/${SCRIPT_AND_ARGS}"
                            }
                        }
                        post {
                            always {
                                dir('tidb') {
                                    // archive test report to Jenkins.
                                    junit(testResults: "**/bazel.xml", allowEmptyResults: true)
                                }
                            }
                            failure {
                                // TODO 修正收集日志的路径，目前这里存在问题，收集不到正确的路径
                                dir("dir") {
                                    archiveArtifacts(artifacts: 'pd*.log, tikv*.log, integration-test.out', allowEmptyArchive: true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        success {
            // Send lark notification
            // update sha1.verify file
            sh """
            echo ${tikv_commit_sha} > tikv.sha1.verify
            echo ${pd_commit_sha} > pd.sha1.verify
            echo ${tidb_commit_sha} > tidb.sha1.verify
            # curl -F ${tikv_sha1_verify_url}=@tikv.sha1.verify ${FILE_SERVER_URL}/upload
            # curl -F ${pd_sha1_verify_url}=@pd.sha1.verify ${FILE_SERVER_URL}/upload
            # curl -F ${tidb_sha1_verify_url}=@tidb.sha1.verify ${FILE_SERVER_URL}/upload
            """
        }

        failure {
            // Send lark notification
            
        }
    }
}

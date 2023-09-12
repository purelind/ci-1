// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflash"
final GIT_FULL_REPO_NAME = 'pingcap/tiflash'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflash/latest/pod-pull_build.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final dependency_dir = "/home/jenkins/agent/dependency"
Boolean proxy_cache_ready = false
String proxy_commit_hash = null

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
        timeout(time: 40, unit: 'MINUTES')
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
                dir("tiflash") {
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS, withSubmodule=true)
                            }
                            dir("contrib/tiflash-proxy") {
                                proxy_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H"').trim()
                            }
                        }
                    }
                }
            }
        }
        stage("Build") {
            stages("Prepare tools") {
                parallel {
                    stage("CCache") {
                        sh label: "install ccache", script: """
                            if ! command -v ccache &> /dev/null; then
                                echo "ccache not found! Installing..."
                                rpm -Uvh '${dependency_dir}/ccache.x86_64.rpm'
                            else
                                echo "ccache is already installed!"
                            fi
                        """
                    }
                    stage("Cmake") {
                        sh label: "install cmake3", script: """
                            if ! command -v cmake &> /dev/null; then
                                echo "cmake not found! Installing..."
                                sh ${dependency_dir}/cmake-3.22.3-linux-${arch_id}.sh --prefix=/usr --skip-license --exclude-subdir
                            else
                                echo "cmake is already installed!"
                            fi
                        """
                    }
                    stage("Clang-Format") {
                        sh label: "install clang-format", script: """
                            if ! command -v clang-format &> /dev/null; then
                                echo "clang-format not found! Installing..."
                                cp '${dependency_dir}/clang-format-12' '/usr/local/bin/clang-format'
                                chmod +x '/usr/local/bin/clang-format'
                            else
                                echo "clang-format is already installed!"
                            fi
                        """
                    }
                    stage("Clang-Format-15") {
                        sh label: "install clang-format-15", script: """
                            if ! command -v clang-format-15 &> /dev/null; then
                                echo "clang-format-15 not found! Installing..."
                                cp '${dependency_dir}/clang-format-15' '/usr/local/bin/clang-format-15'
                                chmod +x '/usr/local/bin/clang-format-15'
                            else
                                echo "clang-format-15 is already installed!"
                            fi
                        """

                    }
                    stage("Clang-Tidy") {
                        sh label: "install clang-tidy", script: """
                            if ! command -v clang-tidy &> /dev/null; then
                                echo "clang-tidy not found! Installing..."
                                cp '${dependency_dir}/clang-tidy-12' '/usr/local/bin/clang-tidy'
                                chmod +x '/usr/local/bin/clang-tidy'
                                cp '${dependency_dir}/lib64-clang-12-include.tar.gz' '/tmp/lib64-clang-12-include.tar.gz'
                                cd /tmp && tar zxf lib64-clang-12-include.tar.gz
                            else
                                echo "clang-tidy is already installed!"
                            fi
                        """
                    }
                    stage("Coverage") {
                        sh label: "install gcovr", script: """
                            if ! command -v gcovr &> /dev/null; then
                                echo "lcov not found! Installing..."
                                cp '${dependency_dir}/gcovr.tar' '/tmp/'
                                cd /tmp
                                tar xvf gcovr.tar && rm -rf gcovr.tar
                                ln -sf /tmp/gcovr/gcovr /usr/bin/gcovr
                            else
                                echo "lcov is already installed!"
                            fi
                        """
                    }
                }
            }
            stages("Prepare Cache") {
                parallel {
                    stage("CCache") {
                        // TODO: need adjust "master" against different target branch
                        def ccache_tag = "tiflash-amd64-linux-llvm-debug-master-failpoint"
                        def ccache_source = "/home/jenkins/agent/ccache/${ccache_tag}.tar"
                        dir("tiflash") {
                            // TODO: need to refactor this part, use shell script to do the job
                            // pass the ccache_source & cache_source to shell script by env
                            if (fileExists(ccache_source)) {
                                echo "ccache found"
                                sh """
                                cd /tmp
                                cp ${ccache_source} ccache.tar
                                tar -xf ccache.tar
                                cd -
                                """
                            } else {
                                echo "ccache not found"
                            }
                            sh """
                            ccache -o cache_dir="/tmp/.ccache"
                            ccache -o max_size=2G
                            ccache -o limit_multiple=0.99
                            ccache -o hash_dir=false
                            ccache -o compression=true
                            ccache -o compression_level=6
                            ccache -o read_only=false
                            ccache -z
                            """
                        }
                    }
                    stage("Proxy Cache") {
                        def proxy_suffix = "amd64-linux-llvm"
                        def cache_source = "/home/jenkins/agent/proxy-cache/${proxy_commit_hash}-${proxy_suffix}"
                        // TODO: need to refactor this part, use shell script to do the job
                        // cache proxy lib by pvc or nfs or fileserver ?
                        if (fileExists(cache_source)) {
                            echo "proxy cache found"
                            dir("tiflash") {
                                sh """
                                cp ${cache_source} libtiflash_proxy.so
                                chmod +x libtiflash_proxy.so
                                """
                            }
                        } else {
                            echo "proxy cache not found"
                        }
                        sh label: "link cargo cache", script: """
                            mkdir -p ~/.cargo/registry
                            mkdir -p ~/.cargo/git
                            mkdir -p /home/jenkins/agent/rust/registry/cache
                            mkdir -p /home/jenkins/agent/rust/registry/index
                            mkdir -p /home/jenkins/agent/rust/git/db
                            mkdir -p /home/jenkins/agent/rust/git/checkouts

                            rm -rf ~/.cargo/registry/cache && ln -s /home/jenkins/agent/rust/registry/cache ~/.cargo/registry/cache
                            rm -rf ~/.cargo/registry/index && ln -s /home/jenkins/agent/rust/registry/index ~/.cargo/registry/index
                            rm -rf ~/.cargo/git/db && ln -s /home/jenkins/agent/rust/git/db ~/.cargo/git/db
                            rm -rf ~/.cargo/git/checkouts && ln -s /home/jenkins/agent/rust/git/checkouts ~/.cargo/git/checkouts

                            rm -rf ~/.rustup/tmp
                            rm -rf ~/.rustup/toolchains
                            mkdir -p /home/jenkins/agent/rust/rustup-env/tmp
                            mkdir -p /home/jenkins/agent/rust/rustup-env/toolchains
                            ln -s /home/jenkins/agent/rust/rustup-env/tmp ~/.rustup/tmp
                            ln -s /home/jenkins/agent/rust/rustup-env/toolchains ~/.rustup/toolchains
                        """
                    }
                }
            }
            stages("Build Dependency and Utils") {
                stage("Cluster Manage") { 
                    // NOTE: cluster_manager is deprecated since release-6.0 (include)
                    echo "cluster_manager is deprecated"
                    // dir("tiflash/cluster_manage"){
                    //     sh label: "build cluster manage", script: """
                    //         sh release.sh
                    //     """
                    // }
                    // def install_dir = "${WORKSPACE}/install/tiflash"
                    // sh label: "mv cluster manage", script: """
                    //     mkdir -p ${install_dir}
                    //     cp -rf tiflash/cluster_manage/dist/flash_cluster_manager ${install_dir}/flash_cluster_manager
                    // """
                }
                stage("TiFlash Proxy") {
                    if (proxy_cache_ready) {
                        echo "skip becuase of cache"
                    } else {
                        echo "proxy cache not ready"
                    }
                    // NOTE: we don't need to build tiflash proxy in this pipeline on master branch
                    // skip because proxy build is integrated if toolchain is llvm

                    // dir("tiflash/contrib/tiflash-proxy") {
                    //     sh label: "build tiflash proxy", script: """
                    //         env ENGINE_LABEL_VALUE=tiflash make release
                    //         mkdir -p '${WORKSPACE}/tiflash/libs/libtiflash-proxy'
                    //         cp target/release/libtiflash_proxy.so ${WORKSPACE}/tiflash/libs/libtiflash-proxy/libtiflash_proxy.so
                    //     """
                    // }
                }
            }
            stage("Configure Project") {
                def toolchain = getToolchain(repo_path)
                def generator = 'Ninja'
                def coverage_flag = ""
                def diagnostic_flag = ""
                def compatible_flag = ""
                def openssl_root_dir = ""
                def prebuilt_dir_flag = "-DPREBUILT_LIBS_ROOT='${WORKSPACE}/tiflash/contrib/tiflash-proxy/'"
                if (proxy_cache_ready) {
                    // only for toolchain is llvm
                    prebuilt_dir_flag = "-DPREBUILT_LIBS_ROOT='${WORKSPACE}/tiflash/contrib/tiflash-proxy/'"
                    sh """
                    mkdir -p ${WORKSPACE}/tiflash/contrib/tiflash-proxy/target/release
                    cp ${WORKSPACE}/tiflash/libs/libtiflash-proxy/libtiflash_proxy.so ${WORKSPACE}/tiflash/contrib/tiflash-proxy/target/releasev
                    """
                }
                // create build dir and install dir
                sh label: "create build & install dir", script: """
                mkdir -p ${WORKSPACE}/build
                mkdir -p ${WORKSPACE}/install/tiflash
                """
                dir("${WORKSPACE}/build") {
                    sh label: "configure project", script: """
                    cmake '${WORKSPACE}/tiflash' ${prebuilt_dir_flag} ${coverage_flag} ${diagnostic_flag} ${compatible_flag} ${openssl_root_dir} \\
                        -G '${generator}' \\
                        -DENABLE_FAILPOINTS=true \\
                        -DCMAKE_BUILD_TYPE=debug \\
                        -DCMAKE_PREFIX_PATH='/usr/local' \\
                        -DCMAKE_INSTALL_PREFIX=${WORKSPACE}/install/tiflash \\
                        -DENABLE_TESTS=false \\
                        -DUSE_CCACHE=true \\
                        -DDEBUG_WITHOUT_DEBUG_INFO=true \\
                        -DUSE_INTERNAL_TIFLASH_PROXY=${!proxy_cache_ready} \\
                        -DRUN_HAVE_STD_REGEX=0 \\
                    """
                }
            }
            stage("Format Check") {
                def target_branch = "master"  // TODO: need to adjust target branch
                def diff_flag = "--dump_diff_files_to '/tmp/tiflash-diff-files.json'"
                def repo_path = "${WORKSPACE}/tiflash"
                if (!fileExists("${WORKSPACE}/tiflash/format-diff.py")) {
                    echo "skipped because this branch does not support format"
                    return
                }
                // TODO: need to check format-diff.py for more details
                dir("${WORKSPACE}/tiflash") {
                    sh """
                    python3 \\
                        ${WORKSPACE}/tiflash/format-diff.py ${diff_flag} \\
                        --repo_path '${WORKSPACE}/tiflash' \\
                        --check_formatted \\
                        --diff_from \$(git merge-base origin/${target_branch} HEAD)
                    """
                }

            }
            stages("Build TiFlash") {
                def toolchain = getToolchain(repo_path)
                def targets = "tiflash"
                // need to refactor this part, use shell script to handle this
                dir(build_dir) {
                    if (targets.contains('page_ctl') && sh(returnStatus: true, script: 'cmake --build . --target help | grep page_ctl') != 0) {
                        echo "remove page_ctl from target list"
                        targets = targets.replaceAll('page_ctl', '')
                    }
                    if (targets.contains('page_stress_testing') && sh(returnStatus: true, script: 'cmake --build . --target help | grep page_stress_testing') != 0) {
                        echo "remove page_stress_testing from target list"
                        targets = targets.replaceAll('page_stress_testing', '')
                    }
                    if (targets.contains('gtests_libdaemon') && sh(returnStatus: true, script: 'cmake --build . --target help | grep gtests_libdaemon') != 0) {
                        echo "remove gtests_libdaemon from target list"
                        targets = targets.replaceAll('gtests_libdaemon', '')
                    }
                }
                sh """
                cmake --build '${WORKSPACE}/build' --target ${targets} --parallel 12
                """
                sh """
                cmake --install '${WORKSPACE}/build' --component=tiflash-release --prefix='${WORKSPACE}/install/tiflash'
                """
            }
            stages("License check") {
                dir("${WORKSPACE}/tiflash") {
                    sh label: "license header check", script: """
                        echo "license check"
                        if [[ -f .github/licenserc.yml ]]; then
                            wget -q -O license-eye http://fileserver.pingcap.net/download/cicd/ci-tools/license-eye_v0.4.0
                            chmod +x license-eye
                            ./license-eye -c .github/licenserc.yml header check
                        else
                            echo "skip license check"
                            exit 0
                        fi
                    """
                }
            }
            stages("Post Build") {
                stage("Static Analysis"){
                    def generator = "Ninja"
                    def include_flag = ""
                    def repo_path = "${WORKSPACE}/tiflash"
                    def fix_compile_commands = "${repo_path}/release-centos7-llvm/scripts/fix_compile_commands.py"
                    def run_clang_tidy = "${repo_path}/release-centos7-llvm/scripts/run-clang-tidy.py"
                    def build_dir = "${WORKSPACE}/build"
                    dir(build_dir) {
                        sh """
                        NPROC=\$(nproc || grep -c ^processor /proc/cpuinfo || echo '1')
                        cmake "${repo_path}" \\
                            -DENABLE_TESTS=false \\
                            -DCMAKE_BUILD_TYPE=Debug \\
                            -DUSE_CCACHE=OFF \\
                            -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \\
                            -DRUN_HAVE_STD_REGEX=0 \\
                            -G '${generator}'
                        python3 ${fix_compile_commands} ${include_flag} \\
                            --file_path=compile_commands.json \\
                            --load_diff_files_from "/tmp/tiflash-diff-files.json"
                        python3 ${run_clang_tidy} -p \$(realpath .) -j \$NPROC --files ".*/tiflash/dbms/*"
                        """
                    }
                }
                stage("Upload Build Artifacts") {
                    def install_dir = "${WORKSPACE}/install/tiflash"
                    def basename = sh(returnStdout: true, script: "basename '${install_dir}'").trim()
                    dir("${install_dir}/../") {
                        sh """
                        tar -czf '${basename}.tar.gz' '${basename}'
                        """
                        archiveArtifacts artifacts: "${basename}.tar.gz"
                    }
                }
            }
        }

        stage("Integration test") {
            echo "start tests"
        }
    }
}

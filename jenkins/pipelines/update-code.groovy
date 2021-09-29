// make new cache folder named with date
// copy all repo from current cache folder
// pull to update repo in new cache folder
// tar repo and touch md5sum file
// update softlink

node("ci-admin-utils") {
    Date date = new Date()
    String datePart = date.format("yyyyMMdd")
    String timePart = date.format("HH:mm:ss")

    println "datePart : " + datePart + "\ttimePart : " + timePart

    def repoPathMap = [:]
    def codeArchiveDir = "git-archive"
    def codeCurrentDir = "git"

    stage('Collect info') {
        container("golang") {
            sh "ls -ld /nfs/cache/${codeCurrentDir}"
            dir("/nfs/cache/${codeCurrentDir}") {
                def files = findFiles()
                def workspace = pwd()
                files.each { f ->
                    if (f.directory) {
                        echo "This is dirctory: ${f.name}"
                        if (fileExists("${f.name}/.git/config")) {
                            echo "This is a valid git repo"
                            repoPathMap[f.name] = "${workspace}/${f.name}"
                        } else {
                            echo "This is invalid git repo. Skip..."
                        }
                    }
                }
            }
        }
    }

    stage('Copy') {
        res = sh(script: "test -d /nfs/cache/${codeArchiveDir}/${datePart} && echo '1' || echo '0' ", returnStdout: true).trim()
        if (res == '1') {
            echo "dir already exist, quit job in case of delete current using code cache dir"
            error("Build failed because of dir exist now: /nfs/cache/git-archive/${datePart}")
        }
        dir("/nfs/cache/${codeArchiveDir}/${datePart}") {
            repoPathMap.each { entry ->
                sh """
                    cp -R ${entry.value} ./ && [ -d ${entry.key}/.git ]
                """
                dir("${entry.key}") {
                    GIT_ORIGIN_URL = sh (
                            script: 'git config --get remote.origin.url',
                            returnStdout: true
                    ).trim()
                    GIT_DEFAULT_BRANCH = sh (
                            script: 'git remote show origin | grep "HEAD branch" | cut -d ":" -f 2\n',
                            returnStdout: true
                    ).trim()

                    echo "Git origin url: ${GIT_ORIGIN_URL}"
                    echo "Git default branch: ${GIT_DEFAULT_BRANCH}"
                    if (entry.key in ["tiflash", "tics"] ) {
                        checkout([$class: 'GitSCM',
                                  branches: [[name: '*/master']],
                                  doGenerateSubmoduleConfigurations: false,
                                  extensions: [
                                          [$class             : 'SubmoduleOption',
                                           disableSubmodules  : false,
                                           parentCredentials  : true,
                                           recursiveSubmodules: true,
                                           trackingSubmodules : false,
                                           reference          : ''],
                                          [$class: 'PruneStaleBranch'],
                                          [$class: 'CleanBeforeCheckout'],
                                  ],
                                  submoduleCfg: [],
                                  userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                       refspec: '+refs/heads/*:refs/remotes/origin/*',
                                                       url: "git@github.com:pingcap/${entry.key}.git"]]])
                    } else {
                        if (GIT_ORIGIN_URL.startsWith("git")) {
                            checkout changelog: false, poll: true,
                                    scm: [$class                           : 'GitSCM',
                                          branches                         : [[name: '*/master']],
                                          doGenerateSubmoduleConfigurations: false,
                                          extensions                       : [[$class: 'PruneStaleBranch'],
                                                                              [$class: 'CleanBeforeCheckout']],
                                          submoduleCfg                     : [],
                                          userRemoteConfigs                : [[credentialsId: 'github-sre-bot-ssh',
                                                                               refspec      : '+refs/heads/*:refs/remotes/origin/*',
                                                                               url          : "git@github.com:pingcap/${entry.key}.git"]]]
                        } else {
                            sh """
                            pwd
                            rm -f ./git/index.lock && git clean -fnd && git checkout . && git checkout ${GIT_DEFAULT_BRANCH}  && git pull --all
                            """
                        }
                    }
                }
                dir("${entry.key}@tmp") {
                    deleteDir()
                }
                sh """
                    tar -czf src-${entry.key}.tar.gz ${entry.key}
                    md5sum src-${entry.key}.tar.gz > src-${entry.key}.tar.gz.md5sum
                """
            }
        }
        dir("/nfs/cache/${codeArchiveDir}/${datePart}@tmp") {
            deleteDir()
        }
    }

    stage('Update softlink') {
        sh "cd /nfs/cache && ln -sfn ${codeArchiveDir}/${datePart}  ${codeCurrentDir}"
    }

}
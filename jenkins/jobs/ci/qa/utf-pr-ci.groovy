// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('utf-pr-ci') {
    logRotator {
        daysToKeep(30)
    }
    parameters {
        string('EXTRA_ARGS')
    }
    properties {
        githubProjectUrl("https://github.com/pingcap/automated-tests/")
        pipelineTriggers {
            triggers {
                ghprbTrigger {
                    cron('H/5 * * * *')
                    gitHubAuthId('8b25795b-a680-4dce-9904-89ef40d73159')
                    triggerPhrase('.*/run.*')
                    onlyTriggerPhrase(false)
                    skipBuildPhrase(".*skip-ci.*")
                    buildDescTemplate('PR #$pullId: $abbrTitle\n$url')
                    orgslist("pingcap")
                    // ignore when only those file changed.(
                    //   multi line regex
                    // excludedRegions('.*\\.md')
                    excludedRegions('') // current the context is required in github branch protection.

                    blackListLabels("")
                    whiteListLabels("")
                    adminlist("")
                    blackListCommitAuthor("")
                    includedRegions("")
                    commentFilePath("")

                    allowMembersOfWhitelistedOrgsAsAdmin(true)
                    permitAll(true)
                    useGitHubHooks(true)
                    displayBuildErrorsOnDownstreamBuilds(false)
                    autoCloseFailedPullRequests(false)

                    // useless, but can not delete.
                    commitStatusContext("--none--")
                    msgSuccess("--none--")
                    msgFailure("--none--")

                    extensions {
                        ghprbCancelBuildsOnUpdate { overrideGlobal(true) }
                        ghprbSimpleStatus {
                            commitStatusContext("jenkins-ci-automated-tests/utf-pr-ci")
                            statusUrl('${RUN_DISPLAY_URL}')
                            startedStatus("Jenkins job is running.")
                            triggeredStatus("Jenkins job triggered.")
                            addTestResults(false)
                            showMatrixStatus(false)
                        }
                    }
                }
            }
        }
    }
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("jenkins/pipelines/qa/utf/utf-pr-ci.groovy")
            scm {
                git{
                    remote {
                        url('https://github.com/PingCAP-QE/ci.git')
                    }
                    branch('main')
                    extensions {
                        cloneOptions {
                            depth(1)
                            shallow(true)
                            timeout(5)
                        } 
                    }
                }
            }
        }
    }
}
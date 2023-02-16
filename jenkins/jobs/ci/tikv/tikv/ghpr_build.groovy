// REF: https://<your-jenkins-server>/plugin/job-dsl/api-viewer/index.html
pipelineJob('tikv_ghpr_build') {
    logRotator {
        daysToKeep(90)
        numToKeep(1000)
    }
    parameters {
        stringParam{
            name('ghprbActualCommit')
            trim(true)
        }
        stringParam{
            name('ghprbPullId')
            trim(true)
        }
        stringParam{
            name('ghprbPullTitle')
            trim(true)
        }
        stringParam{
            name('ghprbPullLink')
            trim(true)
        }
        stringParam{
            name('ghprbPullDescription')
            trim(true)
        }
        stringParam{
            name('ghprbCommentBody')
            trim(true)
        }
        stringParam{
            name('ghprbTargetBranch')
            defaultValue('master')
            trim(true)
        }
        booleanParam{
            name('notcomment')
            defaultValue(true)
        }
    }
    properties {
        // priority(0) // 0 fast than 1
        githubProjectUrl("https://github.com/tikv/tikv/")
        pipelineTriggers {
            triggers {
                ghprbTrigger {
                    cron('H/5 * * * *')
                    gitHubAuthId('a6f8c5ac-6082-4ad1-b84d-562cc1c37682')

                    triggerPhrase('.*/(build|rebuild|run-build).*')
                    onlyTriggerPhrase(true)
                    skipBuildPhrase(".*skip-ci.*")
                    buildDescTemplate('PR #$pullId: $abbrTitle\n$url')
                    whitelist('')
                    orgslist('pingcap tikv')
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
                            commitStatusContext('idc-jenkins-ci-tikv/build')
                            statusUrl('${RUN_DISPLAY_URL}')
                            startedStatus('Jenkins job is running.')
                            triggeredStatus('Jenkins job triggered.')
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
            scriptPath('jenkins/pipelines/ci/tikv/tikv_ghpr_build.groovy')
            scm {
                git{
                    remote {
                        url('git@github.com:PingCAP-QE/ci.git')
                        credentials('github-sre-bot-ssh')
                    }
                    branch('main')
                }
            }
        }
    }
}


properties([
        parameters([
                string(defaultValue: '',name: 'ORG_AND_REPO',trim: true),
                string(defaultValue: '',name: 'FEATURE_BRANCH',trim: true),
                string(defaultValue: '',name: 'FEISHU_WEBHOOK_ID',trim: true),
        ]),
        pipelineTriggers([
                parameterizedCron('''
                0 12 * * * % ORG_AND_REPO=purelind/test-ci;FEATURE_BRANCH=cool_feature;FEISHU_WEBHOOK_ID=test-cd-feishu-webhook
            ''')
        ])
])


def run_with_lightweight_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest'
    pod_go_docker_image = "hub.pingcap.net/wulifu/curl:latest"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'build', alwaysPullImage: true,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '100m', resourceRequestMemory: '1Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                            
                    )
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}


HEAD_BRANCH = "master"
commit_message = "Merge remote-tracking branch 'upstream/master' into ${FEATURE_BRANCH}"
http_code_success = "201"
http_code_fail = "400"
http_code_conflict = "409"

def taskStartTimeInMillis = System.currentTimeMillis()
try {
run_with_lightweight_pod{
    container("build") {
        withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
            script = """
                output=\$(curl -s -o response.txt -w "%{http_code}" --location --request POST "https://api.github.com/repos/${ORG_AND_REPO}/merges" \
                    --header "Authorization: token ${TOKEN}" \
                    --header 'Content-Type: application/json' \
                    --data-raw '{
                        "base": "${FEATURE_BRANCH}",
                        "head": "${HEAD_BRANCH}",
                        "commit_message": "${commit_message}"
                    }')
                echo "response: \${output}"
                cat response.txt
                ls -alh 

                if [ "\$output" = "${http_code_success}" ]; then
                    echo "Successfully merged ${HEAD_BRANCH} into ${FEATURE_BRANCH}"
                elif [ "\$output" = "409" ]; then
                    echo "Conflict detected, please resolve it manually"
                    exit 1
                elif [ "\$output" = "204" ]; then
                    echo "Skip, No content to merge"
                    exit 3
                else
                    echo "Failed to merge cool_feature into main"
                    exit 2
                fi
            """
            def runShellstatusCode = sh script:script, returnStatus:true
            archiveArtifacts artifacts: 'response.txt', fingerprint: true
            if (runShellstatusCode == 0) {
                echo "Successfully merged ${HEAD_BRANCH} into ${FEATURE_BRANCH}"
                currentBuild.description = "Successfully merged ${HEAD_BRANCH} into ${FEATURE_BRANCH}"
                currentBuild.result = "SUCCESS"
            } else if (runShellstatusCode == 1) {
                echo "Conflict detected, please resolve it manually"
                currentBuild.description = "Conflict detected, please resolve it manually(sync ${HEAD_BRANCH} into ${FEATURE_BRANCH})"
                currentBuild.result = "FAILURE"
            } else if (runShellstatusCode == 3) {
                echo "Skip, No content to merge"
                currentBuild.description = "Skip, No content to merge(sync ${HEAD_BRANCH} into ${FEATURE_BRANCH})"
                currentBuild.result = "SUCCESS"
            } else {
                echo "Failed to merge cool_feature into main"
                currentBuild.description = "Failed to merge ${HEAD_BRANCH} into ${FEATURE_BRANCH}"
                currentBuild.result = "FAILURE"
            }
        }
    }
}
}catch(Exception e){
    currentBuild.result = "Failure"
}finally{
    build job: 'send_feishu_notify',
        wait: true,
        parameters: [
                [$class: 'StringParameterValue', name: 'RESULT_JOB_NAME', value: "${JOB_NAME}"],
                [$class: 'StringParameterValue', name: 'RESULT_BUILD_RESULT', value: currentBuild.description],
                [$class: 'StringParameterValue', name: 'RESULT_BUILD_NUMBER', value: "${BUILD_NUMBER}"],
                [$class: 'StringParameterValue', name: 'RESULT_RUN_DISPLAY_URL', value: "${RUN_DISPLAY_URL}"],
                [$class: 'StringParameterValue', name: 'RESULT_TASK_START_TS', value: "${taskStartTimeInMillis}"],
                [$class: 'StringParameterValue', name: 'SEND_TYPE', value: currentBuild.result],
                [$class: 'StringParameterValue', name: 'FEISHU_WEBHOOK_ID', value: FEISHU_WEBHOOK_ID],
        ]
}
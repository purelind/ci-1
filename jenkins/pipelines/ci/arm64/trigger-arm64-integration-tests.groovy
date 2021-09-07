


/*
* trigger arm integration test
*  cdc_ghpr_integration_test
*  cdc_ghpr_kafka_integration_test
 */


properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'RELEASE_BRANCH',
                        trim: true
                )
        ]),
        pipelineTriggers([
                parameterizedCron('''
H 1 * * * %RELEASE_BRANCH=master
H 2 * * * %RELEASE_BRANCH=release-5.1
        ''')
        ])
])

node("${GO_TEST_SLAVE}") {
    def ws = pwd()
    deleteDir()

    stage("get hash") {

        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
        println "${ws}"
        sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

        TIDB_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
        TIKV_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
        PD_HASH = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
        BINLOG_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
        LIGHTNING_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tidb-lightning -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
        TOOLS_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
        CDC_HASH = sh(returnStdout: true, script: "python gethash.py -repo=ticdc -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
        BR_HASH = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
        IMPORTER_HASH = sh(returnStdout: true, script: "python gethash.py -repo=importer -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
        TIFLASH_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
        DUMPLING_HASH = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()

        println "tidb hash:${TIDB_HASH}"
        println "tikv hash:${TIKV_HASH}"
        println "pd hash:${PD_HASH}"
        println "binlog hash: ${BINLOG_HASH}"
        println "lightning hash: ${LIGHTNING_HASH}"
        println "tidb tools hash: ${TOOLS_HASH}"
        println "br hash: ${BR_HASH}"
        println "importer hash: ${IMPORTER_HASH}"
        println "cdc hash: ${CDC_HASH}"
        println "tiflash hash: ${TIFLASH_HASH}"
        println "dumpling hash: ${DUMPLING_HASH}"

    }


    stage("trigger job") {
        def default_params = [
                //一些检测 commit 是否重跑的逻辑，force 不触发该逻辑
                booleanParam(name: 'force', value: true),
                booleanParam(name: 'release_test', value: true),
                string(name: 'release_test__release_branch', value: RELEASE_BRANCH),
                string(name: 'release_test__tidb_commit', value: TIDB_HASH),
                string(name: 'release_test__tikv_commit', value: TIKV_HASH),
                string(name: 'release_test__pd_commit', value: PD_HASH),
                string(name: 'release_test__binlog_commit', value: BINLOG_HASH),
                string(name: 'release_test__lightning_commit', value: LIGHTNING_HASH),
                string(name: 'release_test__importer_commit', value: IMPORTER_HASH),
                string(name: 'release_test__tools_commit', value: TOOLS_HASH),
                string(name: 'release_test__tiflash_commit', value: "${RELEASE_BRANCH}/${TIFLASH_HASH}"),
                string(name: 'release_test__br_commit', value: BR_HASH),
                string(name: 'release_test__cdc_commit', value: CDC_HASH)
        ]

        parallel(
                cdc_integration_test_arm64: {
                    build(job: "cdc_integration_test_arm64", parameters: default_params)
                },
                cdc_kafka_integration_test_arm64: {
                    build(job: "cdc_kafka_integration_test_arm64", parameters:  default_params)
                },
                tidb_ghpr_common_test_arm64: {
                    build(job: "tidb_ghpr_common_test_arm64", parameters:  default_params)
                },
                tidb_ghpr_integration_common_test_arm64: {
                    build(job: "tidb_ghpr_integration_common_test_arm64_arm64", parameters:  default_params)
                },
                tidb_ghpr_integration_ddl_test_arm64_arm64: {
                    build(job: "tidb_ghpr_integration_ddl_test_arm64", parameters:  default_params)
                },
                tidb_ghpr_integration_copr_test_arm64: {
                    build(job: "tidb_ghpr_integration_copr_test_arm64", parameters:  default_params)
                },
                tidb_ghpr_sqllogic_test_arm64: {
                    build(job: "tidb_ghpr_sqllogic_test_arm64", parameters:  default_params)
                },
                tidb_ghpr_mybatis_arm64: {
                    build(job: "tidb_ghpr_mybatis_arm64", parameters:  default_params)
                },
                tidb_ghpr_unit_test_arm64: {
                    build(job: "tidb_ghpr_unit_test_arm64", parameters:  default_params)
                },
                binlog_ghpr_integration_arm64: {
                    build(job: "binlog_ghpr_integration_arm64", parameters:  default_params)
                },
                tools_ghpr_integration_arm64: {
                    build(job: "tools_ghpr_integration_arm64", parameters:  default_params)
                },
                pd_test_arm64: {
                    build(job: "pd_test_arm64", parameters: default_params)
                },
                importer_ghpr_test_arm64: {
                    build(job: "importer_ghpr_test_arm64", parameters: default_params)
                }
        )
    }

}
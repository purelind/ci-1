
properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'TRIGGER_JOB',
                        trim: true
                )
        ]),
        pipelineTriggers([
                parameterizedCron('''
H H(1-6) * * * %TRIGGER_JOB=tidb_nightly_test_arm64/master
H H(1-6) * * * %TRIGGER_JOB=tidb_nightly_test_arm64/release-5.1
H H(1-6) * * * %TRIGGER_JOB=binlog_nightly_test_arm64/master
H H(1-6) * * * %TRIGGER_JOB=binlog_nightly_test_arm64/release-5.1
H H(1-6) * * * %TRIGGER_JOB=br_nightly_test_arm64/master
H H(1-6) * * * %TRIGGER_JOB=br_nightly_test_arm64/release-5.1
H H(1-6) * * * %TRIGGER_JOB=cdc_nightly_test_arm64/master
H H(1-6) * * * %TRIGGER_JOB=cdc_nightly_test_arm64/release-5.1
H H(1-6) * * * %TRIGGER_JOB=dm_nightly_test_arm64/master
H H(1-6) * * * %TRIGGER_JOB=dm_nightly_test_arm64/release-5.1
H H(1-6) * * * %TRIGGER_JOB=dumpling_nightly_test_arm64/master
H H(1-6) * * * %TRIGGER_JOB=dumpling_nightly_test_arm64/release-5.1
H H(1-6) * * * %TRIGGER_JOB=importer_nightly_test_arm64/master
H H(1-6) * * * %TRIGGER_JOB=importer_nightly_test_arm64/release-5.1
H H(1-6) * * * %TRIGGER_JOB=pd_nightly_test_arm64/master
H H(1-6) * * * %TRIGGER_JOB=pd_nightly_test_arm64/release-5.1
H H(1-6) * * * %TRIGGER_JOB=tikv_nightly_test_arm64/master
H H(1-6) * * * %TRIGGER_JOB=tikv_nightly_test_arm64/release-5.1
        ''')
        ])
])

node("${GO_TEST_SLAVE}") {
    build(job: ${TRIGGER_JOB})
}
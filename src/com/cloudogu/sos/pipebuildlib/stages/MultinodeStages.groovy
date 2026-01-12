package com.cloudogu.sos.pipebuildlib

class MultinodeStages implements DoguStageModule {

    void register(DoguPipe pipe, StageGroup group) {

        group.raw_stage("Checkout", PipelineMode.INTEGRATIONMULTINODE) {
            pipe.checkout_updatemakefiles(pipe.updateSubmodules)
        }

        group.raw_stage('MN-Setup', PipelineMode.INTEGRATIONMULTINODE) {

            def defaultSetupConfig = [
                clustername        : pipe.script.params.ClusterName,
                additionalDogus    : [],
                additionalComponents: []
            ]

            pipe.additionalDogus.each { d ->
                if (!defaultSetupConfig.additionalDogus.contains(d)) {
                    defaultSetupConfig.additionalDogus << d
                }
            }

            pipe.additionalComponents.each { c ->
                if (!defaultSetupConfig.additionalComponents.contains(c)) {
                    defaultSetupConfig.additionalComponents << c
                }
            }

            if (pipe.script.params.TestDoguUpgrade) {
                if (pipe.script.params.OldDoguVersionForUpgradeTest?.trim() &&
                    !pipe.script.params.OldDoguVersionForUpgradeTest.contains('v')) {

                    pipe.script.echo "Installing user-defined version of dogu: ${pipe.script.params.OldDoguVersionForUpgradeTest}"
                    defaultSetupConfig.additionalDogus << "${pipe.namespace}/${pipe.doguName}@${pipe.script.params.OldDoguVersionForUpgradeTest}"

                } else {
                    pipe.script.echo "Installing latest released version of dogu..."
                    defaultSetupConfig.additionalDogus << "${pipe.namespace}/${pipe.doguName}"
                }
            }

            pipe.multiNodeEcoSystem.setup(defaultSetupConfig)
        }

        group.raw_stage('MN-Build', PipelineMode.INTEGRATIONMULTINODE) {
            pipe.script.env.NAMESPACE = "ecosystem"
            pipe.script.env.RUNTIME_ENV = "remote"
            pipe.multiNodeEcoSystem.build(pipe.doguName)
        }

        group.raw_stage("MN-Wait for Dogu", PipelineMode.INTEGRATIONMULTINODE) {
            pipe.multiNodeEcoSystem.waitForDogu(pipe.doguName)
        }

        group.raw_stage("MN-Verify", PipelineMode.INTEGRATIONMULTINODE) {
            pipe.multiNodeEcoSystem.verify(pipe.doguName)
        }

        if (pipe.runIntegrationTests) {
            group.raw_stage("MN-Run Integration Tests", PipelineMode.INTEGRATIONMULTINODE) {
                pipe.multiNodeEcoSystem.runCypressIntegrationTests([
                    cypressImage     : pipe.upgradeCypressImage,
                    enableVideo      : pipe.script.params.EnableVideoRecording,
                    enableScreenshots: pipe.script.params.EnableScreenshotRecording
                ])
            }
        }

        // you can keep the cluster for later inspection (default: false)
        if (!pipe.script.params.KeepCluster) {
            // this stage must be named "Clean" to get executed in any case at the end of the pipeline
            group.raw_stage("Clean", PipelineMode.INTEGRATIONMULTINODE) {
                pipe.multiNodeEcoSystem.destroy()
            }
        }
    }
}

package com.cloudogu.sos.pipebuildlib.stages

import com.cloudogu.sos.pipebuildlib.*
import com.cloudogu.sos.pipebuildlib.dogu.*
import com.cloudogu.ces.cesbuildlib.*
import com.cloudogu.ces.dogubuildlib.*

class IntegrationStages implements DoguStageModule {

    void register(DoguPipe pipe, StageGroup group) {
        String releaseTargetBranch = ''
        String releaseVersion = ''

        group.stage("Checkout", PipelineMode.INTEGRATION) {
            pipe.checkout_updatemakefiles(pipe.updateSubmodules)
        }

        group.stage("Provision", PipelineMode.INTEGRATION) {
            if (pipe.gitflow.isPreReleaseBranch()) {
                pipe.script.sh "make prerelease_namespace"
            }
            pipe.ecoSystem.provision(pipe.doguDir, pipe.machineType)
        }

        group.stage("Setup", PipelineMode.INTEGRATION) {
            pipe.ecoSystem.loginBackend(pipe.backendUser)

            def setupArgs = [:]
            if (pipe.registryConfig?.trim()) setupArgs.registryConfig = pipe.registryConfig
            if (pipe.registryConfigE?.trim()) setupArgs.registryConfigEncrypted = pipe.registryConfigE
            if (pipe.additionalDependencies) setupArgs.additionalDependencies = pipe.additionalDependencies

            if (setupArgs) {
                pipe.script.echo "[INFO] Calling setup with: ${setupArgs.keySet()}"
                pipe.ecoSystem.setup(*: setupArgs)
            } else {
                pipe.script.echo "[INFO] Calling setup with no arguments"
                pipe.ecoSystem.setup()
            }
        }

        if (pipe.dependedDogus) {
            group.stage("Wait for dependencies", PipelineMode.INTEGRATION) {
                pipe.script.timeout(time: pipe.waitForDepTime, unit: 'MINUTES') {
                    pipe.dependedDogus.each { dep ->
                        pipe.ecoSystem.waitForDogu(dep)
                    }
                }
            }
        }

        group.stage("Build", PipelineMode.INTEGRATION) {
            def rawPreinstalled = pipe.ecoSystem.defaultSetupConfig["dependencies"].collect { it.split("/")[1] }

            if (rawPreinstalled.contains(pipe.doguName) && pipe.gitflow.isPreReleaseBranch()) {
                pipe.ecoSystem.purgeDogu(
                    pipe.doguName,
                    "--keep-config --keep-volumes --keep-service-accounts --keep-logs"
                )
            }
            pipe.ecoSystem.build(pipe.doguDir)
        }

        group.stage("Trivy scan", PipelineMode.INTEGRATION) {
            pipe.ecoSystem.copyDoguImageToJenkinsWorker(pipe.doguDir)

            Trivy trivy = new Trivy(pipe.script)

            if (!pipe.config.checkEOL) {
                trivy.metaClass.scanImage = { String imageName,
                                              String severityLevel = TrivySeverityLevel.CRITICAL,
                                              String strategy = TrivyScanStrategy.UNSTABLE,
                                              String additionalFlags = "--db-repository public.ecr.aws/aquasecurity/trivy-db --java-db-repository public.ecr.aws/aquasecurity/trivy-java-db",
                                              String trivyReportFile = "trivy/trivyReport.json" ->

                    pipe.script.echo "[DEBUG] trivy.metaClass.scanImage overwritten"

                    String trivyVersion = "0.67.2"
                    String trivyImage = "aquasec/trivy"
                    String trivyDirectory = "trivy"

                    String script_str =
                        "trivy image --exit-code 10 --exit-on-eol 0 --format ${TrivyScanFormat.JSON} " +
                        "-o ${trivyReportFile} --severity ${severityLevel} ${additionalFlags} ${imageName}"

                    Integer exitCode = pipe.docker.image("${trivyImage}:${trivyVersion}")
                        .mountJenkinsUser()
                        .mountDockerSocket()
                        .inside("-v ${pipe.script.env.WORKSPACE}/.trivy/.cache:/root/.cache/") {
                            pipe.script.sh("mkdir -p " + trivyDirectory)
                            pipe.script.sh(script: script_str, returnStatus: true)
                        }

                    switch (exitCode) {
                        case 0: return true
                        case 10:
                            switch (strategy) {
                                case TrivyScanStrategy.UNSTABLE:
                                    pipe.script.archiveArtifacts artifacts: trivyReportFile, allowEmptyArchive: true
                                    pipe.script.unstable("Trivy has found vulnerabilities in image ${imageName}")
                                    break
                                case TrivyScanStrategy.FAIL:
                                    pipe.script.archiveArtifacts artifacts: trivyReportFile, allowEmptyArchive: true
                                    pipe.script.error("Trivy has found vulnerabilities in image ${imageName}")
                                    break
                            }
                            return false
                        default:
                            pipe.script.error("Error during trivy scan; exit code: " + exitCode)
                    }
                }
            }

            trivy.scanDogu(".", pipe.script.params.TrivySeverityLevels, pipe.script.params.TrivyStrategy)
            trivy.saveFormattedTrivyReport(TrivyScanFormat.TABLE)
            trivy.saveFormattedTrivyReport(TrivyScanFormat.JSON)
            trivy.saveFormattedTrivyReport(TrivyScanFormat.HTML)
        }

        group.stage("Archive Trivy", PipelineMode.INTEGRATION) {
            pipe.script.withCredentials([pipe.script.usernamePassword(
                credentialsId: 'trivy-archive-s3-keys',
                usernameVariable: 'ACCESS_KEY',
                passwordVariable: 'SECRET_KEY'
            )]) {
                String remotePath = "dogus/${pipe.namespace}/${pipe.doguName}/${pipe.git.getCommitHash()}.json"

                pipe.script.sh """
                    curl "https://trivy.fsn1.your-objectstorage.com/${remotePath}" \
                        --upload-file "trivy/trivyReport.json" \
                        --user "\${ACCESS_KEY}:\${SECRET_KEY}" \
                        --aws-sigv4 "aws:amz:fsn1:s3" \
                        --header "x-amz-content-sha256: UNSIGNED-PAYLOAD"
                """
            }
        }

        group.stage("Verify", PipelineMode.INTEGRATION) {
            pipe.ecoSystem.verify(pipe.doguDir)
        }

        if (pipe.runIntegrationTests) {
            group.stage("Integration Tests", PipelineMode.INTEGRATION) {
                pipe.runCypress(pipe.ecoSystem, pipe.cypressImage)
            }
        }

        if (pipe.script.params.TestDoguUpgrade) {
            group.stage("Upgrade Dogu", PipelineMode.INTEGRATION) {

                pipe.ecoSystem.purgeDogu(pipe.doguName)

                if (pipe.script.params.OldDoguVersionForUpgradeTest?.trim() &&
                    !pipe.script.params.OldDoguVersionForUpgradeTest.contains('v')) {

                    pipe.script.echo "Installing user-defined version of dogu: ${pipe.script.params.OldDoguVersionForUpgradeTest}"
                    pipe.ecoSystem.installDogu("${pipe.namespace}/${pipe.doguName} ${pipe.script.params.OldDoguVersionForUpgradeTest}")

                } else {
                    pipe.script.echo "Installing latest released version of dogu..."
                    pipe.ecoSystem.installDogu("${pipe.namespace}/${pipe.doguName}")
                }

                pipe.ecoSystem.startDogu(pipe.doguName)
                pipe.ecoSystem.waitForDogu(pipe.doguName)
                pipe.ecoSystem.upgradeDogu(pipe.ecoSystem)
                pipe.ecoSystem.waitForDogu(pipe.doguName)

                if (pipe.runIntegrationTests) {
                    pipe.script.stage("Integration Tests - After Upgrade", PipelineMode.INTEGRATION) {
                        pipe.ecoSystem.runCypressIntegrationTests([
                            cypressImage     : pipe.upgradeCypressImage,
                            enableVideo      : pipe.script.params.EnableVideoRecording,
                            enableScreenshots: pipe.script.params.EnableScreenshotRecording
                        ])
                    }
                }
            }
        }

        if (pipe.gitflow.isReleaseBranch()) {
            group.stage('Retrieving Release Branch') {
                pipe.script.withCredentials([pipe.script.usernamePassword(
                    credentialsId: pipe.gitUserName,
                    usernameVariable: 'GIT_AUTH_USR',
                    passwordVariable: 'GIT_AUTH_PSW'
                )]) {

                    pipe.script.sh """
                        git config credential.helper '!f() { echo username=\$GIT_AUTH_USR; echo password=\$GIT_AUTH_PSW; }; f'
                        git fetch origin +refs/heads/*:refs/remotes/origin/*

                        release_target=\$(if git show-ref --verify --quiet refs/remotes/origin/main; then
                            echo main
                        elif git show-ref --verify --quiet refs/remotes/origin/master; then
                            echo master
                        else
                            exit 1
                        fi)

                        echo "\$release_target" > release_target.txt
                    """

                    releaseVersion = pipe.git.getSimpleBranchName()
                    releaseTargetBranch = pipe.script.readFile('release_target.txt').trim()
                    pipe.script.echo "[DEBUG] release branch: ${releaseTargetBranch}"
                }
            }

            group.stage('Finish Release') {
                // Optionally, target branch can be provided (default "main")
                pipe.gitflow.finishRelease(releaseVersion, releaseTargetBranch)
            }

            group.stage('Push Dogu to registry') {
                pipe.ecoSystem.push(pipe.doguDir)
            }

            group.stage('Add Github-Release') {
                pipe.github.createReleaseWithChangelog(
                    releaseVersion,
                    pipe.changelog,
                    releaseTargetBranch
                )
            }

            group.stage('Notfiy Webhook - Release') {
                pipe.notifyRelease()
            }
        } else if (pipe.gitflow.isPreReleaseBranch()) {
            group.stage("Push Prerelease Dogu to registry") {
                ecoSystem.pushPreRelease(doguDir)
            }
        }
    }
}

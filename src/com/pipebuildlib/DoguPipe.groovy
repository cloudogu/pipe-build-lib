package com.pipebuildlib

import com.cloudogu.ces.cesbuildlib.*
import com.cloudogu.ces.dogubuildlib.*

class DoguPipe extends BasePipe {
    EcoSystem ecoSystem
    Git git
    GitFlow gitflow
    GitHub github
    Changelog changelog
    Vagrant vagrant
    Markdown markdown

    DoguPipe(script, Map config) {
        super(script)

        def doguName = config.doguName
        def doguDir = config.doguDirectory ?: "/dogu"
        def backendUser = config.backendUser
        def gitUserName = config.gitUser
        def committerEmail = config.committerEmail
        def gcloudCredentials = config.gcloudCredentials
        def sshCredentials = config.sshCredentials
        def shellScripts = config.shellScripts ?: ''
        def markdownVersion = config.markdownVersion ?: "3.12.2"
        def updateSubmodules = config.updateSubmodules ?: false
        def runIntegrationTests = config.runIntegrationTests ?: false
        def doBatsTests = config.doBatsTests ?: false
        def registryConfig = config.registryConfig ?: """"""
        def registryConfigE = config.registryConfigEncrypted ?: """"""
        def additionalDependencies = config.additionalDependencies ?: """"""
        def cypressImage = config.cypressImage ?: "cypress/included:13.15.2"
        def upgradeCypressImage = config.upgradeCypressImage ?: "cypress/included:13.2.0"
        def dependedDogus = config.dependencies ?: []
        def waitForDepTime = config.waitForDepTime ?: 15
        def namespace = config.namespace ?: "official"
        def agents = config.agents ?: []

        String releaseTargetBranch = ""
        String releaseVersion = ""

        git = new Git(script, gitUserName)
        git.committerName = gitUserName
        git.committerEmail = committerEmail
        gitflow = new GitFlow(script, git)
        github = new GitHub(script, git)
        changelog = new Changelog(script)
        ecoSystem = new EcoSystem(script, gcloudCredentials, sshCredentials)
        vagrant = new Vagrant(script, gcloudCredentials, sshCredentials)
        markdown = new Markdown(script, markdownVersion)

        addStage("Checkout", {
            checkout_updatemakefiles(updateSubmodules)
        }, agents)

        addStage("Lint") {
            script.lintDockerfile()
        }

        if (config.checkMarkdown) {
            addStage('Check Markdown Links') {
                markdown.check()
            }
        }

        if (shellScripts) {
            addStage("Shellcheck") {
                script.shellCheck(shellScripts)
            }
        }

        if (config.runShellTests) {
            addStage("Shell Tests") {
                executeShellTests()
            }
        }

        if (doBatsTests) {
            addStage("Bats Tests") {
                Bats bats = new Bats(script, script.docker)
                bats.checkAndExecuteTests()
            }
        }

        addStage("Provision") {
            if (gitflow.isPreReleaseBranch()) {
                script.sh "make prerelease_namespace"
            }
            ecoSystem.provision(doguDir)
        }

        addStage("Setup") {
            ecoSystem.loginBackend(backendUser)
            def setupArgs = [:]
            if (registryConfig?.trim()) setupArgs.registryConfig = registryConfig
            if (registryConfigE?.trim()) setupArgs.registryConfigEncrypted = registryConfigE
            if (additionalDependencies) setupArgs.additionalDependencies = additionalDependencies

            if (setupArgs) {
                script.echo "[INFO] Calling setup with: ${setupArgs.keySet()}"
                ecoSystem.setup(*:setupArgs)
            } else {
                script.echo "[INFO] Calling setup with no arguments"
                ecoSystem.setup()
            }
        }

        if (dependedDogus) {
            addStage("Wait for dependencies") {
                script.timeout(time: waitForDepTime, unit: 'MINUTES') {
                    dependedDogus.each { dep ->
                        ecoSystem.waitForDogu(dep)
                    }
                }
            }
        }

        addStage("Build") {
            def rawPreinstalled = ecoSystem.defaultSetupConfig["dependencies"].collect { it.split("/")[1] }
            if (rawPreinstalled.contains(doguName) && gitflow.isPreReleaseBranch()) {
                ecoSystem.purgeDogu(doguName, "--keep-config --keep-volumes --keep-service-accounts --keep-logs")
            }
            ecoSystem.build(doguDir)
        }

        addStage("Trivy scan") {
            ecoSystem.copyDoguImageToJenkinsWorker(doguDir)
            Trivy trivy = new Trivy(script)
            trivy.scanDogu(".", script.params.TrivySeverityLevels, script.params.TrivyStrategy)
            trivy.saveFormattedTrivyReport(TrivyScanFormat.TABLE)
            trivy.saveFormattedTrivyReport(TrivyScanFormat.JSON)
            trivy.saveFormattedTrivyReport(TrivyScanFormat.HTML)
        }

        addStage("Pre Verify Hook") {
            preVerifyStage.call(ecoSystem)
        }

        addStage("Verify") {
            ecoSystem.verify(doguDir)
        }

        addStage("Post Verify Hook") {
            postVerifyStage.call(ecoSystem)
        }

        if (runIntegrationTests) {
            addStage("Integration Tests") {
                runCypress(ecoSystem, cypressImage)
            }
        }

        addStage("Post Integration Hook") {
            postIntegrationStage.call(ecoSystem)
        }

        if (script.params.TestDoguUpgrade) {
            addStage("Upgrade Dogu") {
                ecoSystem.purgeDogu(doguName)

                if (script.params.OldDoguVersionForUpgradeTest?.trim() && !script.params.OldDoguVersionForUpgradeTest.contains('v')) {
                    script.echo "Installing user-defined version of dogu: ${script.params.OldDoguVersionForUpgradeTest}"
                    ecoSystem.installDogu("${namespace}/${doguName} ${script.params.OldDoguVersionForUpgradeTest}")
                } else {
                    script.echo "Installing latest released version of dogu..."
                    ecoSystem.installDogu("${namespace}/${doguName}")
                }

                ecoSystem.startDogu(doguName)
                ecoSystem.waitForDogu(doguName)
                ecoSystem.upgradeDogu(ecoSystem)
                ecoSystem.waitForDogu(doguName)

                if (runIntegrationTests) {
                    script.stage("Integration Tests - After Upgrade") {
                        ecoSystem.runCypressIntegrationTests([
                            cypressImage     : upgradeCypressImage,
                            enableVideo      : script.params.EnableVideoRecording,
                            enableScreenshots: script.params.EnableScreenshotRecording
                        ])
                    }
                }
            }
        }

        if (gitflow.isReleaseBranch()) {
            addStage('Retrieving Release Branch') {
                script.sh 'git fetch origin +refs/heads/*:refs/remotes/origin/*'
                releaseVersion = git.getSimpleBranchName()
                releaseTargetBranch = script.sh(
                    script: '''
                    if git show-ref --verify --quiet refs/remotes/origin/main; then
                        echo main
                    elif git show-ref --verify --quiet refs/remotes/origin/master; then
                        echo master
                    else
                        exit 1
                    fi
                    ''',
                    returnStdout: true
                ).trim()
                script.echo "[DEBUG] release branch: ${releaseTargetBranch}"
            }
            addStage('Finish Release') {
                // Optionally, target branch can be provided (default "main")
                gitflow.finishRelease(releaseVersion, releaseTargetBranch)
            }
            addStage('Push Dogu to registry') {
                ecoSystem.push(doguDir)
            }
            addStage('Add Github-Release') {
                github.createReleaseWithChangelog(releaseVersion, changelog, releaseTargetBranch)
            }
        } else if (gitflow.isPreReleaseBranch()) {
            addStage("Push Prerelease Dogu to registry") {
                ecoSystem.pushPreRelease(doguDir)
            }
        }

        addStage("Clean") {
            ecoSystem.destroy()
        }
    }

    void executeShellTests() {
        String bats_base_image = 'bats/bats'
        String bats_custom_image = 'cloudogu/bats'
        String bats_tag = '1.2.1'
        def batsImage = script.docker.build("${bats_custom_image}:${bats_tag}", "--build-arg=BATS_BASE_IMAGE=${bats_base_image} --build-arg=BATS_TAG=${bats_tag} ./batsTests")
        try {
            script.sh "mkdir -p target"
            script.sh "mkdir -p testdir"
            batsImage.inside("--entrypoint='' -v ${script.WORKSPACE}:/workspace -v ${script.WORKSPACE}/testdir:/usr/share/webapps") {
                script.sh "make unit-test-shell-ci"
            }
        } finally {
            script.junit allowEmptyResults: true, testResults: 'target/shell_test_reports/*.xml'
        }
    }

    void runCypress(EcoSystem ecoSystem, def cypressImage) {
        ecoSystem.runCypressIntegrationTests([
            cypressImage     : cypressImage,
            enableVideo      : script.params.EnableVideoRecording,
            enableScreenshots: script.params.EnableScreenshotRecording
        ])
    }

    void setBuildProperties(List<ParameterDefinition> customParams = null) {
        def defaultParams = [
            script.booleanParam(name: 'TestDoguUpgrade', defaultValue: false, description: 'Test dogu upgrade from latest release or optionally from defined version below'),
            script.booleanParam(name: 'EnableVideoRecording', defaultValue: true, description: 'Enables cypress to record video of the integration tests.'),
            script.booleanParam(name: 'EnableScreenshotRecording', defaultValue: true, description: 'Enables cypress to take screenshots of failing integration tests.'),
            script.string(name: 'OldDoguVersionForUpgradeTest', defaultValue: '', description: 'Old Dogu version for the upgrade test (optional; e.g. 4.1.0-3)'),
            script.choice(name: 'TrivySeverityLevels', choices: [TrivySeverityLevel.CRITICAL, TrivySeverityLevel.HIGH_AND_ABOVE, TrivySeverityLevel.MEDIUM_AND_ABOVE, TrivySeverityLevel.ALL], description: 'The levels to scan with trivy'),
            script.choice(name: 'TrivyStrategy', choices: [TrivyScanStrategy.UNSTABLE, TrivyScanStrategy.FAIL, TrivyScanStrategy.IGNORE], description: 'What to do if vulnerabilities are found')
        ]

        script.properties([
            script.buildDiscarder(script.logRotator(numToKeepStr: '10')),
            script.disableConcurrentBuilds(),
            script.parameters(customParams ?: defaultParams)
        ])
    }

    void checkout_updatemakefiles(boolean updateSubmodules) {
        checkout scm
        if (updateSubmodules) {
            sh 'git submodule update --init'
        }

        if (fileExists('Makefile')) {
            stage('Update Makefile Version') {
                // Download yq only if needed (optional)
                sh '''
                    mkdir -p .bin
                    curl -L https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64 -o .bin/yq
                    chmod +x .bin/yq
                '''
                // Get latest tag from GitHub API
                String latestVersion = sh(
                    script: "curl -s https://api.github.com/repos/cloudogu/makefiles/releases/latest | grep tag_name | cut -d '\"' -f4",
                    returnStdout: true
                ).trim()

                // Strip leading "v"
                String versionNoV = latestVersion.replaceFirst(/^v/, '')

                echo "Latest Makefiles version is ${versionNoV}"

                // Replace version in Makefile
                sh "sed -i 's/^MAKEFILES_VERSION=.*/MAKEFILES_VERSION=${versionNoV}/' Makefile"

                // Manually fetch and apply the Makefiles from the public GitHub tag archive
                sh 'make update-makefiles'
            }
        }
    }
}

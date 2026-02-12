package com.cloudogu.sos.pipebuildlib

import com.cloudogu.sos.pipebuildlib.*
import com.cloudogu.sos.pipebuildlib.dogu.*
import com.cloudogu.sos.pipebuildlib.dogu.stages.*

import com.cloudogu.ces.cesbuildlib.*
import com.cloudogu.ces.dogubuildlib.*

/**
 * DoguPipe is the orchestration layer of the Dogu pipeline: it binds the Jenkins
 * runtime (`script`) to a fully initialized DoguConfig and composes the pipeline
 * by assembling independent stage modules (Static, Integration, Multinode, Release)
 * into agent-scoped execution groups, while also exposing shared utilities and
 * Jenkins job configuration for all stages.
 */
class DoguPipe extends BasePipe {

// ============================================================================
//  Construction & Core Wiring
// ============================================================================

    final DoguConfig config

    DoguPipe(script, Map raw) {
        super(script)
        this.config = new DoguConfig(script, raw)
    }

// ============================================================================
//  Groovy Property Forwarding (DoguConfig → DoguPipe)
// ============================================================================

    // Transparenter Zugriff: dogu.ecoSystem → config.ecoSystem
    def propertyMissing(String name) {
        if (config.hasProperty(name)) {
            return config."$name"
        }
        throw new MissingPropertyException(name, this.class)
    }

    // Setter-Fallback falls jemand etwas überschreibt
    void propertyMissing(String name, value) {
        if (config.hasProperty(name)) {
            config."$name" = value
            return
        }
        throw new MissingPropertyException(name, this.class)
    }

// ============================================================================
//  Pipeline Orchestration
// ============================================================================

    @Override
    void addDefaultStages() {
        // Load mode
        String pipelineMode = script.params.pipelineMode ?: 'FULL'
        script.echo "[INFO] Pipeline mode selected: ${pipelineMode}"

        // local vars
        addStageGroup(agentStatic) { group ->
            new StaticStages().register(this, group)
        }

        addStageGroup(this.agentMultinode) { group ->
            new MultinodeStages().register(this, group)
        }

        addStageGroup(this.agentVagrant) { group ->
            new IntegrationStages().register(this, group)
            new ReleaseStages().register(this, group)
            group.stage('Clean') {
                ecoSystem.destroy()
            }
        }
    }

// ============================================================================
//  Jenkins Job / Parameter Setup
// ============================================================================

    @Override
    void setBuildProperties(List<hudson.model.ParameterDefinition> customParams = null) {
        setupEnvironment()
        // Dynamically build the choices list
        def pipelineModeChoices = ['FULL', 'STATIC', 'INTEGRATION', 'INTEGRATIONMULTINODE']
        def defaultParams = []
        def currentBranch = script.env.BRANCH_NAME
        script.echo "[DEBUG] setBuildProperties, currentBranch: ${currentBranch}"

        def baseParams = [
            script.booleanParam(name: 'TestDoguUpgrade', defaultValue: false, description: 'Test dogu upgrade from latest release or optionally from defined version below'),
            script.booleanParam(name: 'EnableVideoRecording', defaultValue: true, description: 'Enables cypress to record video of the integration tests.'),
            script.booleanParam(name: 'EnableScreenshotRecording', defaultValue: true, description: 'Enables cypress to take screenshots of failing integration tests.'),
            script.string(name: 'OldDoguVersionForUpgradeTest', defaultValue: '', description: 'Old Dogu version for the upgrade test (optional; e.g. 4.1.0-3)'),
            script.choice(name: 'TrivySeverityLevels', choices: [TrivySeverityLevel.CRITICAL, TrivySeverityLevel.HIGH_AND_ABOVE, TrivySeverityLevel.MEDIUM_AND_ABOVE, TrivySeverityLevel.ALL], description: 'The levels to scan with trivy'),
            script.choice(name: 'TrivyStrategy', choices: [TrivyScanStrategy.UNSTABLE, TrivyScanStrategy.FAIL, TrivyScanStrategy.IGNORE], description: 'What to do if vulnerabilities are found'),
            script.string(name: 'ClusterName', defaultValue: '', description: 'Optional: Name of the multinode integration test cluster. A new instance gets created if this parameter is not supplied'),
            script.booleanParam(name: 'KeepCluster', defaultValue: false, description: 'Optional: If True, the cluster will not be deleted after the build execution'),
        ]

        if (currentBranch == 'develop') {
            pipelineModeChoices << 'RELEASE'
            defaultParams << script.string(
                    name: 'ReleaseTag',
                    defaultValue: '',
                    description:"Only required if PipelineMode=RELEASE. Enter new release tag (latest: ${this.latestTag})."
                    )
        }

        defaultParams << script.choice(
                            name: 'PipelineMode',
                            choices: pipelineModeChoices,
                            defaultValue: 'FULL',
                            description: 'Select pipeline mode'
                            )

        defaultParams.addAll(baseParams)

        script.properties([
            script.buildDiscarder(script.logRotator(numToKeepStr: '10')),
            script.disableConcurrentBuilds(),
            script.parameters(customParams ?: defaultParams)
        ])
    }

// ============================================================================
// Utilities
// ============================================================================

    void checkout_updatemakefiles(boolean updateSubmodules) {
        script.checkout script.scm
        if (updateSubmodules) {
            script.sh "git config --global url.'https://github.com/'.insteadof git@github.com:"

            git.executeGitWithCredentials('submodule sync')
            // Get submodule in "app" directory
            git.executeGitWithCredentials('submodule update --init --recursive')
        }

        if (script.fileExists('Makefile')) {
            script.stage('Update Makefile Version') {
                // sos image has already yq installed
                // Download yq only if needed (optional)
                script.sh '''
                    mkdir -p .bin
                    curl -L https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64 -o .bin/yq
                    chmod +x .bin/yq
                '''
                // Get latest tag from GitHub API
                String latestVersion = script.sh(
                    script: "curl -s https://api.github.com/repos/cloudogu/makefiles/releases/latest | grep tag_name | cut -d '\"' -f4",
                    returnStdout: true
                ).trim()

                // Strip leading "v"
                String versionNoV = latestVersion.replaceFirst(/^v/, '')

                script.echo "Latest Makefiles version is ${versionNoV}"

                // Replace version in Makefile
                script.sh "sed -i 's/^MAKEFILES_VERSION=.*/MAKEFILES_VERSION=${versionNoV}/' Makefile"

                // Manually fetch and apply the Makefiles from the public GitHub tag archive
                script.sh 'make update-makefiles'
            }
        }
    }

    void executeShellTests() {
        String bats_base_image = 'bats/bats'
        String bats_custom_image = 'cloudogu/bats'
        String bats_tag = '1.2.1'
        def batsImage = script.docker.build("${bats_custom_image}:${bats_tag}", "--build-arg=BATS_BASE_IMAGE=${bats_base_image} --build-arg=BATS_TAG=${bats_tag} ./batsTests")
        try {
            script.sh 'mkdir -p target'
            script.sh 'mkdir -p testdir'
            batsImage.inside("--entrypoint='' -v ${script.WORKSPACE}:/workspace -v ${script.WORKSPACE}/testdir:/usr/share/webapps") {
                script.sh 'make unit-test-shell-ci'
            }
        } finally {
            script.junit allowEmptyResults: true, testResults: 'target/shell_test_reports/*.xml'
        }
    }

    void gitWithCredentials(String command) {
        script.withCredentials([script.usernamePassword(credentialsId: this.gitUserName, usernameVariable: 'GIT_AUTH_USR', passwordVariable: 'GIT_AUTH_PSW')]) {
            script.sh(
                    script: "git -c credential.helper=\"!f() { echo username='\$GIT_AUTH_USR'; echo password='\$GIT_AUTH_PSW'; }; f\" " + command,
                    returnStdout: true
            )
        }
    }

    String fetchLatestGithubRelease(String doguName) {
        String repoName = '';
        if(this.config.repoName){
            repoName = this.config.repoName
        }
        else {
            repoName = doguName
        }

        def githubApiUrl = "https://api.github.com/repos/cloudogu/${repoName}/releases/latest"

        try {
            script.withCredentials([script.usernamePassword(
                credentialsId: this.gitUserName,
                usernameVariable: 'GIT_AUTH_USR',
                passwordVariable: 'GIT_AUTH_PSW'
            )]) {
                def response = script.httpRequest(
                    url: githubApiUrl,
                    httpMode: 'GET',
                    acceptType: 'APPLICATION_JSON',
                    customHeaders: [
                        [name: 'Authorization', value: "Basic ${"${GIT_AUTH_USR}:${GIT_AUTH_PSW}".bytes.encodeBase64().toString()}"]
                    ],
                    consoleLogResponseBody: false,
                    validResponseCodes: '200'
                )
                def json = new groovy.json.JsonSlurper().parseText(response.content)
                return json.tag_name ?: 'unknown'
            }
        } catch (Exception e) {
            script.echo "Failed to fetch release version: ${e}"
            return 'unknown'
        }
    }

    void setupEnvironment() {
        this.latestTag = fetchLatestTagInNode(script, this.gitUserName, this.doguName)
    }

    private static String fetchLatestTagInNode(def script, String gitUserName, String doguName) {
        String tag = 'unknown'
        String repoName = '';
        if(this.config.repoName){
            repoName = this.config.repoName
        }else {
            repoName = doguName == 'easyredmine' ? "${doguName}-itz" : doguName
        }

        script.node {
            script.withCredentials([script.usernamePassword(
                credentialsId: gitUserName,
                usernameVariable: 'GIT_AUTH_USR',
                passwordVariable: 'GIT_AUTH_PSW'
            )]) {
                script.sh 'rm -rf repo && mkdir repo'
                script.dir('repo') {
                    script.sh """
                        git clone https://${'$'}GIT_AUTH_USR:${'$'}GIT_AUTH_PSW@github.com/cloudogu/${repoName}.git .
                        git fetch --tags
                    """
                    tag = script.sh(
                        script: "git tag --list 'v*' --sort=-v:refname | head -n 1",
                        returnStdout: true
                    ).trim()
                }
            }
        }
        return tag
    }

    void notifyRelease() {
        def webhookSecret = this.releaseWebhookUrlSecret
        script.withCredentials([script.string(credentialsId: webhookSecret, variable: 'webhookUrl')]) {
            def repoUrl = git.getRepositoryUrl().replaceFirst(/\.git$/, '')
            def releaseVersion = git.getSimpleBranchName()
            def doguName = this.doguName
            def messageText = """\
*New Dogu Release*
• Project: *<${repoUrl}|${doguName}>*
• Version: *${releaseVersion}*
• <${repoUrl}/releases/tag/${releaseVersion}|View Changelog>
""".stripIndent()
            def messageTextclean = messageText

            def message = [
                text: messageTextclean,
                formattedText: messageText
            ]

            try {
                def response = script.httpRequest(
                    httpMode: 'POST',
                    contentType: 'APPLICATION_JSON',
                    requestBody: groovy.json.JsonOutput.toJson(message),
                    url: script.env.webhookUrl
                )
                script.echo "Notification sent to Google Chat: ${response.status} ${response.content}"
            } catch (Exception notifyError) {
                script.echo "Failed to send notification to Google Chat: ${notifyError.getMessage()}"
            }
        }
    }

    void runCypress(EcoSystem ecoSystem, def cypressImage) {
        ecoSystem.runCypressIntegrationTests([
            cypressImage     : cypressImage,
            enableVideo      : script.params.EnableVideoRecording,
            enableScreenshots: script.params.EnableScreenshotRecording
        ])
    }

}

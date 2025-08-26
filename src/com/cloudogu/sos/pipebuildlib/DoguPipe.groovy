package com.cloudogu.sos.pipebuildlib

import com.cloudogu.ces.cesbuildlib.*
import com.cloudogu.ces.dogubuildlib.*

class DoguPipe extends BasePipe {

    EcoSystem ecoSystem
    MultiNodeEcoSystem multiNodeEcoSystem
    Git git
    GitFlow gitflow
    GitHub github
    Docker docker
    Changelog changelog
    Vagrant vagrant
    Markdown markdown
    Map config

    String doguName
    String doguDir
    String backendUser
    String shellScripts
    boolean updateSubmodules
    boolean runIntegrationTests
    boolean doBatsTests
    boolean checkEOL
    String registryConfig
    String registryConfigE
    String additionalDependencies
    String cypressImage
    String upgradeCypressImage
    List dependedDogus
    List additionalDogus
    List additionalComponents
    int waitForDepTime
    String namespace
    boolean doSonarTests

    String gitUserName
    String committerEmail
    String gcloudCredentials
    String sshCredentials
    String markdownVersion
    String agentStatic
    String agentVagrant
    String agentMultinode
    String releaseWebhookUrlSecret
    String latestTag = ""


    final String githubId = 'cesmarvin'
    final String machineType = 'n2-standard-8'
    final List<String> allowedReleaseUsers = ['fhuebner', 'mkannathasan', 'dschwarzer']
    final String shellcheckImage = "koalaman/shellcheck-alpine:v0.10.0"
    String jenkinsUser

    DoguPipe(script, Map config) {
        super(script)
        this.config = config

        // config map vars
        this.gitUserName            = config.gitUser ?: this.githubId
        this.committerEmail         = config.committerEmail ?: "${this.gitUserName}@cloudogu.com"
        this.gcloudCredentials      = config.gcloudCredentials ?: 'gcloud-ces-operations-internal-packer'
        this.sshCredentials         = config.sshCredentials ?: 'jenkins-gcloud-ces-operations-internal'
        this.markdownVersion        = config.markdownVersion ?: "3.11.0"
        this.agentStatic            = config.agentStatic ?: "sos"
        this.agentVagrant           = config.agentVagrant ?: "sos-stable"
        this.agentMultinode         = config.agentMultinode ?: "docker"
        this.doguName               = config.doguName
        this.doguDir                = config.doguDirectory ?: '/dogu'
        this.backendUser            = config.backendUser ?: 'cesmarvin-setup'
        this.shellScripts           = normalizeShellScripts(config.shellScripts).join(" ")
        this.updateSubmodules       = config.updateSubmodules ?: false
        this.runIntegrationTests    = config.runIntegrationTests ?: false
        this.doBatsTests            = config.doBatsTests ?: false
        this.registryConfig         = config.registryConfig ?: """"""
        this.registryConfigE        = config.registryConfigEncrypted ?: """"""
        this.additionalDependencies = config.additionalDependencies ?: """"""
        this.cypressImage           = config.cypressImage ?: "cypress/included:13.17.0"
        this.upgradeCypressImage    = config.upgradeCypressImage ?: "cypress/included:13.17.0"
        this.dependedDogus          = config.dependencies ?: []
        this.additionalDogus        = config.additionalDogus ?: []
        this.additionalComponents   = config.additionalComponents ?: []
        this.waitForDepTime         = config.waitForDepTime ?: 15
        this.namespace              = config.namespace ?: "official"
        this.doSonarTests           = config.doSonarTests ?: false
        this.releaseWebhookUrlSecret= config.releaseWebhookUrlSecret ?: "sos-sw-release-webhook-url"
        this.checkEOL               = config.checkEOL ?: true

        // Objects
        script.echo "[INFO] Authenticate git with ${gitUserName}"
        git = new Git(script, gitUserName)
        git.committerName = gitUserName
        git.committerEmail = committerEmail
        gitflow = new GitFlow(script, git)
        github = new GitHub(script, git)
        docker = new Docker(script)
        changelog = new Changelog(script)

        script.echo "[INFO] Init ecosystem object"

        ecoSystem = new EcoSystem(script, gcloudCredentials, sshCredentials)
        script.echo "[INFO] ecosystem object initialized"

        multiNodeEcoSystem = new MultiNodeEcoSystem(script, "jenkins_workspace_gcloud_key", "automatic_migration_coder_token", this.doguName)

        // Inject helper: sanitizeForLabel
        ecoSystem.metaClass.sanitizeForLabel = { String input ->
            if (!input) return "unknown"

            input = input.replaceAll(/%2[fF]/, "/")
                        .replaceAll(/%20/, "-")
                        .replaceAll(/%3[aA]/, ":")
                        .replaceAll(/%/, "")
                        .toLowerCase()
                        .replaceAll(/[^a-z0-9_-]/, "-")

            return input.length() > 63 ? input.substring(0, 63) : input
        }

        // Inject helper: getJenkinsUser
        ecoSystem.metaClass.getJenkinsUser = {
            def cause = script.currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause)
            return cause ? cause.getUserId() : "unknown"
        }
        this.jenkinsUser = ecoSystem.getJenkinsUser()

        // Inject helper: getPipelineName
        ecoSystem.metaClass.getPipelineName = {
            def jobName = script.env.JOB_NAME ?: "unknown"
            return ecoSystem.sanitizeForLabel(jobName)
        }

        // overriding vagrant configuration so that sos image is used and labels set
        ecoSystem.metaClass.writeVagrantConfiguration = { String mountPath, String machineType = machineType ->
        def pipelineName = ecoSystem.getPipelineName()
        script.writeFile file: 'Vagrantfile', text: """
Vagrant.require_version ">= 1.9.0"

gcloud_key = ENV["GCLOUD_SA_KEY"]

file = File.read(gcloud_key)
data_hash = JSON.parse(file)

project_id = data_hash["project_id"]

Vagrant.configure(2) do |config|

  config.vm.box = "google/gce"

  config.vm.provider :google do |google, override|
    google.google_project_id = project_id
    google.google_json_key_location = gcloud_key

    google.image_family = 'sos-development2404'
    google.zone = "europe-west3-a"
    google.machine_type = "${machineType}"
    google.disk_type = "pd-ssd"
    # preemptible
    google.preemptible = true
    google.auto_restart = false
    google.on_host_maintenance = "TERMINATE"

    google.name = "ces-dogu-" + Time.now.to_i.to_s

    google.labels = {
    "vm_name" => "ces-dogu-vagrant",
    "user" => "${this.jenkinsUser}",
    "pipeline_name" => "${pipelineName}"
    }

    google.tags = ["http-server", "https-server", "setup"]

    google.disk_size = 100

    override.ssh.username = ENV["SSH_USERNAME"]
    override.ssh.private_key_path = ENV["SSH_KEY"]
  end

  config.vm.synced_folder ".", "/vagrant", disabled: true
  config.vm.synced_folder "ecosystem", "/vagrant", type: "rsync", rsync__exclude: [".git/", "images/"]
  config.vm.synced_folder ".", "${mountPath}"
  config.vm.provision "shell",
    inline: "mkdir -p /etc/ces && echo 'gcloud-vagrant' > /etc/ces/type && /vagrant/install.sh"

end
"""
        }

        vagrant = new Vagrant(script, gcloudCredentials, sshCredentials)
        markdown = new Markdown(script, markdownVersion)
        // markdown.metaClass.check = {
        //     docker.image("ghcr.io/tcort/markdown-link-check:${this.tag}")
        //         .mountJenkinsUser()
        //         .inside("--entrypoint=\"\" -v ${this.script.env.WORKSPACE}/docs:/docs") {
        //             this.script.sh '''
        //                 echo '{
        //                   "retry": {
        //                     "retries": 3,
        //                     "minTimeout": 1000
        //                   },
        //                   "timeout": 10000
        //                 }' > /docs/tmp-config.json
        
        //                 find /docs -name \\*.md -print0 | \
        //                 xargs -0 -n1 -I {} markdown-link-check -v -c /docs/tmp-config.json {}
        //             '''
        //         }
        // }
    }
    
    @Override
    void addDefaultStages() {
        // Load mode
        String pipelineMode = script.params.pipelineMode ?: "FULL"
        script.echo "[INFO] Pipeline mode selected: ${pipelineMode}"

        // local vars
        String releaseTargetBranch = ""
        String releaseVersion = ""
        String developmentBranch = "develop"

        addStageGroup(this.agentStatic) { group ->

            group.stage("Checkout", PipelineMode.STATIC) {
                checkout_updatemakefiles(updateSubmodules)
            }

            group.stage("Lint", PipelineMode.STATIC) {
                script.lintDockerfile()
            }

            if (config.checkMarkdown) {
                group.stage('Check Markdown Links', PipelineMode.STATIC) {
                    try {
                        markdown.check()
                    } catch (Exception firstFailure) {
                        script.echo "[WARN] Markdown check failed. Retrying in 5 seconds..."
                        script.sleep 5
                        try {
                            markdown.check()
                        } catch (Exception secondFailure) {
                            script.error "[ERROR] Markdown check failed after retry: ${secondFailure.message}"
                        }
                    }
                }
            }
            
            if (shellScripts) {
                group.stage("Shellcheck", PipelineMode.STATIC) {
                    def shellCheck = { Object... args ->
                        def runWith = { String files ->
                            script.echo "[INFO] Overridden shellCheck using ${shellcheckImage}"
                            script.echo "[INFO] Shellcheck Files ${files}"
                            script.docker.image(shellcheckImage).inside {
                                script.sh "/bin/shellcheck ${files}"
                            }
                        }
            
                        if (args && args[0]) {
                            script.echo "[INFO] args ${args}"

                            // one-arg form
                            runWith(args)
                        } else {
                            // no-arg form
                            def out = script.sh(
                                script: 'find . -path ./ecosystem -prune -o -type f -regex .*\\.sh -print',
                                returnStdout: true
                            ).trim()
                            if (!out) {
                                script.echo "[INFO] No .sh files found; skipping shellcheck."
                                return
                            }
                            def fileList = '"' + out.replaceAll('\n','" "') + '"'
                            script.echo "[INFO] fileList ${fileList}."

                            runWith(fileList)
                        }
                    }
            
                    shellCheck(shellScripts)
                }
            }

            if (config.runShellTests) {
                group.stage("Shell Tests", PipelineMode.STATIC) {
                    executeShellTests()
                }
            }

            if (doBatsTests) {
                group.stage("Bats Tests", PipelineMode.STATIC) {
                    Bats bats = new Bats(script, script.docker)
                    bats.checkAndExecuteTests()
                }
            }

            if (doSonarTests) {
                group.stage('SonarQube', PipelineMode.STATIC) {
                    script.sh "git config 'remote.origin.fetch' '+refs/heads/*:refs/remotes/origin/*'"
                    gitWithCredentials("fetch --all")
                    def currentBranch = "${script.env.BRANCH_NAME}"

                    def scannerHome = script.tool name: 'sonar-scanner', type: 'hudson.plugins.sonar.SonarRunnerInstallation'
                    script.withSonarQubeEnv {
                        if (currentBranch == "main") {
                            script.echo "This branch has been detected as the production branch."
                            script.sh "${scannerHome}/bin/sonar-scanner -Dsonar.branch.name=${script.env.BRANCH_NAME}"
                        }
                        else if (currentBranch == "master") {
                            script.echo "This branch has been detected as the production branch."
                            script.sh "${scannerHome}/bin/sonar-scanner -Dsonar.branch.name=${script.env.BRANCH_NAME}"
                        } else if (currentBranch == developmentBranch) {
                            script.echo "This branch has been detected as the development branch."
                            script.sh "${scannerHome}/bin/sonar-scanner -Dsonar.branch.name=${script.env.BRANCH_NAME}"
                        } else if (script.env.CHANGE_TARGET) {
                            script.echo "This branch has been detected as a pull request."
                            script.sh "${scannerHome}/bin/sonar-scanner -Dsonar.pullrequest.key=${script.env.CHANGE_ID} -Dsonar.pullrequest.branch=${script.env.CHANGE_BRANCH} -Dsonar.pullrequest.base=${developmentBranch}"
                        } else if (currentBranch.startsWith("feature/")) {
                            script.echo "This branch has been detected as a feature branch."
                            script.sh "${scannerHome}/bin/sonar-scanner -Dsonar.branch.name=${script.env.BRANCH_NAME}"
                        } else {
                            script.echo "This branch has been detected as a miscellaneous branch."
                            script.sh "${scannerHome}/bin/sonar-scanner -Dsonar.branch.name=${script.env.BRANCH_NAME} "
                        }
                    }
                    script.timeout(time: 2, unit: 'MINUTES') { // Needed when there is no webhook for example
                        def qGate = script.waitForQualityGate()
                        if (qGate.status != 'OK') {
                            script.unstable("Pipeline unstable due to SonarQube quality gate failure")
                        }
                    }
                }
            }
        }

        addStageGroup(this.agentMultinode) { group ->

            group.stage("Checkout", PipelineMode.INTEGRATIONMULTINODE) {
                checkout_updatemakefiles(updateSubmodules)
            }

            group.stage('MN-Setup', PipelineMode.INTEGRATIONMULTINODE) {
                def defaultSetupConfig = [
                        clustername: script.params.ClusterName,
                        additionalDogus: [],
                        additionalComponents: []
                ]
                additionalDogus.each { d ->
                    if (!defaultSetupConfig.additionalDogus.contains(d)) {
                        defaultSetupConfig.additionalDogus << d
                    }
                }
                additionalComponents.each { c ->
                    if (!defaultSetupConfig.additionalComponents.contains(c)) {
                        defaultSetupConfig.additionalComponents << c
                    }
                }
                if (script.params.TestDoguUpgrade) {
                    if (script.params.OldDoguVersionForUpgradeTest?.trim() && !script.params.OldDoguVersionForUpgradeTest.contains('v')) {
                        script.echo "Installing user-defined version of dogu: ${script.params.OldDoguVersionForUpgradeTest}"
                        defaultSetupConfig.additionalDogus << "${namespace}/${doguName}@${script.params.OldDoguVersionForUpgradeTest}"
                    } else {
                        script.echo "Installing latest released version of dogu..."
                        defaultSetupConfig.additionalDogus << "${namespace}/${doguName}"
                    }
                }
                multiNodeEcoSystem.setup(defaultSetupConfig)
            }

            group.stage('MN-Build', PipelineMode.INTEGRATIONMULTINODE) {
                script.env.NAMESPACE="ecosystem"
                script.env.RUNTIME_ENV="remote"
                multiNodeEcoSystem.build(doguName)
            }

            group.stage ("MN-Wait for Dogu", PipelineMode.INTEGRATIONMULTINODE) {
                multiNodeEcoSystem.waitForDogu(doguName)
            }

            group.stage ("MN-Verify", PipelineMode.INTEGRATIONMULTINODE) {
                multiNodeEcoSystem.verify(doguName)
            }

            if (runIntegrationTests) {
                group.stage("MN-Run Integration Tests", PipelineMode.INTEGRATIONMULTINODE) {
                    multiNodeEcoSystem.runCypressIntegrationTests([
                            cypressImage     : upgradeCypressImage,
                            enableVideo      : script.params.EnableVideoRecording,
                            enableScreenshots: script.params.EnableScreenshotRecording
                    ])
                }
            }

            // you can keep the cluster for later inspection  default: false
            if (!script.params.KeepCluster) {
                // this stage must be named "Clean" to get executed in any case at the end of the pipeline
                group.stage("Clean") {
                    multiNodeEcoSystem.destroy()
                }
            }
        } // end of Multinode-Integration-Test-Piplinebranch

        addStageGroup(this.agentVagrant) { group ->
            group.stage("Checkout", EnumSet.of(PipelineMode.INTEGRATION)) {
                checkout_updatemakefiles(updateSubmodules)
            }

        group.raw_stage('Make dogu Release', PipelineMode.RELEASE) {
            if (!allowedReleaseUsers.contains(this.jenkinsUser)) {
                script.error("User '${this.jenkinsUser}' is not authorized to run a release!")
            }

            if (!script.params.ReleaseTag?.trim()) {
                script.error("ReleaseTag must be provided in RELEASE mode!")
            }

            script.checkout script.scm
            script.sh "git config 'remote.origin.fetch' '+refs/heads/*:refs/remotes/origin/*'"
            gitWithCredentials("fetch --all")
            if (updateSubmodules) {
                script.sh 'git submodule update --init'
            }

            def releaseTagRaw = script.params.ReleaseTag?.trim()
            def releaseTag = releaseTagRaw.replaceFirst(/^v/, '')

            script.sh '''
                mkdir -p .bin
                curl -L https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64 -o .bin/yq
                chmod +x .bin/yq
                sudo apt install git-flow -y
            '''

            script.withCredentials([script.usernamePassword(
                credentialsId: this.gitUserName,
                usernameVariable: 'GIT_AUTH_USR',
                passwordVariable: 'GIT_AUTH_PSW'
            )]) {
                script.sh """
                    git config credential.helper '!f() { echo username=\$GIT_AUTH_USR; echo password=\$GIT_AUTH_PSW; }; f'
                    git fetch origin +refs/heads/*:refs/remotes/origin/*

                    if git show-ref --verify --quiet refs/remotes/origin/main; then
                        echo main > release_target.txt
                    elif git show-ref --verify --quiet refs/remotes/origin/master; then
                        echo master > release_target.txt
                    else
                        echo "Neither main nor master found!" >&2
                        exit 1
                    fi
                """

                def target = script.readFile('release_target.txt').trim()

                script.sh """
                    git checkout ${target}
                    git checkout develop
                """


            script.withEnv(["RELEASE_TAG=${releaseTag}"]) {
                script.sh '''
                        cat > ./git-askpass.sh <<'EOF'
#!/bin/sh
case "$1" in
  Username*) echo "$GIT_AUTH_USR" ;;
  Password*) echo "$GIT_AUTH_PSW" ;;
esac
EOF
                chmod +x ./git-askpass.sh
                export GIT_ASKPASS=./git-askpass.sh

                {
                    echo "$RELEASE_TAG"
                    yes ok
                } | make dogu-release
            '''
                    }
                }
            }

            group.stage("Provision", PipelineMode.INTEGRATION) {
                if (gitflow.isPreReleaseBranch()) {
                    script.sh "make prerelease_namespace"
                }
                ecoSystem.provision(doguDir, machineType)
            }

            group.stage("Setup", PipelineMode.INTEGRATION) {
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
                group.stage("Wait for dependencies", PipelineMode.INTEGRATION) {
                    script.timeout(time: waitForDepTime, unit: 'MINUTES') {
                        dependedDogus.each { dep ->
                            ecoSystem.waitForDogu(dep)
                        }
                    }
                }
            }

            group.stage("Build", PipelineMode.INTEGRATION) {
                def rawPreinstalled = ecoSystem.defaultSetupConfig["dependencies"].collect { it.split("/")[1] }
                if (rawPreinstalled.contains(doguName) && gitflow.isPreReleaseBranch()) {
                    ecoSystem.purgeDogu(doguName, "--keep-config --keep-volumes --keep-service-accounts --keep-logs")
                }
                ecoSystem.build(doguDir)
            }

            group.stage("Trivy scan", PipelineMode.INTEGRATION) {
                ecoSystem.copyDoguImageToJenkinsWorker(doguDir)
                Trivy trivy = new Trivy(script)
                if (!config.checkEOL) {
                    trivy.metaClass.scanImage = {
                        String imageName,
                        String severityLevel = TrivySeverityLevel.CRITICAL,
                        String strategy = TrivyScanStrategy.UNSTABLE,
                        // Avoid rate limits of default Trivy database source
                        String additionalFlags = "--db-repository public.ecr.aws/aquasecurity/trivy-db --java-db-repository public.ecr.aws/aquasecurity/trivy-java-db",
                        String trivyReportFile = "trivy/trivyReport.json" ->
                        
                        script.echo "[DEBUG] trivy.metaClass.scanImage overwritten"
                        String trivyVersion = "0.57.1"
                        String trivyImage = "aquasec/trivy"
                        String trivyDirectory = "trivy"                       
                        String script_str = "trivy image --exit-code 10 --exit-on-eol 0 --format ${TrivyScanFormat.JSON} -o ${trivyReportFile} --severity ${severityLevel} ${additionalFlags} ${imageName}"
                        script.echo "[DEBUG] script_str: ${script_str}"
                        Integer exitCode = docker.image("${trivyImage}:${trivyVersion}") 
                        .mountJenkinsUser()
                        .mountDockerSocket()
                        .inside("-v ${script.env.WORKSPACE}/.trivy/.cache:/root/.cache/") {
                            // Write result to $trivyReportFile in json format (--format json), which can be converted in the saveFormattedTrivyReport function
                            // Exit with exit code 10 if vulnerabilities are found or OS is so old that Trivy has no records for it anymore
                            script.sh("mkdir -p " + trivyDirectory)
                            script.sh(script: script_str, returnStatus: true)
                        }
                            switch (exitCode) {
                                case 0:
                                    // Everything all right, no vulnerabilities
                                    return true
                                case 10:
                                    // Found vulnerabilities
                                    // Set build status according to strategy
                                    switch (strategy) {
                                        case TrivyScanStrategy.IGNORE:
                                            break
                                        case TrivyScanStrategy.UNSTABLE:
                                            script.archiveArtifacts artifacts: "${trivyReportFile}", allowEmptyArchive: true
                                            script.unstable("Trivy has found vulnerabilities in image " + imageName + ". See " + trivyReportFile)
                                            break
                                        case TrivyScanStrategy.FAIL:
                                            script.archiveArtifacts artifacts: "${trivyReportFile}", allowEmptyArchive: true
                                            script.error("Trivy has found vulnerabilities in image " + imageName + ". See " + trivyReportFile)
                                            break
                                    }
                                    return false
                                default:
                                    script.error("Error during trivy scan; exit code: " + exitCode)
                            }
                    }
                }
                                
                trivy.scanDogu(".", script.params.TrivySeverityLevels, script.params.TrivyStrategy)
                trivy.saveFormattedTrivyReport(TrivyScanFormat.TABLE)
                trivy.saveFormattedTrivyReport(TrivyScanFormat.JSON)
                trivy.saveFormattedTrivyReport(TrivyScanFormat.HTML)
            }

            group.stage("Verify", PipelineMode.INTEGRATION) {
                ecoSystem.verify(doguDir)
            }

            if (runIntegrationTests) {
                group.stage("Integration Tests", PipelineMode.INTEGRATION) {
                    runCypress(ecoSystem, cypressImage)
                }
            }

            if (script.params.TestDoguUpgrade) {
                group.stage("Upgrade Dogu", PipelineMode.INTEGRATION) {
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
                        script.stage("Integration Tests - After Upgrade", PipelineMode.INTEGRATION) {
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
                group.stage('Retrieving Release Branch') {
                    script.withCredentials([script.usernamePassword(
                        credentialsId: this.gitUserName,
                        usernameVariable: 'GIT_AUTH_USR',
                        passwordVariable: 'GIT_AUTH_PSW'
                    )]) {
                        script.sh """
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
                        releaseVersion = git.getSimpleBranchName()
                        releaseTargetBranch = script.readFile('release_target.txt').trim()
                        script.echo "[DEBUG] release branch: ${releaseTargetBranch}"
                    }
                }
                group.stage('Finish Release') {
                    // Optionally, target branch can be provided (default "main")
                    gitflow.finishRelease(releaseVersion, releaseTargetBranch)
                }
                group.stage('Push Dogu to registry') {
                    ecoSystem.push(doguDir)
                }
                group.stage('Add Github-Release') {
                    github.createReleaseWithChangelog(releaseVersion, changelog, releaseTargetBranch)
                }
                group.stage('Notfiy Webhook - Release') {
                    notifyRelease()
                }
            } else if (gitflow.isPreReleaseBranch()) {
                group.stage("Push Prerelease Dogu to registry") {
                    ecoSystem.pushPreRelease(doguDir)
                }
            }

            group.stage("Clean") {
                ecoSystem.destroy()
            }
        }
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

    @Override
    void setBuildProperties(List<ParameterDefinition> customParams = null) {
        setupEnvironment()
        // Dynamically build the choices list
        def pipelineModeChoices = ['FULL', 'STATIC', 'INTEGRATION', 'INTEGRATIONMULTINODE']
        def defaultParams = []
        def currentBranch = script.env.BRANCH_NAME
        script.echo "[DEBUG] setBuildProperties, currentBranch: ${currentBranch}"

        if (currentBranch == 'develop') {
            pipelineModeChoices << 'RELEASE'
            defaultParams = [
                script.choice(
                    name: 'PipelineMode',
                    choices: pipelineModeChoices,
                    defaultValue: 'FULL',
                    description: 'Select pipeline mode'
                ),
                script.string(
                    name: 'ReleaseTag',
                    defaultValue: '',
                    description:"Only required if PipelineMode=RELEASE. Enter new release tag (latest: ${this.latestTag})."
                ),
                script.booleanParam(name: 'TestDoguUpgrade', defaultValue: false, description: 'Test dogu upgrade from latest release or optionally from defined version below'),
                script.booleanParam(name: 'EnableVideoRecording', defaultValue: true, description: 'Enables cypress to record video of the integration tests.'),
                script.booleanParam(name: 'EnableScreenshotRecording', defaultValue: true, description: 'Enables cypress to take screenshots of failing integration tests.'),
                script.string(name: 'OldDoguVersionForUpgradeTest', defaultValue: '', description: 'Old Dogu version for the upgrade test (optional; e.g. 4.1.0-3)'),
                script.choice(name: 'TrivySeverityLevels', choices: [TrivySeverityLevel.CRITICAL, TrivySeverityLevel.HIGH_AND_ABOVE, TrivySeverityLevel.MEDIUM_AND_ABOVE, TrivySeverityLevel.ALL], description: 'The levels to scan with trivy'),
                script.choice(name: 'TrivyStrategy', choices: [TrivyScanStrategy.UNSTABLE, TrivyScanStrategy.FAIL, TrivyScanStrategy.IGNORE], description: 'What to do if vulnerabilities are found'),
                script.string(name: 'ClusterName', defaultValue: '', description: 'Optional: Name of the multinode integration test cluster. A new instance gets created if this parameter is not supplied'),
                script.booleanParam(name: 'KeepCluster', defaultValue: false, description: 'Optional: If True, the cluster will not be deleted after the build execution'),
            ]
        } else {
            defaultParams = [
                script.choice(
                    name: 'PipelineMode',
                    choices: pipelineModeChoices,
                    defaultValue: 'FULL',
                    description: 'Select pipeline mode'
                ),
                script.booleanParam(name: 'TestDoguUpgrade', defaultValue: false, description: 'Test dogu upgrade from latest release or optionally from defined version below'),
                script.booleanParam(name: 'EnableVideoRecording', defaultValue: true, description: 'Enables cypress to record video of the integration tests.'),
                script.booleanParam(name: 'EnableScreenshotRecording', defaultValue: true, description: 'Enables cypress to take screenshots of failing integration tests.'),
                script.string(name: 'OldDoguVersionForUpgradeTest', defaultValue: '', description: 'Old Dogu version for the upgrade test (optional; e.g. 4.1.0-3)'),
                script.choice(name: 'TrivySeverityLevels', choices: [TrivySeverityLevel.CRITICAL, TrivySeverityLevel.HIGH_AND_ABOVE, TrivySeverityLevel.MEDIUM_AND_ABOVE, TrivySeverityLevel.ALL], description: 'The levels to scan with trivy'),
                script.choice(name: 'TrivyStrategy', choices: [TrivyScanStrategy.UNSTABLE, TrivyScanStrategy.FAIL, TrivyScanStrategy.IGNORE], description: 'What to do if vulnerabilities are found'),
                script.string(name: 'ClusterName', defaultValue: '', description: 'Optional: Name of the multinode integration test cluster. A new instance gets created if this parameter is not supplied'),
                script.booleanParam(name: 'KeepCluster', defaultValue: false, description: 'Optional: If True, the cluster will not be deleted after the build execution'),
            ]            
        }

        script.properties([
            script.buildDiscarder(script.logRotator(numToKeepStr: '10')),
            script.disableConcurrentBuilds(),
            script.parameters(customParams ?: defaultParams)
        ])
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
        def githubApiUrl = "https://api.github.com/repos/cloudogu/${doguName}/releases/latest"

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
                return json.tag_name ?: "unknown"
            }
        } catch (Exception e) {
            script.echo "Failed to fetch release version: ${e}"
            return "unknown"
        }
    }

    void checkout_updatemakefiles(boolean updateSubmodules) {
        script.checkout script.scm
        if (updateSubmodules) {
            script.sh 'git submodule update --init'
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
    @NonCPS
    private List<String> normalizeShellScripts(def input) {
        if (!input) {
            return []
        }

        if (input instanceof String) {
            return input.trim().split(/\s+/) as List
        }

        if (input instanceof List) {
            if (input.size() == 1 && input[0] instanceof String) {
                return input[0].trim().split(/\s+/) as List
            }
            return input
        }

        throw new IllegalArgumentException("Unsupported shellScripts format: ${input.getClass()}")
    }

    void setupEnvironment() {
        this.latestTag = fetchLatestTagInNode(script, this.gitUserName, this.doguName)
    }   

    private static String fetchLatestTagInNode(def script, String gitUserName, String doguName) {
        String tag = "unknown"
    
        script.node {
            script.withCredentials([script.usernamePassword(
                credentialsId: gitUserName,
                usernameVariable: 'GIT_AUTH_USR',
                passwordVariable: 'GIT_AUTH_PSW'
            )]) {
                script.sh "rm -rf repo && mkdir repo"
    
                script.dir('repo') {
                    script.sh """
                        git clone https://${'$'}GIT_AUTH_USR:${'$'}GIT_AUTH_PSW@github.com/cloudogu/${doguName}.git .
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

}

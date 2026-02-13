package com.cloudogu.sos.pipebuildlib.dogu

import com.cloudogu.ces.cesbuildlib.*
import com.cloudogu.ces.dogubuildlib.*

/**
 * DoguConfig is the compiled, runtime-ready configuration for a Dogu pipeline:
 * it takes the raw Jenkinsfile config map, applies defaults and normalization,
 * instantiates all required build systems (Git, EcoSystem, Docker, Vagrant, etc.),
 * and injects pipeline-specific helpers into them so that DoguPipe and all stage
 * modules operate on a fully initialized, self-contained build environment.
 */
class DoguConfig {

    EcoSystem ecoSystem
    MultiNodeEcoSystem multiNodeEcoSystem
    Git git
    GitFlow gitflow
    GitHub github
    Docker docker
    Changelog changelog
    Vagrant vagrant
    Markdown markdown
    Makefile makefile
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
    boolean checkMarkdown
    boolean runShellTests
    String latestTag = ""
    String defaultBranch

    String gitUserName
    String committerEmail
    String gcloudCredentials
    String sshCredentials
    String markdownVersion
    String agentStatic
    String agentVagrant
    String agentMultinode
    String releaseWebhookUrlSecret

    final String githubId = 'cesmarvin'
    final String machineType = 'n2-standard-8'
    final List<String> allowedReleaseUsers = ['fhuebner', 'mkannathasan', 'dschwarzer']
    final String shellcheckImage = "koalaman/shellcheck-alpine:v0.10.0"
    String jenkinsUser

    DoguConfig(script, Map config) {
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
        this.checkMarkdown          = config.checkMarkdown ?: false
        this.runShellTests          = config.runShellTests ?: false
        this.defaultBranch          = config.defaultBranch ?: "main"


        // Objects
        script.echo "[INFO] Authenticate git with ${gitUserName}"
        git = new Git(script, gitUserName)
        git.committerName = gitUserName
        git.committerEmail = committerEmail
        gitflow = new GitFlow(script, git)
        github = new GitHub(script, git)
        docker = new Docker(script)
        changelog = new Changelog(script)
        makefile = new Makefile(script)

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

        ecoSystem.metaClass.build = { String doguPath ->
            vagrant.ssh "sudo cp /root/cesapp /usr/sbin/cesapp && sudo cesapp build --buildx ${doguPath}"
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
    google.preemptible = false
    google.auto_restart = false
    google.on_host_maintenance = "TERMINATE"

    google.name = "ces-dogu-" + Time.now.to_i.to_s

    google.labels = {
    "vm_name" => "ces-dogu-vagrant",
    "user" => "${this.jenkinsUser}",
    "pipeline_name" => "${pipelineName}"
    }

    google.tags = ["http-server", "https-server", "setup"]

    google.disk_size = 150

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
}

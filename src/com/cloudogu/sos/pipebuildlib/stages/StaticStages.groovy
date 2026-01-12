package com.cloudogu.sos.pipebuildlib.stages

import com.cloudogu.sos.pipebuildlib.StageGroup
import com.cloudogu.sos.pipebuildlib.DoguPipe

import com.cloudogu.ces.cesbuildlib.*
import com.cloudogu.ces.dogubuildlib.*

class StaticStages implements DoguStageModule {

    void register(DoguPipe pipe, StageGroup group) {

        // literally paste your existing code here
        group.stage('Checkout', PipelineMode.STATIC) {
            pipe.checkout_updatemakefiles(pipe.updateSubmodules)
        }

        group.stage('Lint', PipelineMode.STATIC) {
            pipe.script.lintDockerfile()
        }

        if (pipe.config.checkMarkdown) {
            group.stage('Check Markdown Links', PipelineMode.STATIC) {
                try {
                    pipe.markdown.check()
                } catch (Exception firstFailure) {
                    pipe.script.echo '[WARN] Markdown check failed. Retrying in 5 seconds...'
                    pipe.script.sleep 5
                    try {
                        pipe.markdown.check()
                    } catch (Exception secondFailure) {
                        pipe.script.error "[ERROR] Markdown check failed after retry: ${secondFailure.message}"
                    }
                }
            }
        }

        if (pipe.shellScripts) {
            group.stage('Shellcheck', PipelineMode.STATIC) {
                def shellCheck = { Object... args ->
                    def runWith = { String files ->
                        pipe.script.echo "[INFO] Overridden shellCheck using ${pipe.shellcheckImage}"
                        pipe.script.echo "[INFO] Shellcheck Files ${files}"
                        pipe.script.docker.image(pipe.shellcheckImage).inside {
                            pipe.script.sh "/bin/shellcheck ${files}"
                        }
                    }

                    if (args && args[0]) {
                        pipe.script.echo "[INFO] args ${args}"
                        runWith(args)
                    } else {
                        def out = pipe.script.sh(
                            script: 'find . -path ./ecosystem -prune -o -type f -regex .*\\.sh -print',
                            returnStdout: true
                        ).trim()
                        if (!out) {
                            pipe.script.echo "[INFO] No .sh files found; skipping shellcheck."
                            return
                        }
                        def fileList = '"' + out.replaceAll('\n','" "') + '"'
                        pipe.script.echo "[INFO] fileList ${fileList}."
                        runWith(fileList)
                    }
                }
                shellCheck(pipe.shellScripts)
            }
        }

        if (pipe.config.runShellTests) {
            group.stage("Shell Tests", PipelineMode.STATIC) {
                executeShellTests()
            }
        }

        if (pipe.doBatsTests) {
            group.stage("Bats Tests", PipelineMode.STATIC) {
                Bats bats = new Bats(pipe.script, pipe.script.docker)
                bats.checkAndExecuteTests()
            }
        }

        if (pipe.doSonarTests) {
            group.stage('SonarQube', PipelineMode.STATIC) {
                pipe.script.sh "git config 'remote.origin.fetch' '+refs/heads/*:refs/remotes/origin/*'"
                pipe.gitWithCredentials("fetch --all")
                def currentBranch = "${pipe.script.env.BRANCH_NAME}"

                def scannerHome = pipe.script.tool name: 'sonar-scanner', type: 'hudson.plugins.sonar.SonarRunnerInstallation'
                pipe.script.withSonarQubeEnv {
                    if (currentBranch == "main") {
                        pipe.script.sh "${scannerHome}/bin/sonar-scanner -Dsonar.branch.name=${pipe.script.env.BRANCH_NAME}"
                    }
                    else if (currentBranch == "master") {
                        pipe.script.sh "${scannerHome}/bin/sonar-scanner -Dsonar.branch.name=${pipe.script.env.BRANCH_NAME}"
                    }
                    else if (currentBranch == "develop") {
                        pipe.script.sh "${scannerHome}/bin/sonar-scanner -Dsonar.branch.name=${pipe.script.env.BRANCH_NAME}"
                    }
                    else if (pipe.script.env.CHANGE_TARGET) {
                        pipe.script.sh "${scannerHome}/bin/sonar-scanner -Dsonar.pullrequest.key=${pipe.script.env.CHANGE_ID} -Dsonar.pullrequest.branch=${pipe.script.env.CHANGE_BRANCH} -Dsonar.pullrequest.base=develop"
                    }
                    else {
                        pipe.script.sh "${scannerHome}/bin/sonar-scanner -Dsonar.branch.name=${pipe.script.env.BRANCH_NAME}"
                    }
                }

                pipe.script.timeout(time: 2, unit: 'MINUTES') {
                    def qGate = pipe.script.waitForQualityGate()
                    if (qGate.status != 'OK') {
                        pipe.script.unstable("Pipeline unstable due to SonarQube quality gate failure")
                    }
                }
            }
        }
    }
}

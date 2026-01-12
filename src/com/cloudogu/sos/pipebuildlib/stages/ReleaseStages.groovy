package com.cloudogu.sos.pipebuildlib.stages

import com.cloudogu.sos.pipebuildlib.StageGroup
import com.cloudogu.sos.pipebuildlib.DoguPipe

import com.cloudogu.ces.cesbuildlib.*
import com.cloudogu.ces.dogubuildlib.*

class ReleaseStages implements DoguStageModule {

    void register(DoguPipe pipe, StageGroup group) {
        String releaseTargetBranch = ''
        String releaseVersion = ''

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
    }
}

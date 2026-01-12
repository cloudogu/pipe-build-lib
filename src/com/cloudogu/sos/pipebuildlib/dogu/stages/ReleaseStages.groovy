package com.cloudogu.sos.pipebuildlib.stages

import com.cloudogu.sos.pipebuildlib.*
import com.cloudogu.sos.pipebuildlib.dogu.*
import com.cloudogu.ces.cesbuildlib.*
import com.cloudogu.ces.dogubuildlib.*

class ReleaseStages implements DoguStageModule {

    void register(DoguPipe pipe, StageGroup group) {

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
    }
}

// src/com/pipebuildlib/PostgresDoguPipeline.groovy
package com.pipebuildlib

class PostgresDoguPipe extends DoguPipe {
    PostgresDoguPipe(script, Map config) {
        super(script, config)

        overrideStage("Build") {
            script.echo "Custom Postgres build logic for ${config.doguName}"
        }

        insertStageAfter("Verify", "Post-Verify Hook") {
            script.echo "Post-verification for Postgres"
        }
    }
}

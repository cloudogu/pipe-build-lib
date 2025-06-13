package com.pipebuildlib

abstract class BasePipe implements Serializable {

    protected final def script
    protected final List<StageDefinition> stages = []
    protected final String defaultAgent = "sos"

    BasePipe(script) {
        this.script = script
    }

    void addStage(String name,
                  Closure block,
                  String agentLabel = defaultAgent,
                  boolean parallel = false) {
        stages << new StageDefinition(name, block, agentLabel, parallel)
    }

    void insertStageAfter(String afterName,
                          String newName,
                          Closure block,
                          String agentLabel = defaultAgent,
                          boolean parallel = false) {
        def normAfter = normalizeStageName(afterName)
        def index = stages.findIndexOf { normalizeStageName(it.name) == normAfter }

        if (index >= 0) {
            if (hasStage(newName)) {
                throw new IllegalArgumentException("Stage with name '${newName}' already exists.")
            }
            stages.add(index + 1, new StageDefinition(newName, block, agentLabel, parallel))
        } else {
            script.echo "[Info] inserting stage failed, stage '${afterName}' not found"
        }
    }

    void overrideStage(String name,
                       Closure newBlock,
                       String newAgentLabel = null,
                       Boolean newParallel = null) {
        def stage = findStage(name)
        if (stage) {
            stage.block = newBlock
            if (newAgentLabel != null) stage.agentLabel = newAgentLabel
            if (newParallel != null) stage.parallel = newParallel
        } else {
            script.echo "[Warning] Tried to override non-existent stage '${name}'"
        }
    }

    void run() {
        // Group stages by agent, preserving order
        def stagesByAgent = [:].withDefault { [] }
        stages.each { stageDef ->
            stagesByAgent[stageDef.agentLabel ?: defaultAgent] << stageDef
        }

        stagesByAgent.each { agent, stageList ->
            script.node(agent) {
                script.timestamps {
                    def parallelStages = [:]
                    stageList.each { s ->
                        if (s.parallel) {
                            parallelStages[s.name] = {
                                script.stage(s.name) {
                                    s.block.call()
                                }
                            }
                        } else {
                            if (!parallelStages.isEmpty()) {
                                script.parallel parallelStages
                                parallelStages.clear()
                            }
                            script.stage(s.name) {
                                s.block.call()
                            }
                        }
                    }
                    // Handle any remaining parallel stages
                    if (!parallelStages.isEmpty()) {
                        script.parallel parallelStages
                    }
                }
            }
        }
    }

    protected String normalizeStageName(String name) {
        return name.toLowerCase().replaceAll(/\s+/, '')
    }

    protected boolean hasStage(String name) {
        def norm = normalizeStageName(name)
        return stages.any { stage -> normalizeStageName(stage.name) == norm }
    }

    protected StageDefinition findStage(String name) {
        def norm = normalizeStageName(name)
        return stages.find { stage -> normalizeStageName(stage.name) == norm }
    }

}

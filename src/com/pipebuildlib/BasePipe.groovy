package com.pipebuildlib

abstract class BasePipe implements Serializable {

    protected final def script
    protected final List<StageDefinition> stages = []
    protected final String defaultAgent = "sos"

    BasePipe(script) {
        this.script = script
        if (this.script) {
            this.script.echo "[DEBUG] BasePipe constructor: script set successfully"
        } else {
            println "[ERROR] BasePipe constructor: script is NULL"
        }
    }

    void addStage(String name,
                  Closure block,
                  String agentLabel = defaultAgent,
                  boolean parallel = false) {
        if (script) {
            script.echo "[DEBUG] Adding stage '${name}' on agent '${agentLabel}', parallel=${parallel}"
        } else {
            println "[ERROR] Tried to add stage '${name}', but script is NULL"
        }

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
        if (script) {
            script.echo "[DEBUG] Starting pipeline with ${stages.size()} stage(s)"
        } else {
            println "[ERROR] Cannot run pipeline: script is NULL"
        }

        if (stages.isEmpty()) {
            script.echo "[WARN] No stages to run"
            return
        }

        def stagesByAgent = [:].withDefault { [] }
        stages.each { stageDef ->
            script.echo "[DEBUG] Assigning stage '${stageDef.name}' to agent '${stageDef.agentLabel}'"
            stagesByAgent[stageDef.agentLabel ?: defaultAgent] << stageDef
        }

        stagesByAgent.each { agent, stageList ->
            script.echo "[DEBUG] Executing stages on agent '${agent}'"
            script.node(agent) {
                script.timestamps {
                    def parallelStages = [:]
                    stageList.each { s ->
                        if (s.parallel) {
                            script.echo "[DEBUG] Queuing parallel stage '${s.name}'"
                            parallelStages[s.name] = {
                                script.stage(s.name) {
                                    s.block.call()
                                }
                            }
                        } else {
                            if (!parallelStages.isEmpty()) {
                                script.echo "[DEBUG] Executing ${parallelStages.size()} parallel stages"
                                script.parallel parallelStages
                                parallelStages.clear()
                            }
                            script.echo "[DEBUG] Executing stage '${s.name}'"
                            script.stage(s.name) {
                                s.block.call()
                            }
                        }
                    }
                    if (!parallelStages.isEmpty()) {
                        script.echo "[DEBUG] Executing remaining ${parallelStages.size()} parallel stages"
                        script.parallel parallelStages
                    }
                }
            }
        }
    }

    void assignAgentToStage(String name, String agentLabel) {
        def stage = findStage(name)
        if (stage) {
            stage.agentLabel = agentLabel
        } else {
            script.echo "[Warning] Cannot assign agent. Stage '${name}' not found."
        }
    }

    void assignAgents(Map<String, String> assignments) {
        assignments.each { name, agent ->
            assignAgentToStage(name, agent)
        }
    }

    void removeStage(String name) {
        def norm = normalizeStageName(name)
        def removed = stages.find { normalizeStageName(it.name) == norm }
        if (removed) {
            stages.remove(removed)
        } else {
            script.echo "[Warning] Tried to remove non-existent stage '${name}'"
        }
    }

    void moveStageAfter(String stageToMove, String targetStage) {
        def normMove = normalizeStageName(stageToMove)
        def normTarget = normalizeStageName(targetStage)

        def moveIndex = stages.findIndexOf { normalizeStageName(it.name) == normMove }
        def targetIndex = stages.findIndexOf { normalizeStageName(it.name) == normTarget }

        if (moveIndex < 0) {
            script.echo "[Warning] Stage '${stageToMove}' not found for move"
            return
        }

        if (targetIndex < 0) {
            script.echo "[Warning] Target stage '${targetStage}' not found for insertion"
            return
        }

        if (moveIndex == targetIndex || moveIndex == targetIndex + 1) {
            // No need to move
            return
        }

        def stageDef = stages.remove(moveIndex)

        // Adjust index if we removed from earlier
        if (moveIndex < targetIndex) {
            targetIndex -= 1
        }

        stages.add(targetIndex + 1, stageDef)
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

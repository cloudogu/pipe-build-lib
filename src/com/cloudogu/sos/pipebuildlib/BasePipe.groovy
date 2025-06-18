package com.cloudogu.sos.pipebuildlib

abstract class BasePipe implements Serializable {

    protected final def script
    protected final List<StageGroup> stageGroups = []

    BasePipe(script) {
        this.script = script
        if (!script) {
            println "[ERROR] BasePipe constructor: script is NULL"
        } else {
            script.echo "[DEBUG] BasePipe constructor initialized"
        }
    }

    abstract void addDefaultStages()
    abstract void setBuildProperties(List<ParameterDefinition> customParams)

    void addStageGroup(String agentLabel, Closure groupBuilder) {
        def group = new StageGroup(agentLabel)
        groupBuilder.call(group)
        stageGroups << group
        script.echo "[DEBUG] Added stage group for agent '${agentLabel}' with ${group.stages.size()} stages"
    }

    void addStage(String stageName, Closure block, String agentLabel = defaultAgent) {
        addStage(stageName, EnumSet.of(PipelineMode.FULL), block, agentLabel)
    }

    void addStage(String stageName, Set<PipelineMode> modes, Closure block, String agentLabel = defaultAgent) {
        def group = stageGroups.find { it.agentLabel == agentLabel }
        if (!group) {
            group = new StageGroup(agentLabel)
            stageGroups << group
            script.echo "[DEBUG] Created new StageGroup for agent '${agentLabel}'"
        }
        group.stages << new StageDefinition(stageName, modes, block)
        script.echo "[DEBUG] Added stage '${stageName}' to agent '${agentLabel}' with modes ${modes}"
    }

    void run() {
        if (!script) {
            println "[ERROR] Cannot run pipeline: script is NULL"
            return
        }

        if (stageGroups.isEmpty()) {
            script.echo "[WARN] No stage groups to run"
            return
        }

        def selectedMode = PipelineMode.valueOf(script.params.PipelineMode ?: 'FULL')
        script.echo "[DEBUG] Running pipeline in mode: ${selectedMode}"

        def parallelGroups = [:]

        stageGroups.each { group ->
            parallelGroups[group.agentLabel] = {
                script.node(group.agentLabel) {
                    script.timestamps {
                        try {
                            group.stages.each { stage ->
                                if (!stage.modes.contains(selectedMode)) {
                                    script.echo "[INFO] Skipping stage '${stage.name}' (not enabled for mode ${selectedMode})"
                                    return
                                }
                                if (stage.name != "Clean") {
                                    script.stage(stage.name) {
                                        script.echo "[DEBUG] Running stage '${stage.name}' on agent '${group.agentLabel}'"
                                        stage.block.call()
                                    }
                                }
                            }
                        } catch (Exception e) {
                            script.echo "[ERROR] Exception caught in group '${group.agentLabel}': ${e}"
                            script.currentBuild.result = 'FAILURE'
                            throw e
                        } finally {
                            def cleanStage = group.stages.find { it.name == "Clean" }
                            if (cleanStage) {
                                script.stage("Clean") {
                                    script.echo "[DEBUG] Running cleanup stage on agent '${group.agentLabel}'"
                                    cleanStage.block.call()
                                }
                            }
                        }
                    }
                }
            }
        }

        if (parallelGroups.size() > 1) {
            script.parallel parallelGroups
        } else {
            parallelGroups.values().first().call()
        }
    }

    // === Utilities for later flexibility ===
    void insertStageAfter(String afterName, String newName, Closure block) {
        def normAfter = normalizeStageName(afterName)

        StageGroup groupWithStage = stageGroups.find { group ->
            group.stages.any { normalizeStageName(it.name) == normAfter }
        }

        if (groupWithStage == null) {
            script.echo "[Warning] insertStageAfter: No stage '${afterName}' found"
            return
        }

        def stages = groupWithStage.stages
        def index = stages.findIndexOf { normalizeStageName(it.name) == normAfter }

        if (index < 0) {
            script.echo "[Warning] insertStageAfter: No stage '${afterName}' found"
            return
        }

        if (stages.any { normalizeStageName(it.name) == normalizeStageName(newName) }) {
            throw new IllegalArgumentException("Stage with name '${newName}' already exists.")
        }

        def referenceModes = stages[index].modes

        stages.add(index + 1, new StageDefinition(newName, referenceModes, block))
    }

    void overrideStage(String name, Closure newBlock, Set<PipelineMode> newModes = null) {
        def stage = findStage(name)
        if (stage) {
            stage.block = newBlock
            if (newModes != null) {
                stage.modes = newModes
                script.echo "[DEBUG] Stage '${name}' modes updated to ${newModes}"
            }
        } else {
            script.echo "[WARN] Stage '${name}' not found to override"
        }
    }

    void removeStage(String name) {
        def norm = normalizeStageName(name)
        stageGroups.each { group ->
            def removed = group.stages.find { normalizeStageName(it.name) == norm }
            if (removed) {
                group.stages.remove(removed)
                script.echo "[DEBUG] Removed stage '${name}' from agent '${group.agentLabel}'"
            }
        }
    }

    void moveStageAfter(String stageToMove, String targetStage, String agentLabel) {
        def group = stageGroups.find { it.agentLabel == agentLabel }
        if (!group) {
            script.echo "[WARN] No stage group found for agent '${agentLabel}'"
            return
        }

        def moveIndex = group.stages.findIndexOf { normalizeStageName(it.name) == normalizeStageName(stageToMove) }
        def targetIndex = group.stages.findIndexOf { normalizeStageName(it.name) == normalizeStageName(targetStage) }

        if (moveIndex < 0 || targetIndex < 0) {
            script.echo "[WARN] Cannot move stage '${stageToMove}' after '${targetStage}' in agent '${agentLabel}'"
            return
        }

        if (moveIndex == targetIndex || moveIndex == targetIndex + 1) {
            return
        }

        def stageDef = group.stages.remove(moveIndex)
        if (moveIndex < targetIndex) {
            targetIndex -= 1
        }
        group.stages.add(targetIndex + 1, stageDef)
    }

    boolean hasStage(String name) {
        return findStage(name) != null
    }

    protected StageDefinition findStage(String name) {
        def norm = normalizeStageName(name)
        return stageGroups
            .collectMany { it.stages }
            .find { normalizeStageName(it.name) == norm }
    }

    protected String normalizeStageName(String name) {
        return name.toLowerCase().replaceAll(/\s+/, '')
    }
}

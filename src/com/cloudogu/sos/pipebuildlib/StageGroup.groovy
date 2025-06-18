package com.cloudogu.sos.pipebuildlib

class StageGroup implements Serializable {
    String agentLabel
    List<StageDefinition> stages = []
    Set<PipelineMode> defaultModes = [PipelineMode.FULL]

    StageGroup(String agentLabel) {
        this.agentLabel = agentLabel
    }

    void raw_stage(String name, PipelineMode mode, Closure block) {
        stages << new StageDefinition(name, EnumSet.of(mode), block)
    }

    void stage(String name, Closure block) {
        stage(name, EnumSet.of(PipelineMode.FULL), block)
    }

    void stage(String name, PipelineMode mode, Closure block) {
        stage(name, EnumSet.of(mode), block)
    }

    void stage(String name, Set<PipelineMode> modes, Closure block) {
        def finalModes = new HashSet<>(modes)
        finalModes.add(PipelineMode.FULL)
        stages << new StageDefinition(name, finalModes, block)
    }
}

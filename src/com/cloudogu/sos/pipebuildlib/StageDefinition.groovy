package com.cloudogu.sos.pipebuildlib

class StageDefinition implements Serializable {
    String name
    Set<PipelineMode> modes = []
    Closure block

    StageDefinition(String name, Closure block) {
        this(name, EnumSet.of(PipelineMode.FULL), block)
    }

    StageDefinition(String name, Set<PipelineMode> modes, Closure block) {
        this.name = name
        this.modes = modes
        this.block = block
    }
}


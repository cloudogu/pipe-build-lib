package com.pipebuildlib

class StageDefinition implements Serializable {
    final String name
    final Closure block
    final String agentLabel
    final boolean parallel

    StageDefinition(String name, Closure block, String agentLabel = null, boolean parallel = false) {
        this.name = name
        this.block = block
        this.agentLabel = agentLabel
        this.parallel = parallel
    }
}

package com.cloudogu.sos.pipebuildlib.stages

import com.cloudogu.sos.pipebuildlib.StageGroup
import com.cloudogu.sos.pipebuildlib.DoguPipe

interface DoguStageModule {
    void register(DoguPipe pipe, StageGroup group)
}

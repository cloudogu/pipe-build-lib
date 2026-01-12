package com.cloudogu.sos.pipebuildlib.stages

import com.cloudogu.sos.pipebuildlib.StageGroup
import com.cloudogu.sos.pipebuildlib.DoguPipe

import com.cloudogu.ces.cesbuildlib.*
import com.cloudogu.ces.dogubuildlib.*

interface DoguStageModule {
    void register(DoguPipe pipe, StageGroup group)
}

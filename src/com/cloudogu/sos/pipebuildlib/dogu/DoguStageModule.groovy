package com.cloudogu.sos.pipebuildlib.dogu

import com.cloudogu.sos.pipebuildlib.*
import com.cloudogu.sos.pipebuildlib.dogu.*
import com.cloudogu.ces.cesbuildlib.*
import com.cloudogu.ces.dogubuildlib.*

interface DoguStageModule {
    void register(DoguPipe pipe, StageGroup group)
}

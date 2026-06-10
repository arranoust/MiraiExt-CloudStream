package com.arranoust

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SamehadakuPlugin : Plugin() {
    override fun load(context: Context) {
        SamehadakuProvider.context = context
        registerMainAPI(SamehadakuProvider())
        registerExtractorAPI(FiledonExtractor())
    }
}
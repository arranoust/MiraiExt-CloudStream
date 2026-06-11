package com.otakudesu

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OtakudesuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OtakudesuProvider())
        registerExtractorAPI(OdstreamExtractor())
        registerExtractorAPI(FiledonExtractor())
        registerExtractorAPI(OndesuExtractor())
        registerExtractorAPI(OndesuhExtractor())
    }
}
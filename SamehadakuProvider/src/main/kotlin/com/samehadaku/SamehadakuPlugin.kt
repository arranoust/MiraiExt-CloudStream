package com.samehadaku

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SamehadakuPlugin : Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("samehadaku_settings", Context.MODE_PRIVATE)

        SamehadakuProvider.context = context
        registerMainAPI(SamehadakuProvider(sharedPref))
        registerExtractorAPI(FiledonExtractor())

        openSettings = {
            val frag = SettingsFragment(sharedPref)
            frag.show(it.supportFragmentManager, "SamehadakuSettings")
        }
    }
}
package com.samehadaku

import android.content.Context
import androidx.fragment.app.FragmentActivity 
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SamehadakuPlugin : Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("samehadaku_settings", Context.MODE_PRIVATE)

        SamehadakuProvider.context = context
        registerMainAPI(SamehadakuProvider(sharedPref))
        registerExtractorAPI(FiledonExtractor())

        openSettings = { ctx -> 
            val activity = ctx as? FragmentActivity
            activity?.let {
                val frag = SettingsFragment(sharedPref)
                frag.show(it.supportFragmentManager, "SamehadakuSettings")
            }
        }
    }
}
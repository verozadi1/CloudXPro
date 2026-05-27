package com.tvtokusatsuindo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TVTokusatsuIndoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TVTokusatsuIndo())
    }
}

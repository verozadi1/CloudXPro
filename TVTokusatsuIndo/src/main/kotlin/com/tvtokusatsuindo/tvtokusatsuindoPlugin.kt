package com.tvtokusatsuindo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TvTokusatsuIndoPlugin : Plugin() {
    override fun load(context: Context) {
        // Ini yang menghubungkan aplikasi ke file provider lu
        registerMainAPI(TvTokusatsuIndoProvider())
    }
}
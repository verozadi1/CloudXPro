package com.tokusatsuindo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TokusatsuindoPlugin: Plugin() {
    override fun load(context: Context) {
        // Memanggil mesin utama
        registerMainAPI(TokusatsuIndoProvider())
    }
}

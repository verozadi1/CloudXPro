package com.tokusatsuindo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TokusatsuIndoPlugin: Plugin() {
    override fun load(context: Context) {
        // Nama KTP sudah disamakan: huruf I besar!
        registerMainAPI(TokusatsuIndoProvider())
    }
}

package com.tokusatsuindo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TokusatsuindoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TokusatsuIndoProvider()) // I kapital
        registerExtractorAPI(GdPlayer())
        registerExtractorAPI(GdrivePlayer())
    }
}
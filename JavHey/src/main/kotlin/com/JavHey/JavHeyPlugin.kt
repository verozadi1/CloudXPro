package com.javhey

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin // <--- PENTING: Ini class induknya
import android.content.Context

@CloudstreamPlugin
class JavHeyPlugin : Plugin() { // <--- PENTING: Extend 'Plugin', BUKAN 'CloudstreamPlugin'
    override fun load(context: Context) {
        // Mendaftarkan API utama
        registerMainAPI(JavHey())
    }
}

package com.tokusatsuindo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.squareup.duktape.Duktape
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

class GdPlayer : ExtractorApi() {
    override val name = "GdPlayer"
    override val mainUrl = "https://gdplayer.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to (referer ?: mainUrl)
            )
        ).text

        val aaencodedScript = Regex("""(ﾟωﾟ.*?)\s*</script>""").find(html)?.groupValues?.get(1)
            ?: return

        // 1. Decode AAEncoded Javascript
        val duktape = Duktape.create()
        val packerScript = try {
            val evalPreamble = "(\\uFF9F\\u0414\\uFF9F) ['_'] ( (\\uFF9F\\u0414\\uFF9F) ['_'] ("
            val decodePreamble = "( (\\uFF9F\\u0414\\uFF9F) ['_'] ("
            val evalPostamble = ") (\\uFF9F\\u0398\\uFF9F)) ('_');"
            val decodePostamble = ") ());"

            val decodingScript = aaencodedScript
                .replace(evalPreamble, decodePreamble)
                .replace(evalPostamble, decodePostamble)

            duktape.evaluate(decodingScript) as String
        } catch (e: Exception) {
            return
        } finally {
            duktape.close()
        }

        // 2. Unpack Packer Javascript
        val duktape2 = Duktape.create()
        val unpackedScript = try {
            duktape2.evaluate(packerScript.replaceFirst("eval", "")) as String
        } catch (e: Exception) {
            return
        } finally {
            duktape2.close()
        }

        // 3. Extract variables from unpacked script
        val ps = Regex("""ps\s*=\s*"([^"]+)"""").find(unpackedScript)?.groupValues?.get(1) ?: return
        val pd = Regex("""pd\s*=\s*"([^"]+)"""").find(unpackedScript)?.groupValues?.get(1) ?: return
        val kaken = Regex("""kaken\s*=\s*"([^"]+)"""").find(unpackedScript)?.groupValues?.get(1) ?: return
        val qsx = Regex("""qsx\s*=\s*"([^"]+)"""").find(unpackedScript)?.groupValues?.get(1) ?: return
        val apxBase64 = Regex("""apx\s*=\s*"([^"]+)"""").find(unpackedScript)?.groupValues?.get(1) ?: return
        val apx = String(Base64.decode(apxBase64, Base64.DEFAULT), Charsets.UTF_8).trim()

        // 4. Construct sources API URL
        // https://rack1.bubarindpr.com/api-config/ => Replace "-config" with "" => https://rack1.bubarindpr.com/api/
        val apiUrl = apx.replace("-config", "") + "?p=" + ps

        // 5. Make POST request to sources API with kaken as body
        val responseText = app.post(
            apiUrl,
            data = kaken,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to url,
                "Origin" to "https://gdplayer.to",
                "Content-Type" to "text/plain"
            )
        ).text

        if (responseText.isBlank()) return

        // 6. Decrypt the response
        val decryptedJson = try {
            decryptAES(responseText.trim(), pd)
        } catch (e: Exception) {
            return
        }

        // 7. Parse JSON and invoke callback for each source
        val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).registerKotlinModule()
        val data = try {
            mapper.readValue(decryptedJson, GdResponse::class.java)
        } catch (e: Exception) {
            return
        }

        data.sources?.forEach { source ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name (${source.label})",
                    url = source.file,
                    referer = url,
                    quality = getQualityFromName(source.label),
                    type = if (source.file.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }
    }

    private fun decryptAES(encryptedBase64: String, password: String): String {
        val cipherBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
        val salt = cipherBytes.sliceArray(0 until 16)
        val ciphertext = cipherBytes.sliceArray(16 until cipherBytes.size)

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, 10000, 384)
        val keyBytes = factory.generateSecret(spec).encoded

        val key = SecretKeySpec(keyBytes.sliceArray(0 until 32), "AES")
        val iv = IvParameterSpec(keyBytes.sliceArray(32 until 48))

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        val decryptedBytes = cipher.doFinal(ciphertext)

        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun getQualityFromName(name: String): Int {
        return when {
            name.contains("1080") -> Qualities.P1080.value
            name.contains("720") -> Qualities.P720.value
            name.contains("480") -> Qualities.P480.value
            name.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    data class GdSource(
        val file: String,
        val label: String,
        val type: String
    )

    data class GdResponse(
        val sources: List<GdSource>? = null
    )
}

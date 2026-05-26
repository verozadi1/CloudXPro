package com.tokusatsuindo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
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

        // 1. Decode AAEncoded Javascript natively in Kotlin
        val packerScript = try {
            AADecoder.decode(aaencodedScript)
        } catch (e: Exception) {
            return
        }

        // 2. Unpack Packer Javascript using Cloudstream's built-in getAndUnpack
        val unpackedScript = try {
            getAndUnpack(packerScript)
        } catch (e: Exception) {
            return
        }

        // 3. Extract variables from unpacked script
        val ps = Regex("""ps\s*=\s*"([^"]+)"""").find(unpackedScript)?.groupValues?.get(1) ?: return
        val pd = Regex("""pd\s*=\s*"([^"]+)"""").find(unpackedScript)?.groupValues?.get(1) ?: return
        val kaken = Regex("""kaken\s*=\s*"([^"]+)"""").find(unpackedScript)?.groupValues?.get(1) ?: return
        val qsx = Regex("""qsx\s*=\s*"([^"]+)"""").find(unpackedScript)?.groupValues?.get(1) ?: return
        val apxBase64 = Regex("""apx\s*=\s*"([^"]+)"""").find(unpackedScript)?.groupValues?.get(1) ?: return
        val apx = String(Base64.decode(apxBase64, Base64.DEFAULT), Charsets.UTF_8).trim()

        // 4. Construct sources API URL
        val apiUrl = apx.replace("-config", "") + "?p=" + ps

        // 5. Make POST request to sources API with kaken as raw body
        val requestBody = kaken.toRequestBody("text/plain".toMediaTypeOrNull())

        val responseText = app.post(
            apiUrl,
            requestBody = requestBody,
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
                    type = if (source.file.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = getQualityFromName(source.label)
                }
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

    object AADecoder {
        private fun isOctalDigit(c: Char): Boolean {
            return c in '0'..'7'
        }

        fun decode(encodedText: String): String {
            val startIdx = encodedText.indexOf("/*´∇｀*/(ﾟДﾟ)[ﾟoﾟ]+")
            val endIdx = encodedText.lastIndexOf("(ﾟДﾟ)[ﾟoﾟ]) (ﾟΘﾟ)) ('_');")

            if (startIdx == -1 || endIdx == -1) {
                return ""
            }

            var expr = encodedText.substring(startIdx, endIdx)

            val mapping = listOf(
                "((ﾟｰﾟ) + (ﾟｰﾟ) + (ﾟΘﾟ))" to "9",
                "((ﾟｰﾟ) + (ﾟｰﾟ))" to "8",
                "((ﾟｰﾟ) + (o^_^o))" to "7",
                "((o^_^o) +(o^_^o))" to "6",
                "((ﾟｰﾟ) + (ﾟΘﾟ))" to "5",
                "(ﾟｰﾟ)" to "4",
                "(o^_^o)" to "3",
                "((o^_^o) - (ﾟΘﾟ))" to "2",
                "(ﾟΘﾟ)" to "1",
                "(c^_^o)" to "0",
                "c^_^o" to "0",
                "ﾟｰﾟ" to "4",
                "o^_^o" to "3",
                "ﾟΘﾟ" to "1"
            )

            expr = expr.replace("(ﾟДﾟ)[ﾟεﾟ]", "\\")
            expr = expr.replace("(ﾟДﾟ)[ﾟoﾟ]", "\"")

            for ((emo, value) in mapping) {
                expr = expr.replace(emo, value)
            }

            expr = expr.replace("+", "")
                .replace(" ", "")
                .replace("\n", "")
                .replace("\r", "")

            expr = expr.replace("(3-1)", "2")
            expr = expr.replace("(3-1-1)", "1")
            expr = expr.replace("(4-1)", "3")

            val sb = StringBuilder()
            var i = 0
            val len = expr.length
            while (i < len) {
                if (expr[i] == '\\' && i + 1 < len) {
                    if (expr[i + 1] == 'x' && i + 3 < len) {
                        val hex = expr.substring(i + 2, i + 4)
                        sb.append(hex.toInt(16).toChar())
                        i += 4
                    } else if (isOctalDigit(expr[i + 1])) {
                        var octalLen = 1
                        while (octalLen < 3 && i + 1 + octalLen < len && isOctalDigit(expr[i + 1 + octalLen])) {
                            octalLen++
                        }
                        val octal = expr.substring(i + 1, i + 1 + octalLen)
                        sb.append(octal.toInt(8).toChar())
                        i += 1 + octalLen
                    } else {
                        sb.append(expr[i])
                        i++
                    }
                } else {
                    sb.append(expr[i])
                    i++
                }
            }

            var result = sb.toString().replace("/*´∇｀*/", "").trim()
            if (result.startsWith("\"")) {
                result = result.substring(1)
            }
            if (result.endsWith("\"")) {
                result = result.substring(0, result.length - 1)
            }
            return result.trim()
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
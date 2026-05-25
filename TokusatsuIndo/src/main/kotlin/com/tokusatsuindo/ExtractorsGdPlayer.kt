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
        // https://rack1.bubarindpr.com/api-config/ => Replace "-config" with "" => https://rack1.bubarindpr.com/api/
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
            // Extract only the emoticon string part
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

            // Strip spaces and '+' FIRST!
            expr = expr.replace("+", "")
                .replace(" ", "")
                .replace("\n", "")
                .replace("\r", "")

            // Handle mathematical modifications inside octals/hexes AFTER stripping spaces!
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

class GdrivePlayer : ExtractorApi() {
    override val name = "GdrivePlayer"
    override val mainUrl = "https://gdriveplayer.to"
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
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
        ).text

        val document = org.jsoup.Jsoup.parse(html)
        
        // Find Script 7 (which has sojson and is long)
        val sojsonScript = document.select("script").map { it.html() }
            .firstOrNull { it.contains("sojson.v4") && it.length > 5000 }
            ?: return

        // 1. Decode first sojson layer to get standard player code
        val decodedS7 = try {
            decodeSojson(sojsonScript)
        } catch (e: Exception) {
            return
        }

        // Extract and decode Script 8 cipher to get the key "pass"
        val s8Script = document.select("script").map { it.html() }
            .firstOrNull { it.contains("sojson.v4") && it.length in 1000..3000 }
            ?: return
        val gdrivePass = try {
            val s8Decoded = decodeSojson(s8Script)
            Regex("""pass\s*=\s*"([^"]+)"""").find(s8Decoded)?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            ""
        }

        if (gdrivePass.isBlank()) return

        // Find Packer Script (Script 9)
        val packerScriptRaw = document.select("script").map { it.html() }
            .firstOrNull { it.contains("eval(function(p,a,c,k,e,d)") }
            ?: return
        val packerScript = Regex("""eval\(function.*""").find(packerScriptRaw)?.groupValues?.get(0)
            ?: return

        // 2. Unpack Packer to get Layer 2 unpacked script
        val unpackedScript = try {
            getAndUnpack(packerScript)
        } catch (e: Exception) {
            return
        }

        // 3. Extract the 'data' variable string from Layer 2
        val dataVarStr = Regex("""var\s+data\s*=\s*'([^']+)'""").find(unpackedScript)?.groupValues?.get(1)
            ?: return
        val dataJsonStr = dataVarStr.replace("\\/", "/")

        // 4. Decrypt 'data' using our Java EvpKDF AES decryption (MD5 KDF)
        val decryptedLayer3 = try {
            decryptAESEvp(dataJsonStr, gdrivePass)
        } catch (e: Exception) {
            return
        }

        // 5. Unpack Layer 3 Packer script to get final player setup
        val finalScript = try {
            getAndUnpack(decryptedLayer3)
        } catch (e: Exception) {
            return
        }

        // 6. Extract video ID or direct download link
        val downloadUrlEncoded = Regex("""download\.php%3Fid%3D([^']+)""").find(finalScript)?.groupValues?.get(1)
            ?: Regex("""download\.php\?id=([^']+)""").find(finalScript)?.groupValues?.get(1)
            ?: return

        // Decode download URL to get direct Gdrive ID
        val gdriveId = downloadUrlEncoded.replace("%3D", "=").replace("%2F", "/").replace("%3A", ":").trim()

        val videoUrl = "https://redirector.gdrivecdn.me/drive/index.php?id=$gdriveId"

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name (720p)",
                url = "$videoUrl&res=720",
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://gdriveplayer.to/"
                this.quality = Qualities.P720.value
            }
        )
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name (360p)",
                url = "$videoUrl&res=360",
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://gdriveplayer.to/"
                this.quality = Qualities.P360.value
            }
        )
    }

    private fun decodeSojson(script: String): String {
        val cipherMatch = Regex("""\(null\s*,\s*['\"]([^'\"]+)['\"]""").find(script)
            ?: return ""
        val cipher = cipherMatch.groupValues[1]
        val nums = Regex("""\d+""").findAll(cipher).map { it.value.toInt().toChar() }
        return nums.joinToString("")
    }

    private fun decryptAESEvp(jsonStr: String, passwordStr: String): String {
        val ct = getJsonField(jsonStr, "ct")
        val ivHex = getJsonField(jsonStr, "iv")
        val saltHex = getJsonField(jsonStr, "s")

        val ciphertext = Base64.decode(ct, Base64.DEFAULT)
        val iv = hexToBytes(ivHex)
        val salt = hexToBytes(saltHex)

        val password = passwordStr.toByteArray(Charsets.UTF_8)
        val key = deriveKeyEvp(password, salt, 32)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val decryptedBytes = cipher.doFinal(ciphertext)

        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun getJsonField(json: String, field: String): String {
        return Regex(""""$field"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    private fun deriveKeyEvp(password: ByteArray, salt: ByteArray, keySize: Int): ByteArray {
        val md = java.security.MessageDigest.getInstance("MD5")
        val key = ByteArray(keySize)
        var derived = 0
        var d: ByteArray? = null

        while (derived < keySize) {
            if (d != null) {
                md.update(d)
            }
            md.update(password)
            md.update(salt)
            d = md.digest()
            val chunk = Math.min(d.size, keySize - derived)
            System.arraycopy(d, 0, key, derived, chunk)
            derived += chunk
        }
        return key
    }
}

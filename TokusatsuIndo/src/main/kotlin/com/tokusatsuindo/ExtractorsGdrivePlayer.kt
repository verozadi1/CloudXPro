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
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
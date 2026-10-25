package dev.elias.cast22

import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.net.ssl.HttpsURLConnection

data class ReleaseUpdate(val versionName: String, val versionCode: Int, val apkUrl: String, val releaseNotes: String)

/** Anonymous public release metadata. Failure must never affect the receiver. */
object UpdateCheck {
    const val PACKAGE_ID = "dev.elias.cast22"
    const val ENDPOINT = "https://22cast.elias.dev/api/version"
    const val MAX_BYTES = 32 * 1024

    @JvmStatic fun parse(body: ByteArray, installedCode: Long, sdk: Int): ReleaseUpdate? = try {
        require(body.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
        val tokener = JSONTokener(text)
        val release = tokener.nextValue() as? JSONObject ?: error("Expected release object")
        require(tokener.nextClean() == '\u0000')
        fun integer(key: String): Int {
            val value = release.get(key)
            require(value is Int || value is Long)
            val number = (value as Number).toLong()
            require(number in 1..Int.MAX_VALUE.toLong())
            return number.toInt()
        }
        require(integer("schemaVersion") == 1)
        require(release.get("packageId") == PACKAGE_ID)
        val code = integer("versionCode")
        require(code > installedCode)
        require(integer("minSdk") <= sdk)
        val name = release.get("versionName") as String
        require(name.isNotBlank() && name.length <= 40 && name.none { it.isISOControl() })
        val download = release.get("apkUrl") as String
        val uri = URI(download)
        require(uri.scheme == "https" && uri.host == "22cast.elias.dev")
        require(uri.port == -1 || uri.port == 443)
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.path.startsWith("/downloads/") && uri.path.endsWith(".apk"))
        val hash = release.get("sha256") as String
        require(hash.matches(Regex("[0-9a-fA-F]{64}")))
        val notes = release.opt("releaseNotes") as? String ?: ""
        require(notes.length <= 2000 && notes.none { it.isISOControl() && it != '\n' && it != '\t' && it != '\r' })
        ReleaseUpdate(name, code, download, notes)
    } catch (_: Exception) { null }

    @JvmStatic fun readBody(input: InputStream, contentLength: Long, deadline: Long = Long.MAX_VALUE): ByteArray {
        require(contentLength <= MAX_BYTES)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            require(SystemClock.elapsedRealtime() < deadline)
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    @JvmStatic fun fetch(installedCode: Long, sdk: Int): ReleaseUpdate? {
        var connection: HttpsURLConnection? = null
        return try {
            connection = URL(ENDPOINT).openConnection() as HttpsURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "22Cast")
            val deadline = SystemClock.elapsedRealtime() + 5000
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.use { readBody(it, connection.contentLengthLong, deadline) }
            parse(body, installedCode, sdk)
        } catch (_: Exception) { null }
        finally { connection?.disconnect() }
    }
}

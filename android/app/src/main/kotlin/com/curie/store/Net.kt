package com.curie.store

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Addresses of the services the app talks to. */
object Cfg {
    const val STORE_BASE = "https://rocurity.github.io/Curie-Store/store/"
    const val STORE_API = "https://rocurity--d6f12a7ac46211f19d8c1607ee4eb77e.web.val.run"
    const val CURIE_API = "https://rocurity--40be21cebf4011f199401607ee4eb77e.web.val.run"
    const val CURIE_SIGNUP = "https://curie-id-c579e.web.app"
}

class ApiResult(val code: Int, val json: JSONObject) {
    val ok: Boolean get() = code in 200..299
    val error: String get() = json.str("error")
}

/** Like optString, but a missing key or JSON null gives "" (never the text "null"). */
fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

object Net {
    /** Blocking JSON request. Call from a background thread. */
    fun call(method: String, url: String, body: JSONObject? = null, token: String? = null): ApiResult {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("Accept", "application/json")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            return ApiResult(code, json)
        } finally {
            c.disconnect()
        }
    }
}

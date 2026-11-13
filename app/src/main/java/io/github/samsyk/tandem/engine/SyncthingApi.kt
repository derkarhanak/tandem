package io.github.samsyk.tandem.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(val code: Int, message: String) : IOException(message)

/** Talks to the engine's REST API on localhost. */
class SyncthingApi(private val base: String, private val key: String) {

    fun ping(): Boolean = try {
        request("GET", "/rest/noauth/health")
        true
    } catch (_: Exception) {
        false
    }

    fun request(method: String, path: String, body: String? = null): String {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 3000
            c.readTimeout = 15000
            c.setRequestProperty("X-API-Key", key)
            if (method == "POST" || method == "PUT") {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write((body ?: "").toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException(code, text.trim().ifEmpty { "HTTP $code" })
            return text
        } finally {
            c.disconnect()
        }
    }

    suspend fun get(path: String): String = withContext(Dispatchers.IO) { request("GET", path) }
    suspend fun obj(path: String): JSONObject = JSONObject(get(path))
    suspend fun post(path: String, body: String? = null): String = withContext(Dispatchers.IO) { request("POST", path, body) }
    suspend fun put(path: String, json: JSONObject): String = withContext(Dispatchers.IO) { request("PUT", path, json.toString()) }
    suspend fun delete(path: String): String = withContext(Dispatchers.IO) { request("DELETE", path) }
}

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject?.keyList(): List<String> = this?.keys()?.asSequence()?.toList() ?: emptyList()

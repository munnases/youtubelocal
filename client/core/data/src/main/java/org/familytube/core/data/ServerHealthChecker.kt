package org.familytube.core.data

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

data class HealthResult(val reachable: Boolean, val videoCount: Int? = null, val detail: String? = null)

@Singleton
class ServerHealthChecker @Inject constructor(private val networkClient: NetworkClient) {

    suspend fun check(serverUrl: String): HealthResult = withContext(Dispatchers.IO) {
        val url = "$serverUrl/api/health"
        try {
            networkClient.http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext HealthResult(false, detail = "HTTP ${response.code}")
                val body = response.body?.string() ?: return@withContext HealthResult(false, detail = "Empty response")
                val json = JSONObject(body)
                if (json.optBoolean("ok")) HealthResult(true, json.optInt("videos", 0))
                else HealthResult(false, detail = "Unexpected health response")
            }
        } catch (error: IOException) {
            HealthResult(false, detail = error.localizedMessage ?: "Connection failed")
        } catch (_: Exception) {
            HealthResult(false, detail = "Unexpected health response")
        }
    }
}

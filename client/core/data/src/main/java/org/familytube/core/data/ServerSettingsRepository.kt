package org.familytube.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.serverSettingsStore by preferencesDataStore(name = "server_settings")
private val SERVER_URL = stringPreferencesKey("server_url")
private val INSTALLATION_ID = stringPreferencesKey("installation_id")

@Singleton
class ServerSettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    val serverUrl: Flow<String> = context.serverSettingsStore.data.map { it[SERVER_URL].orEmpty() }

    suspend fun installationId(): String {
        val preferences = context.serverSettingsStore.edit {
            if (it[INSTALLATION_ID] == null) it[INSTALLATION_ID] = UUID.randomUUID().toString()
        }
        return checkNotNull(preferences[INSTALLATION_ID])
    }

    suspend fun saveServerUrl(input: String): String {
        val normalized = normalizeServerUrl(input)
        context.serverSettingsStore.edit { it[SERVER_URL] = normalized }
        return normalized
    }
}

/** Only a server origin is accepted; API paths are supplied by the client. */
fun normalizeServerUrl(input: String): String {
    val uri = try {
        URI(input.trim())
    } catch (_: Exception) {
        throw IllegalArgumentException("Enter a valid http:// or https:// server address")
    }
    require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") && (uri.port == -1 || uri.port in 1..65535)) {
        "Enter a server address such as http://192.168.1.10:8000"
    }
    return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, null, null, null).toString()
}

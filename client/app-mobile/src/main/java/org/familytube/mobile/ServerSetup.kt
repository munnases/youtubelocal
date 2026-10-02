package org.familytube.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.familytube.core.data.*
import org.familytube.core.designsystem.FamilyColors

@Composable
internal fun ServerSetup(settings: ServerSettingsRepository, healthChecker: ServerHealthChecker, onDone: () -> Unit) {
    val savedUrl by settings.serverUrl.collectAsState(initial = "")
    var draft by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Enter your home server address to connect.") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(savedUrl) { if (!edited) draft = savedUrl }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("FamilyTube", style = MaterialTheme.typography.headlineLarge, color = FamilyColors.text)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(draft, onValueChange = { draft = it; edited = true },
            label = { Text("Server address") }, placeholder = { Text("http://192.168.1.10:8000") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            scope.launch {
                busy = true
                try {
                    val url = settings.saveServerUrl(draft)
                    draft = url
                    edited = false
                    status = healthChecker.check(url).message()
                } catch (error: IllegalArgumentException) {
                    status = error.message ?: "Invalid address"
                } finally { busy = false }
            }
        }, enabled = !busy && draft.isNotBlank()) { Text(if (busy) "Checking…" else "Save and connect") }
        Spacer(Modifier.height(12.dp))
        Text(status, color = FamilyColors.muted)
        if (savedUrl.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onDone) { Text("Open library") }
        }
    }
}

private fun HealthResult.message(): String = if (reachable) {
    "Connected to FamilyTube server · ${videoCount ?: 0} videos"
} else {
    "Could not connect: ${detail ?: "Unknown error"}. Check the address and local Wi-Fi."
}

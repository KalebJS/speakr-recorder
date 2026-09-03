package dev.kalebjs.speakr.recorder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun SetupScreen(vm: MainViewModel, onDone: () -> Unit, onCancel: () -> Unit) {
    var url by remember { mutableStateOf(App.serverUrl) }
    var token by remember { mutableStateOf(App.apiToken) }

    val testing = vm.testing.value
    val testResult = vm.testResult.value
    val testError = vm.testError.value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Set up your Speakr", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Point the recorder at your self-hosted Speakr instance. Create an API token in Speakr under Settings → API Tokens.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://your-speakr-instance.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("API token") },
            placeholder = { Text("Paste your Speakr API token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    if (SpeakrApi.baseUrlOk(url.trim()) && token.isNotBlank()) {
                        vm.testConnection(url, token)
                    } else {
                        vm.testError.value = "Enter a valid URL (https://…) and token"
                    }
                },
                enabled = !testing
            ) {
                Text(if (testing) "Testing…" else "Test connection")
            }
            when {
                testResult != null -> Text(
                    testResult,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
                testError != null -> Text(
                    testError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                App.serverUrl = url.trim()
                App.apiToken = token.trim()
                vm.refreshTags()
                onDone()
            },
            enabled = url.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Save and start")
        }

        TextButton(onClick = onCancel, enabled = App.isConfigured) {
            Text("Cancel")
        }

        Text(
            "Your token is stored encrypted on this device and sent only to your Speakr server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
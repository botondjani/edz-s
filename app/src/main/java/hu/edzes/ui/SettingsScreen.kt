package hu.edzes.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.health.connect.client.PermissionController
import hu.edzes.UiState
import hu.edzes.dao
import hu.edzes.data.Backup
import hu.edzes.prefs
import hu.edzes.sync.Hc
import hu.edzes.sync.Strava
import hu.edzes.sync.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = ctx.prefs

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Title("Beállítások")

        Section("Health Connect (Fitbit / Google Health)")
        var hcTick by remember { mutableIntStateOf(0) }
        val hcAvailable = remember { Hc.available(ctx) }
        val hcGranted by produceState(false, hcTick) { value = hcAvailable && runCatching { Hc(ctx).granted() }.getOrDefault(false) }
        val hcRequest = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            hcTick++
            scope.launch { Sync.run(ctx.applicationContext) }
        }
        Text(
            when {
                !hcAvailable -> "A Health Connect nem érhető el ezen a telefonon (telepítsd vagy frissítsd a Play Áruházból)."
                hcGranted -> "✓ Összekapcsolva. Az órával mért edzések automatikusan megjelennek."
                else -> "Az órával mért edzések (futás, kerékpár, úszás, pulzus) innen érkeznek. A Google Health appban is kapcsold be a Health Connect szinkront."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (hcAvailable && !hcGranted) Button(onClick = { hcRequest.launch(Hc.REQUEST) }, Modifier.padding(top = 8.dp)) { Text("Engedélyek megadása") }

        Section("Strava")
        val connected = remember(UiState.stravaTick) { Strava(ctx).connected }
        if (connected) {
            Text("✓ Összekapcsolva.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { Strava(ctx).disconnect(); UiState.stravaTick++ }) { Text("Leválasztás") }
        } else {
            Text(
                "1. Nyisd meg: strava.com/settings/api, és hozz létre egy saját API alkalmazást.\n" +
                    "2. Az „Authorization Callback Domain” mezőbe ezt írd: localhost\n" +
                    "3. Másold ide a Client ID-t és a Client Secretet, majd koppints az Összekapcsolásra.",
                style = MaterialTheme.typography.bodyMedium,
            )
            var id by remember { mutableStateOf(prefs.getString("strava_client_id", "")!!) }
            var secret by remember { mutableStateOf(prefs.getString("strava_client_secret", "")!!) }
            NumberField("Client ID", id, Modifier.fillMaxWidth().padding(top = 8.dp)) { id = it.trim() }
            OutlinedTextField(secret, { secret = it.trim() }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Client Secret") }, singleLine = true)
            Button(enabled = id.isNotEmpty() && secret.isNotEmpty(), modifier = Modifier.padding(top = 8.dp), onClick = {
                prefs.edit { putString("strava_client_id", id); putString("strava_client_secret", secret) }
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Strava(ctx).authUri()))
            }) { Text("Összekapcsolás") }
        }
        PrefSwitch("auto_upload", true, "Edzések automatikus feltöltése Stravára")
        PrefSwitch("upload_walks", false, "Séták automatikus feltöltése is")
        Text(
            "Ha itt be van kapcsolva az automatikus feltöltés, a Google Health → Strava közvetlen szinkront kapcsold ki, különben dupla edzések lehetnek.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )

        Section("Edzés közben")
        PrefSwitch("voice", true, "Hangbemondás (magyar szövegfelolvasó)")

        Section("Adatok")
        var restoring by remember { mutableStateOf<Backup?>(null) }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) scope.launch {
                val json = Json.encodeToString(ctx.dao.backup())
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) } }
                ctx.toast("Mentés kész")
            }
        }
        val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                runCatching {
                    val json = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.reader().readText() } }
                    restoring = Json.decodeFromString<Backup>(json)
                }.onFailure { ctx.toast("Ez nem az app mentési fájlja") }
            }
        }
        Text("A mentés minden programot, tervet és edzést tartalmaz egy JSON fájlban – elemzéshez Claude-nak is feltölthető.", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { export.launch("edzes-mentes-${LocalDate.now()}.json") }) { Text("Mentés fájlba") }
            FilledTonalButton(onClick = { import.launch(arrayOf("application/json")) }) { Text("Visszaállítás") }
        }
        restoring?.let { b ->
            AlertDialog(
                onDismissRequest = { restoring = null },
                title = { Text("Visszaállítás") },
                text = { Text("A fájl ${b.programs.size} programot és ${b.activities.size} edzést tartalmaz. A mostani adatok helyére kerül – biztos?") },
                confirmButton = { TextButton(onClick = { restoring = null; scope.launch { ctx.dao.restore(b); ctx.toast("Visszaállítva") } }) { Text("Visszaállítás") } },
                dismissButton = { TextButton(onClick = { restoring = null }) { Text("Mégse") } },
            )
        }
    }
}

@Composable
private fun PrefSwitch(key: String, default: Boolean, label: String) {
    val prefs = LocalContext.current.prefs
    var on by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(on, { on = it; prefs.edit { putBoolean(key, it) } })
    }
}

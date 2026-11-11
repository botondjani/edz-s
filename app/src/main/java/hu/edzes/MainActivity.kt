package hu.edzes

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import hu.edzes.player.Player
import hu.edzes.sync.Strava
import hu.edzes.sync.Sync
import hu.edzes.ui.CalendarScreen
import hu.edzes.ui.HistoryScreen
import hu.edzes.ui.ImportScreen
import hu.edzes.ui.PlayerScreen
import hu.edzes.ui.ProgramsScreen
import hu.edzes.ui.SettingsScreen
import hu.edzes.ui.TodayScreen
import kotlinx.coroutines.launch

/** Képernyők közötti, Activity-szintű állapot. */
object UiState {
    /** Nem null: az import képernyő látszik ezzel a kezdő szöveggel. */
    var importText by mutableStateOf<String?>(null)
    /** Növeljük, ha a Strava kapcsolat állapota változik. */
    var stravaTick by mutableIntStateOf(0)
}

class MainActivity : ComponentActivity() {
    private var lastAutoSync = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { EdzesApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Előtérbe kerüléskor szinkron, de legfeljebb 10 percenként.
        if (System.currentTimeMillis() - lastAutoSync > 600_000) {
            lastAutoSync = System.currentTimeMillis()
            lifecycleScope.launch { Sync.run(applicationContext) }
        }
    }

    private fun handleIntent(intent: Intent) {
        val data = intent.data
        when {
            data?.scheme == "edzes" -> {
                val code = data.getQueryParameter("code")
                if (code == null) toast("A Strava hozzáférést nem engedélyezted")
                else lifecycleScope.launch {
                    try {
                        Strava(this@MainActivity).exchange(code)
                        UiState.stravaTick++
                        toast("Strava összekapcsolva")
                        Sync.run(applicationContext)
                    } catch (e: Exception) {
                        toast("Strava hiba: ${e.message}")
                    }
                }
            }
            intent.action == Intent.ACTION_SEND -> UiState.importText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            intent.action == Intent.ACTION_VIEW && data != null -> UiState.importText =
                runCatching { contentResolver.openInputStream(data)!!.use { it.reader().readText() } }.getOrDefault("")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

@Composable
fun EdzesApp() {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        android.os.Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors) {
        val player by Player.state.collectAsState()
        val importText = UiState.importText
        when {
            player != null -> PlayerScreen(player!!)
            importText != null -> ImportScreen(importText)
            else -> Tabs()
        }
    }
}

@Composable
private fun Tabs() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "Ma" to Icons.Default.Home, "Naptár" to Icons.Default.DateRange, "Programok" to Icons.Default.Star,
        "Előzmények" to Icons.Default.Menu, "Beállítások" to Icons.Default.Settings,
    )
    Scaffold(bottomBar = {
        NavigationBar {
            tabs.forEachIndexed { i, (label, icon) ->
                NavigationBarItem(
                    selected = tab == i, onClick = { tab = i },
                    icon = { Icon(icon, null) }, label = { Text(label, maxLines = 1) },
                )
            }
        }
    }) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> TodayScreen()
                1 -> CalendarScreen()
                2 -> ProgramsScreen()
                3 -> HistoryScreen()
                else -> SettingsScreen()
            }
        }
    }
}

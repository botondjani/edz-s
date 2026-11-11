package hu.edzes

import android.Manifest
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureScreenRoboImage
import hu.edzes.data.DONE
import hu.edzes.model.ProgramParser
import hu.edzes.player.PlayerService
import hu.edzes.sync.describe
import hu.edzes.ui.schedule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.LocalDate

/** Végigmegy a fő folyamaton: import → ütemezés → edzés lejátszása → mentés → előzmények. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hu-w411dp-h891dp-420dpi")
class AppSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val weekday = LocalDate.now().dayOfWeek.name.take(3)
    private val json = """
        {"format":"edzes-program/v1","name":"Teszt program","weeks":2,"days":[
          {"week":1,"weekday":"$weekday","workout":{"title":"Felsőtest A","sport":"strength","blocks":[
            {"type":"sets","exercise":"Fekvenyomás","sets":2,"reps":8,"weight_kg":60,"rest_s":90},
            {"type":"timed","exercise":"Plank","duration_s":30}]}},
          {"week":2,"weekday":"$weekday","workout":{"title":"Intervall futás","sport":"run","blocks":[
            {"type":"repeat","times":3,"blocks":[{"type":"segment","name":"Gyors","distance_m":400,"target":"Z4"},
                                                 {"type":"segment","name":"Kocogás","duration_s":90}]}]}}]}"""

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }
    private fun tab(name: String) = rule.onNodeWithText(name).performClick()
    /** Megvárja a háttérszálakon futó adatbázis-műveleteket, közben a fő szál üzeneteit is feldolgozza. */
    private fun await(condition: () -> Boolean) {
        repeat(250) {
            rule.waitForIdle()
            if (condition()) return
            Thread.sleep(20)
        }
        error("A feltétel 5 mp alatt sem teljesült")
    }

    private fun idle(seconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
        rule.waitForIdle()
    }

    @Test fun `import, edzes, mentes`() {
        val app = rule.activity.app
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        rule.onNodeWithText("Mára nincs tervezett edzés.").assertIsDisplayed()

        // Hibás, majd jó JSON az import képernyőn (ugyanide érkezik a megosztás a Claude appból)
        tab("Programok")
        UiState.importText = "{\"name\":\"X\",\"days\":[{\"week\":1}]}"
        rule.onNodeWithText("• program: hiányzik a \"format\" mező").assertIsDisplayed()
        shot("import-hiba")
        UiState.importText = null
        rule.waitForIdle()
        UiState.importText = json
        rule.onNodeWithText("2 hét · 2 edzés").assertIsDisplayed()
        shot("import")
        rule.onNodeWithText("Mentés és ütemezés").performScrollTo().performClick()
        await { runBlocking { app.db.dao().programs().first() }.isNotEmpty() }
        rule.waitForIdle()
        shot("utemezes")
        // A dátumválasztó helyett közvetlenül ütemezünk a mai hétre, hogy legyen mai edzés.
        UiState.importText = null
        val program = runBlocking { app.db.dao().programs().first() }.single()
        runBlocking { schedule(app, program.id, ProgramParser.parse(program.json).program!!, LocalDate.now()) }
        rule.waitForIdle()
        shot("programok")

        tab("Ma")
        await { rule.onAllNodesWithText("▶ Indítás").fetchSemanticsNodes().size == 2 }
        shot("ma")
        tab("Naptár")
        shot("naptar")
        tab("Ma")

        // Edzés: 2 sorozat pihenővel, majd időzített plank
        rule.onAllNodesWithText("▶ Indítás")[0].performClick()
        val service = Robolectric.buildService(PlayerService::class.java).create().startCommand(0, 0)
        rule.waitForIdle()
        rule.onNodeWithText("1/2. sorozat · 8 ism. × 60 kg").assertIsDisplayed()
        shot("lejatszo-sorozat")
        rule.onNodeWithText("Sorozat kész").performClick()
        idle(10)
        rule.onNodeWithText("Pihenő").assertIsDisplayed()
        rule.onNodeWithText("1:20").assertIsDisplayed()
        shot("lejatszo-piheno")
        idle(81)
        rule.onNodeWithText("2/2. sorozat · 8 ism. × 60 kg").assertIsDisplayed()
        rule.onNodeWithText("Sorozat kész").performClick()
        idle(91)
        rule.onNodeWithText("Plank").assertIsDisplayed()
        idle(31)
        rule.onNodeWithText("Minden lépés kész 🎉").assertIsDisplayed()
        rule.onNodeWithText("Befejezés").performClick()
        rule.waitForIdle()
        shot("lejatszo-befejezes")
        rule.onNodeWithText("Mentés").performClick()
        service.destroy()   // a Robolectric nem állítja le magától a stopSelf() után

        val dao = app.db.dao()
        await { runBlocking { dao.plannedOn(LocalDate.now().toEpochDay()) }.single().status == DONE }
        val saved = runBlocking { dao.activities().first() }.single()
        assertEquals("Felsőtest A", saved.title)
        assertEquals(10 + 81 + 91 + 31, saved.durationS)
        assertEquals("Fekvenyomás: 8×60 kg, 8×60 kg\nPlank: 30 mp", runBlocking { describe(dao.setLogs(saved.id)) })
        assertEquals(saved.id, runBlocking { dao.plannedOn(LocalDate.now().toEpochDay()) }.single().activityId)

        tab("Előzmények")
        await { runCatching { rule.onNodeWithText("Felsőtest A").assertIsDisplayed() }.isSuccess }
        rule.onNodeWithText("Felsőtest A").performClick()
        shot("elozmeny-reszletek")
        rule.onNodeWithText("Bezárás").performClick()
        tab("Beállítások")
        shot("beallitasok")
    }
}

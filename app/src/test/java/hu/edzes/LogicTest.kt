package hu.edzes

import hu.edzes.data.Activity
import hu.edzes.data.SetLog
import hu.edzes.model.CLAUDE_PROMPT
import hu.edzes.model.ProgramParser
import hu.edzes.model.dates
import hu.edzes.model.intervalWorkout
import hu.edzes.model.steps
import hu.edzes.model.summary
import hu.edzes.player.Engine
import hu.edzes.player.Engine.Event
import hu.edzes.player.HeartRateMonitor
import hu.edzes.sync.Strava
import hu.edzes.sync.StravaActivity
import hu.edzes.sync.TrackPoint
import hu.edzes.sync.buildTcx
import hu.edzes.sync.describe
import hu.edzes.sync.mergePoints
import hu.edzes.sync.overlaps
import hu.edzes.sync.planStrava
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LogicTest {
    private val example = ProgramParser.parse(CLAUDE_PROMPT)

    @Test fun `a promptban levo pelda ervenyes program`() {
        assertEquals(emptyList<String>(), example.errors)
        val p = example.program!!
        assertEquals("Program neve", p.name)
        assertEquals(4, p.weeks)
        assertEquals(listOf("strength", "run", "run"), p.days.map { it.workout.sport })
        assertEquals("40 perc · Z2", p.days[2].workout.target)
        assertEquals("Fekvenyomás — 4 × 8 @ 60 kg, pihenő 2 perc", p.days[0].workout.summary()[0])
    }

    @Test fun `kodblokkba es szovegbe agyazott JSON is beolvashato`() {
        val text = "Íme a terv:\n```json\n{\"format\":\"edzes-program/v1\",\"name\":\"A\",\"days\":[{\"week\":2,\"weekday\":\"sun\"," +
            "\"workout\":{\"title\":\"T\",\"sport\":\"swim\"}}]}\n```\nJó edzést!"
        val p = ProgramParser.parse(text).program!!
        assertEquals(2, p.weeks)
        assertEquals(7, p.days[0].weekday)
    }

    @Test fun `a hibak helye es oka kiderul`() {
        val errors = ProgramParser.parse(
            """{"format":"x","name":"A","days":[
               {"week":0,"weekday":"MON","workout":{"title":"T","sport":"golf","blocks":[
                 {"type":"sets","exercise":"Guggolás","sets":"3"},
                 {"type":"segment","name":"S","duration_s":60,"distance_m":100},
                 {"type":"repeat","times":2,"blocks":[{"type":"jump"}]}]}},
               {"week":1,"weekday":"HOLNAP"}]}"""
        ).errors.joinToString("\n")
        for (expected in listOf(
            "program.format", "days[0].week", "days[0].workout.sport", "days[0].workout.blocks[0].sets",
            "days[0].workout.blocks[0]: hiányzik a \"reps\"", "days[0].workout.blocks[1]: a \"duration_s\" és a \"distance_m\"",
            "days[0].workout.blocks[2].blocks[0].type", "days[1].weekday", "days[1]: hiányzik a \"workout\"",
        )) assertTrue("hiányzik: $expected\n$errors", expected in errors)
        assertEquals(listOf("Nem található JSON objektum a szövegben."), ProgramParser.parse("szia").errors)
        assertTrue(ProgramParser.parse("{ez nem json}").errors.single().startsWith("Hibás JSON"))
    }

    @Test fun `a blokkok lepesekre bomlanak`() {
        val strength = example.program!!.days[0].workout.steps()
        // 4 sorozat + 4 pihenő, 3 plank + 3 pihenő, 8 tabata kör + 8 pihenő, 3 húzódzkodás + 2 pihenő (a záró pihenő elmarad)
        assertEquals(8 + 6 + 16 + 5, strength.size)
        assertEquals("1/4. sorozat · 8 ism. × 60 kg", strength[0].info)
        assertTrue(strength[0].manual && strength[1].rest && strength[1].durationS == 120)
        assertFalse(strength.last().rest)
        assertEquals("3/3. kör · 1/1. sorozat · max ism.", strength.last().info)

        val run = example.program!!.days[1].workout.steps()
        assertEquals(1 + 6 * 2 + 5 * 2 + 1, run.size)
        assertEquals(400, run[1].distanceM)
        assertEquals("1/6. kör · Z5", run[1].info)
        assertTrue(run[2].rest && run[2].durationS == 90)
        assertEquals("1/5. kör · Z4, 4:30/km", run[13].info)
        assertEquals("Gyors — 6 × 400 m / pihenő 1 perc 30 mp · Z5", example.program!!.days[1].workout.summary()[1])
    }

    @Test fun `a program a kezdo hetfotol utemezodik`() {
        val dates = example.program!!.dates(LocalDate.of(2026, 11, 16)).map { it.first.toString() }
        assertEquals(listOf("2026-11-16", "2026-11-18", "2026-11-21"), dates)
    }

    @Test fun `a lejatszo ido, tav es kezi leptetes alapjan halad`() {
        val steps = example.program!!.days[1].workout.steps().take(3)   // 600 mp, 400 m, 90 mp pihenő
        val e = Engine(steps)
        assertNull(Engine(steps).remainingMs())   // van táv alapú lépés: nem tudható előre
        assertEquals(emptyList<Event>(), e.tick(596_000))
        assertEquals(listOf(Event.Countdown(3)), e.tick(1_000))
        assertEquals(listOf(Event.Countdown(2)), e.tick(1_000))
        e.paused = true
        assertEquals(emptyList<Event>(), e.tick(60_000))
        assertEquals(emptyList<Event>(), e.addDistance(50.0))
        e.paused = false
        assertEquals(listOf(Event.Countdown(1)), e.tick(1_000))
        // Az idő alapú szakaszon megtett táv is mérődik (részidőkhöz).
        assertEquals(emptyList<Event>(), e.addDistance(1500.0))
        assertEquals(listOf(Event.Done(steps[0], true, 600_000, 1500.0), Event.Started(steps[1])), e.tick(1_000))
        // Táv alapú szakaszon az idő nem léptet, a megtett táv igen.
        assertEquals(emptyList<Event>(), e.tick(95_000))
        assertEquals(emptyList<Event>(), e.addDistance(399.0))
        assertEquals(listOf(Event.Done(steps[1], true, 95_000, 400.5), Event.Started(steps[2])), e.addDistance(1.5))
        assertEquals(0.0, e.stepDistM, 0.0)
        assertEquals(90_000L, e.remainingMs())
        e.tick(10_000)
        assertEquals(80_000L, e.remainingMs())
        assertEquals(listOf(Event.Started(steps[1])), e.prev())
        // Kézzel átugrott időzített/táv lépés nem számít teljesítettnek.
        assertEquals(listOf(Event.Done(steps[1], false), Event.Started(steps[2])), e.next())
        assertEquals(listOf(Event.Done(steps[2], false), Event.End), e.next())
        assertNull(e.current)
        assertEquals(emptyList<Event>(), e.next())
        assertEquals(listOf(Event.Started(steps[0])), e.append(listOf(steps[0])))
        assertEquals((596 + 4 + 95 + 10) * 1000L, e.totalMs)
    }

    @Test fun `kezi sorozat a Tovabb gombbal teljesul`() {
        val steps = example.program!!.days[0].workout.steps()
        val e = Engine(steps)
        assertEquals(emptyList<Event>(), e.tick(30_000))
        assertEquals(listOf(Event.Done(steps[0], true, 30_000), Event.Started(steps[1])), e.next())
    }

    private fun activity(id: Long, startMin: Int, durMin: Int, stravaId: Long? = null) =
        Activity(id = id, source = "app", sport = "run", title = "", startMs = startMin * 60_000L, durationS = durMin * 60, stravaId = stravaId)

    private fun remote(id: Long, startMin: Int, durMin: Int) =
        StravaActivity(id, "", "run", startMin * 60_000L, durMin * 60, null, null)

    @Test fun `Strava terv - atfedot osszekot, ujat feltolt, ismeretlent letolt`() {
        assertTrue(overlaps(0, 600, 590_000, 600))
        assertFalse(overlaps(0, 600, 600_000, 600))
        val local = listOf(activity(1, 0, 30), activity(2, 100, 30), activity(3, 200, 30, stravaId = 77))
        val plan = planStrava(local, listOf(remote(55, 2, 29), remote(77, 200, 30), remote(88, 500, 10)))
        assertEquals(listOf(1L to 55L), plan.links.map { it.first.id to it.second })
        assertEquals(listOf(2L), plan.uploads.map { it.id })
        assertEquals(listOf(88L), plan.imports.map { it.id })
    }

    @Test fun `sorozatok leirasa`() {
        val logs = listOf(
            SetLog(activityId = 1, exercise = "Fekvenyomás", reps = 8, weightKg = 60.0),
            SetLog(activityId = 1, exercise = "Plank", durationS = 60),
            SetLog(activityId = 1, exercise = "Fekvenyomás", reps = 7, weightKg = 62.5),
            SetLog(activityId = 1, exercise = "Húzódzkodás", reps = 10),
            SetLog(activityId = 1, exercise = "Gyors", durationS = 92, distanceM = 400.4),
            SetLog(activityId = 1, exercise = "Gyors", durationS = 95, distanceM = 401.0),
        )
        assertEquals("Fekvenyomás: 8×60 kg, 7×62,5 kg\nPlank: 1 perc\nHúzódzkodás: 10 ism.\nGyors: 400 m 1:32, 401 m 1:35", describe(logs))
    }

    @Test fun `TCX - GPS pontok pulzussal, GPS nelkul elosztott tavval`() {
        val route = listOf(TrackPoint(10_000, 47.5, 19.0, 120.0), TrackPoint(100_000, 47.6, 19.1))
        val hr = listOf(TrackPoint(9_000, hr = 140), TrackPoint(50_000, hr = 150))
        val merged = mergePoints(route, hr)
        assertEquals(listOf(140, null), merged.map { it.hr })   // a második ponthoz a pulzus már 30 mp-nél régebbi
        val tcx = buildTcx("run", 0, 100, 1500.0, 90, merged)
        assertTrue("Sport=\"Running\"" in tcx && "<Id>1970-01-01T00:00:00Z</Id>" in tcx)
        assertTrue("<Trackpoint><Time>1970-01-01T00:00:10Z</Time><Position><LatitudeDegrees>47.5</LatitudeDegrees><LongitudeDegrees>19.0</LongitudeDegrees></Position><AltitudeMeters>120.0</AltitudeMeters><HeartRateBpm><Value>140</Value></HeartRateBpm></Trackpoint>" in tcx)
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(tcx.byteInputStream())

        val swim = buildTcx("swim", 0, 100, 1000.0, null, mergePoints(emptyList(), hr))
        assertTrue("Sport=\"Other\"" in swim && "<DistanceMeters>500.0</DistanceMeters><HeartRateBpm><Value>150" in swim)
    }

    @Test fun `Bluetooth pulzus ertelmezese`() {
        assertEquals(142, HeartRateMonitor.parseHeartRate(byteArrayOf(0x00, 142.toByte())))
        assertEquals(300, HeartRateMonitor.parseHeartRate(byteArrayOf(0x01, 0x2C, 0x01)))
        assertNull(HeartRateMonitor.parseHeartRate(byteArrayOf(0x00)))
    }

    @Test fun `intervall blokk tavval is megadhato, de csak egyfelekeppen`() {
        fun parse(work: String) = ProgramParser.parse(
            """{"format":"edzes-program/v1","name":"A","days":[{"week":1,"weekday":"MON","workout":{"title":"T","sport":"run",
               "blocks":[{"type":"interval",$work"rest_s":60,"rounds":4}]}}]}"""
        )
        assertEquals(400, parse("\"work_m\":400,").program!!.days[0].workout.blocks[0].distanceM)
        assertTrue("\"work_s\" és a \"work_m\"" in parse("").errors.single())
        assertTrue("\"work_s\" és a \"work_m\"" in parse("\"work_m\":400,\"work_s\":60,").errors.single())
    }

    @Test fun `intervall-szerkeszto edzese`() {
        val run = intervalWorkout("run", "", 10, 400, true, 90, 6, 5)
        assertEquals("6 × 400 m / 90 mp", run.title)
        assertEquals(listOf("Bemelegítés — 10 perc", "Gyors — 6 × 400 m / pihenő 1 perc 30 mp", "Levezetés — 5 perc"), run.summary())
        assertEquals(1 + 12 + 1, run.steps().size)
        val gym = intervalWorkout("hiit", " Tabata ", 10, 20, false, 10, 8, 5)
        assertEquals("Tabata", gym.title)
        assertEquals(listOf("Munka — 8 × 20 mp / pihenő 10 mp"), gym.summary())
        assertEquals((8 * 20 + 7 * 10) * 1000L, Engine(gym.steps()).remainingMs())   // a záró pihenő elmarad
    }

    @Test fun `Strava duplikatum felismerese`() {
        assertEquals(123L, Strava.duplicateId("edzes.tcx duplicate of <a href='/activities/123'>Reggeli futás</a>"))
        assertNull(Strava.duplicateId("The file is empty"))
    }
}

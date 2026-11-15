package com.safelog.app.live

import com.safelog.app.model.Avatar
import com.safelog.app.model.DrivePhase
import com.safelog.app.model.ModeNotice
import com.safelog.app.model.TelltaleLevel
import com.safelog.app.model.ShopCategory
import com.safelog.app.model.cost
import com.safelog.core.SafeLogCore
import com.safelog.core.contract.Trip
import com.safelog.core.drive.PlaceMark
import com.safelog.core.ledger.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The live repository's mapping, end to end without Android: a demo trip from the app's assets goes through the
 * real core (engine + ledger) and every view is mapped into the screens' models.
 */
class LiveMappingTest {
    private fun asset(name: String): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            for (c in listOf(File(d, "src/main/assets/$name"), File(d, "app/src/main/assets/$name"), File(d, "android/app/src/main/assets/$name"))) if (c.isFile) return c
            d = d.parentFile
        }
        error("asset $name not found")
    }

    @Test fun demoTripMapsIntoScreenModels() {
        var now = 1_793_000_000_000L                      // 2026-10-26 16:33 (Asia/Seoul)
        val core = SafeLogCore(MemoryStore(), clock = { now })
        core.finishFirstRun(true, "토리")
        val b0 = Mapping.briefing(core.snapshot().briefing)
        assertTrue("briefing has missions", b0.practices.isNotEmpty())
        val lane = b0.practices.first { it.id == "lane-signal" }
        core.selectMissions(listOf(lane.id))

        val trip = asset("demo/lane_practice.csv.gz").inputStream().use { Trip.readCsv(it) }
        val facts = core.judge(trip, now, listOf(lane.id to lane.recommended), listOf(PlaceMark(0.0, "출발지")))
        assertNotNull(facts)
        now += 3_600_000L

        val s = core.snapshot()
        assertEquals(DrivePhase.Parked, Mapping.phase(s.phase))
        assertEquals("the screen and the core agree on the driver limit", com.safelog.core.ledger.Rules.MAX_DRIVERS, com.safelog.app.model.MAX_DRIVERS)
        val a = Mapping.arrival(s.arrival)!!
        assertEquals("차선 바꾸기 3초 전 깜빡이", a.practiceResult!!.label)
        assertTrue(a.points >= 0 && a.xp >= 0)

        val r = Mapping.records(s.records)
        assertEquals(1, r.journal.size)
        assertTrue("journal shows good scenes", r.journal[0].scenes.any { it.good })

        val shop = s.shop.map(Mapping::shop)
        assertEquals(10, shop.size)
        assertEquals(ShopCategory.Atlas, shop.first { it.id == "raincoat" }.category)
        assertNotNull(shop.first { it.id == "raincoat" }.image)
        assertEquals(1643, shop.first { it.id == "coffee" }.cost(r.grade))

        val atlas = Mapping.atlas(s.atlas)
        assertEquals("토리", atlas.name)
        val settings = Mapping.settings(s.settings)
        assertEquals(s.settings, Mapping.settings(settings))      // round trip
        assertEquals(1, s.drivers.map(Mapping::driver).size)
        Mapping.quiz(s.quiz)?.let { assertEquals(3, it.choices.size) }

        // S05 내 차 익히기 and the 연수 모드 notice, as the screens read them
        val tour = Mapping.carTour(s.carTour)
        assertEquals("seat", tour.required.first().id)
        assertEquals(listOf("비 오는 날", "추운 날", "더운 날", "그 밖에"), tour.extraGroups.map { it.title })
        assertTrue(tour.telltales.any { it.level == TelltaleLevel.Red })
        assertEquals(50, tour.rewardXp)
        core.demoTourStep()
        assertTrue(Mapping.carTour(core.snapshot().carTour).doneCount >= 1)
        core.demoModeFlip()
        assertNotNull(Mapping.modeNotice(core.snapshot().modeNotice))
        assertEquals(ModeNotice.TrainingOff, Mapping.modeNotice("TrainingOff"))
        assertEquals(ModeNotice.TrainingOffer, Mapping.modeNotice("TrainingOffer"))
        for (a in listOf(Avatar.Atlas, Avatar.Letter(4), Avatar.Photo("/data/x.jpg"))) assertEquals(a, Mapping.avatar(Mapping.avatar(a)))
        core.setAvatar(core.driver.id, Mapping.avatar(Avatar.Letter(2)))
        assertEquals(Avatar.Letter(2), Mapping.driver(core.snapshot().drivers.first()).avatar)
        // a next reward with a picture keeps it
        assertEquals(shop.first { it.id == "coffee" }.image, Mapping.reward(com.safelog.core.ledger.NextRewardView("아메리카노 쿠폰", 10, 1643, "COFFEE", "reward_coffee")).image)
    }
}

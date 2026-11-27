package io.github.pedroaugustoptc.pokedeck

import io.github.pedroaugustoptc.pokedeck.core.Config
import io.github.pedroaugustoptc.pokedeck.core.GameState
import io.github.pedroaugustoptc.pokedeck.core.Guard
import io.github.pedroaugustoptc.pokedeck.core.Jitter
import io.github.pedroaugustoptc.pokedeck.core.Log
import io.github.pedroaugustoptc.pokedeck.core.Points
import io.github.pedroaugustoptc.pokedeck.core.RedeemBlock
import io.github.pedroaugustoptc.pokedeck.core.Restock
import io.github.pedroaugustoptc.pokedeck.core.RestockConfig
import io.github.pedroaugustoptc.pokedeck.core.Updater
import io.github.pedroaugustoptc.pokedeck.net.KickApi
import io.github.pedroaugustoptc.pokedeck.net.TwitchApi
import io.github.pedroaugustoptc.pokedeck.net.TwitchPubSub
import io.github.pedroaugustoptc.pokedeck.net.TwitchWatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.random.Random

// Porte dos testes do PC (tests/PokeDeck.Tests/CoreTests.cs), menos voto e extensões.
class RestockTest {
    private var now = 1_000_000L

    private fun make(edit: (RestockConfig) -> Unit = {}): Pair<Restock, MutableList<String>> {
        val r = Restock({ now }, Random(1))
        val notes = mutableListOf<String>()
        r.note = { notes += it }
        val c = RestockConfig(on = true, goldFloor = 1000)
        edit(c)
        r.configure(c)
        return r to notes
    }

    private fun state(gold: Long, balls: Long = 500, potion: Long = 50, revive: Long = 5, maxHp: Double? = 100.0) = GameState.parse(JSONObject(
        """{"connected":true,"shopLoaded":true,"gold":$gold,"maxHp":${maxHp ?: 0},
        "balls":{"1":$balls},"items":{"10":$potion,"20":$revive},
        "ballCatalog":[{"id":1,"nome":"Poke Ball","priceGold":10,"compravel":true}],
        "shop":[{"id":"10","name":"Potion","category":"heal","npcPrice":20,"healAmount":20,"healPct":0},
                {"id":"11","name":"Super Potion","category":"heal","npcPrice":100,"healAmount":60,"healPct":0},
                {"id":"20","name":"Revive","category":"revive","npcPrice":500,"healAmount":0,"healPct":0}]}"""))

    @Test fun nothingToBuyWhenStocked() = assertTrue(make().first.tick(state(100_000)).isEmpty())

    @Test fun buysBallLotWhenBelow() {
        val m = JSONObject(make().first.tick(state(100_000, balls = 50)).single())
        assertEquals("shop.buy", m.getString("t"))
        assertEquals("ball", m.getString("kind"))
        assertEquals(500, m.getInt("qty"))
    }

    @Test fun waitsForGoldInsteadOfBuyingPartialBalls() {
        val (r, notes) = make()
        assertTrue(r.tick(state(3000, balls = 50)).isEmpty()) // 500×10 + piso 1000 > 3000
        assertTrue(notes.any { "esperando juntar" in it })
    }

    @Test fun emptyPotionsBuyWhatGoldAllows() =
        assertEquals(25, JSONObject(make().first.tick(state(1500, potion = 0)).single()).getInt("qty")) // (1500 − 1000) / 20

    @Test fun potionsComeBeforeBalls() =
        assertEquals("item", JSONObject(make().first.tick(state(100_000, balls = 0, potion = 0)).single()).getString("kind"))

    @Test fun bestValuePotionUsesHpRoom() {
        val r = make().first
        r.tick(state(100_000, maxHp = 1000.0))
        assertEquals("Potion", r.potionPick)
    }

    @Test fun waitsGapThenConfirmsPurchase() {
        val (r, notes) = make { it.balls.buy = 20000 }
        assertEquals(1, r.tick(state(1_000_000, balls = 50)).size)
        r.bought(Restock.MAX_QTY, Restock.MAX_QTY * 10)
        assertTrue(notes.any { it.startsWith("comprou") && "faltam" in it })
        assertTrue(r.tick(state(1_000_000, balls = 50)).isEmpty()) // ainda no intervalo (4–8 s)
        now += 9_000
        assertEquals(1, r.tick(state(1_000_000, balls = 50)).size)
    }

    @Test fun threeTimeoutsPauseTwoMinutes() {
        val (r, notes) = make()
        var buys = 0
        var i = 0
        while (i < 200 && !r.paused) {
            buys += r.tick(state(100_000, balls = 50)).size // o estoque nunca sobe: cada pedido vence em 15 s
            i++
            now += 1000
        }
        assertTrue(r.paused)
        assertEquals(3, buys)
        assertTrue(notes.any { "pausa de 2 min" in it })
    }
}

class JitterTest {
    @Test fun staysWithinBand() {
        val rng = Random(3)
        repeat(1000) { assertTrue(Jitter.of(100_000, 0.2, rng) in 80_000..120_000) }
        repeat(1000) { assertTrue(Jitter.between(4_000, 8_000, rng) in 4_000..8_000) }
    }
}

class PointsTest {
    @Test fun detectsRefundAndStops() {
        var now = 0L
        val p = Points("twitch") { now }
        p.apply("canal", """{"points":400,"cost":300,"title":"+15% XP PokeIdle","redeemed":true}""")
        assertEquals(100, p.channel("canal")!!.points)
        now += 5 * 60_000
        p.apply("canal", """{"points":410,"cost":300}""")
        assertNotNull(p.stopped)
        assertFalse(p.canSpend("canal"))
    }

    @Test fun notesHaveKindsAndSpentElsewhere() {
        val p = Points("Twitch")
        assertTrue(p.apply("c", """{"points":400,"claimed":true}""").any { it.first == "baú" })
        assertTrue(p.apply("c", """{"points":100}""").any { it.first == "gasto fora" })
        assertEquals(Log.RedeemEntry("26/11 10:00:00", 2, "Kick", "yoda", "resgate", "resgatou X"),
            Log.parse("26/11 10:00:00\t2\tKick\tyoda\tresgate\tresgatou X"))
    }

    @Test fun refusedWaitsBeforeRetry() {
        var now = 0L
        val p = Points("kick") { now }
        p.apply("canal", """{"points":400,"cost":300,"redeemed":false,"status":400}""")
        assertFalse(p.canSpend("canal"))
        now += 31 * 60_000
        assertTrue(p.canSpend("canal"))
    }

    @Test fun guardPrefersOfflineThenMostPoints() {
        val g = Guard()
        g.remember("a", """{"points":900,"cost":300}""", 0)
        g.remember("b", """{"points":400,"cost":300}""", 0)
        g.remember("c", """{"points":100,"cost":300}""", 0)
        g.remember("d", """{"points":5000,"cost":300,"paused":true}""", 0)
        assertEquals("b", g.pick(listOf("a", "b", "c", "d"), setOf("a")))
        assertEquals("a", g.pick(listOf("a", "c"), setOf("a")))
        assertNull(g.pick(listOf("c", "d"), emptySet()))
    }

    @Test fun scriptsCarryNullsAsJsonNull() {
        val s = Points.twitchScript("canal", "tok", null, null, claim = true, spend = false, refund = null)
        assertTrue("\"refund\":null" in s && "\"word\":null" in s)
        assertTrue(Points.kickScript("c", "t", "xp", spend = true, refund = 10).contains("\"refund\":10"))
    }
}

class ParseTest {
    @Test fun twitchLiveParse() {
        val r = TwitchApi.parseLive(listOf("rodil", "x", "cogu"),
            JSONObject("""{"users":[{"login":"Rodil","stream":{"id":"1"}},null,{"login":"cogu","stream":null}]}"""))
        assertEquals(true, r["rodil"])
        assertEquals(false, r["x"])
        assertEquals(false, r["cogu"])
    }

    @Test fun kickLiveParse() {
        assertEquals(true, KickApi.parseLive("""{"livestream":{"is_live":true}}"""))
        assertEquals(false, KickApi.parseLive("""{"livestream":null}"""))
        assertNull(KickApi.parseLive("""{}"""))
    }

    @Test fun lowestVideoVariant() {
        val m3u8 = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080\nhi.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=160000,CODECS=\"mp4a\"\naudio.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=284x160\nlow.m3u8\n"
        assertEquals("https://x.net/a/low.m3u8", TwitchWatcher.lowestVariant("https://x.net/a/master.m3u8", m3u8))
    }

    @Test fun pubSubClaimNotice() {
        val n = TwitchPubSub.parseNotice(JSONObject("""{"type":"claim-available","data":{"claim":{"id":"c1","channel_id":"99"}}}"""))
        assertEquals(TwitchPubSub.Notice("claim-available", "99", "c1"), n)
    }

    @Test fun configFixClamps() {
        val c = Config(accounts = emptyList(), twitchChannels = listOf(" Rodil ", "rodil", ""), kickLives = 9).fix()
        assertEquals(Config.MAX_ACCOUNTS, c.accounts.size)
        assertEquals(listOf("rodil"), c.twitchChannels)
        assertEquals(2, c.kickLives)
    }

    @Test fun configRoundTripsAndTourOnlyOnFirstRun() {
        val f = File.createTempFile("pokedeck", ".json").apply { delete() }
        try {
            val first = Config.load(f)
            assertFalse(first.tourDone) // sem arquivo: primeiro acesso
            first.accounts[1].restock.balls.below = 7
            first.redeemBlockAt = "23:00"
            first.save(f)
            val again = Config.load(f)
            assertEquals(7, again.accounts[1].restock.balls.below)
            assertEquals("23:00", again.redeemBlockAt)
            f.writeText("{nope")
            assertTrue(Config.load(f).tourDone) // arquivo estragado: padrão, sem tour, e guarda o .bad
            assertTrue(File(f.path + ".bad").exists())
        } finally {
            f.delete()
            File(f.path + ".bad").delete()
        }
    }
}

class RedeemBlockTest {
    private val noon = LocalDateTime.of(2026, 11, 26, 12, 0)

    @Test fun tenHoursLeftAtNoonCovers22h() {
        assertTrue(RedeemBlock.covers("22:00", 10 * 3600, noon))
        assertFalse(RedeemBlock.covers("22:00", 9 * 3600, noon))
    }

    @Test fun blockAlreadyPassedTodayMeansTomorrow() {
        assertEquals(noon.toLocalDate().plusDays(1).atTime(8, 0), RedeemBlock.next("08:00", noon))
        assertFalse(RedeemBlock.covers("08:00", 10 * 3600, noon))
        assertTrue(RedeemBlock.covers("08:00", 20 * 3600, noon))
    }

    @Test fun noOrBadBlockNeverBlocks() {
        for (b in listOf(null, "", "25:00", "22h")) assertFalse(RedeemBlock.covers(b, 100 * 3600, noon))
    }
}

class UpdaterTest {
    private fun latest(
        tag: String = "v0.3.0",
        url: String = "https://github.com/PedroAugustoPTC/poke-deck-mobile/releases/download/v0.3.0/PokeDeck.apk",
        digest: String = "sha256:39a9548b6c5f1961e40a2477abe7e951b73664ed492d64f646a1ec48bc3aac99",
    ) = JSONObject().put("tag_name", tag).put("html_url", "https://github.com/PedroAugustoPTC/poke-deck-mobile/releases/tag/$tag")
        .put("body", "novidades")
        .put("assets", org.json.JSONArray().put(JSONObject().put("name", "PokeDeck.apk").put("browser_download_url", url).put("digest", digest)))
        .toString()

    @Test fun readsTheLatestRelease() {
        val r = Updater.parse(latest())!!
        assertEquals(listOf(0, 3, 0), r.version)
        assertEquals("39a9548b6c5f1961e40a2477abe7e951b73664ed492d64f646a1ec48bc3aac99", r.sha256)
        assertEquals("novidades", r.notes)
    }

    @Test fun refusesForeignLinksMissingHashOrBadTag() {
        assertNull(Updater.parse(latest(url = "https://evil.example/PokeDeck.apk")))
        assertNull(Updater.parse(latest(digest = "")))
        assertNull(Updater.parse(latest(tag = "latest")))
        assertNull(Updater.parse("not json"))
    }

    @Test fun onlyNewerReleasesAndNeverTheLocalBuild() {
        val r = Updater.parse(latest())!!
        assertTrue(Updater.isNewer("0.2.0", r))
        assertTrue(Updater.isNewer("0.2.9", r))
        assertFalse(Updater.isNewer("0.3.0", r))
        assertFalse(Updater.isNewer("0.4.0", r))
        assertFalse(Updater.isNewer("1.0.0", r))
        assertFalse(Updater.isNewer("0.0.0", r))
    }
}

class ShareTest {
    @Test fun codeRoundTripKeepsDeviceFields() {
        val src = Config()
        src.twitchChannels = listOf("b", "a")
        src.kickLives = 1
        src.accounts[1].name = "Alt"
        src.accounts[1].restock.goldFloor = 777
        src.accounts[1].restock.potions.buy = 42
        src.accounts[0].muted = true
        val code = io.github.pedroaugustoptc.pokedeck.core.Share.code(src)
        assertTrue(code.startsWith("PKD1:"))

        val here = Config(tourDone = false, setupDone = false)
        here.accounts[0].muted = false
        val got = io.github.pedroaugustoptc.pokedeck.core.Share.read("  $code\n", here)
        assertEquals(listOf("b", "a"), got.twitchChannels)
        assertEquals(1, got.kickLives)
        assertEquals("Alt", got.accounts[1].name)
        assertEquals(777L, got.accounts[1].restock.goldFloor)
        assertEquals(42, got.accounts[1].restock.potions.buy)
        assertFalse(got.accounts[0].muted) // mudo é do aparelho
        assertFalse(got.tourDone)
        assertFalse(got.setupDone)
    }

    @Test fun readsPcConfigJson() {
        // o config.json do PC: PascalCase, com Layout/Timing/Zoom que o celular ignora, e KickLives até 5
        val pc = """{"Accounts":[{"Name":"PC 1","On":true,"Ultra":true,"Economy":false,"ReduceMotion":true,"Muted":true,"Zoom":1.25,
            "Lives":true,"RedeemTwitch":"shiny","RedeemKick":null,"RedeemTwitchOn":false,"RedeemKickOn":true,"Guard":false,
            "Restock":{"On":true,"GoldFloor":5000,"PotionFirst":true,"Balls":{"On":true,"Id":3,"Buy":50,"Below":10},
            "Revives":{"On":false,"Id":0,"Buy":1,"Below":1},"Potions":{"On":true,"Id":0,"Buy":9,"Below":8}}}],
            "Layout":"focus","Focus":2,"TwitchChannels":["Zed","ana"],"KickChannels":["k1"],"TwitchLives":3,"KickLives":5,
            "ClaimChest":false,"RedeemBlockAt":null,"Timing":{"Fps":60},"TourDone":true}"""
        val here = Config()
        here.accounts[1].name = "Fica"
        val got = io.github.pedroaugustoptc.pokedeck.core.Share.read(pc, here)
        assertEquals(listOf("zed", "ana"), got.twitchChannels)
        assertEquals(3, got.twitchLives)
        assertEquals(2, got.kickLives) // o celular limita a Kick a 2
        assertFalse(got.claimChest)
        val a = got.accounts[0]
        assertEquals("PC 1", a.name)
        assertTrue(a.ultra && a.lives && !a.guard && !a.redeemTwitchOn)
        assertEquals("shiny", a.redeemTwitch)
        assertTrue(a.muted) // o padrão daqui, não o do PC
        assertEquals(5000L, a.restock.goldFloor)
        assertEquals(3, a.restock.balls.id)
        assertFalse(a.restock.revives.on)
        assertEquals("Fica", got.accounts[1].name) // conta que não veio fica como estava
        assertEquals(4, got.accounts.size)
    }

    @Test fun rejectsBadInput() {
        val share = io.github.pedroaugustoptc.pokedeck.core.Share
        for (bad in listOf("oi", "PKD1:@@@", "PKD1:AAAA", "{\"x\":1}", "{nope", "{\"v\":2,\"accounts\":[]}"))
            try {
                share.read(bad, Config())
                throw AssertionError("aceitou $bad")
            } catch (e: io.github.pedroaugustoptc.pokedeck.core.Share.Invalid) {
                assertTrue(e.message!!.isNotEmpty())
            }
    }

    @Test fun fileJsonHasNoDeviceFields() {
        val o = io.github.pedroaugustoptc.pokedeck.core.Share.json(Config())
        assertEquals(1, o.getInt("v"))
        assertFalse(o.has("tourDone") || o.has("setupDone"))
        assertFalse(o.getJSONArray("accounts").getJSONObject(0).has("muted"))
    }
}

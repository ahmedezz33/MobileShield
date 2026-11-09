package com.ahmed.shield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterTest {

    @Test
    fun parsesBlocklistFormats() {
        assertEquals("ads.example.com", Blocklist.parseLine("0.0.0.0 ads.example.com"))
        assertEquals("ads.example.com", Blocklist.parseLine("127.0.0.1\tADS.example.com # comment"))
        assertEquals("tracker.net", Blocklist.parseLine("||tracker.net^"))
        assertEquals("tracker.net", Blocklist.parseLine("||tracker.net^\$important"))
        assertEquals("plain.org", Blocklist.parseLine("plain.org"))
        assertNull(Blocklist.parseLine("0.0.0.0 0.0.0.0"))
        assertNull(Blocklist.parseLine("127.0.0.1 localhost"))
        assertNull(Blocklist.parseLine("! comment"))
        assertNull(Blocklist.parseLine("@@||allowed.com^"))
        assertNull(Blocklist.parseLine("/regex.*/"))
        assertNull(Blocklist.parseLine("# header"))
    }

    @Test
    fun detectsDohResolversAndSubdomains() {
        assertTrue(Blocklist.isDoh("dns.google"))
        assertTrue(Blocklist.isDoh("chrome.cloudflare-dns.com"))
        assertTrue(Blocklist.isDoh("abc123.dns.nextdns.io"))
        assertFalse(Blocklist.isDoh("google.com"))
        assertFalse(Blocklist.isDoh("example.com"))
    }

    @Test
    fun localAddressesAreNeverDirect() {
        listOf(
            "10.1.2.3", "192.168.1.1", "172.16.0.5", "127.0.0.1", "169.254.1.1",
            "100.64.0.1", "224.0.0.251", "255.255.255.255", "fe80::1", "fd00::1", "ff02::fb",
        ).forEach { assertTrue(it, IpUtil.isLocal(it)) }
        listOf("8.8.8.8", "157.240.1.35", "2a03:2880:f12f:83:face:b00c::25de", "100.128.0.1")
            .forEach { assertFalse(it, IpUtil.isLocal(it)) }
        assertFalse(IpUtil.isLocal("example.com")) // never resolved
    }

    @Test
    fun dnsMapRemembersAnswersForADay() {
        val now = 1_000_000_000_000L
        DnsMap.put("203.0.113.7", "cdn.example.com", now)
        assertTrue(DnsMap.isKnown("203.0.113.7", now + 60_000))
        assertEquals("cdn.example.com", DnsMap.name("203.0.113.7"))
        assertFalse(DnsMap.isKnown("203.0.113.7", now + 25 * 3600 * 1000L))
        assertFalse(DnsMap.isKnown("203.0.113.8", now))
    }

    @Test
    fun sinkholeAddressesMapBackToTheirDomain() {
        val name = "gateway.unityads.unity3d.com"
        Sinkhole.register(name)
        val idx = Sinkhole.indexOf(name)
        assertTrue(idx in 0 until 131072)
        val v4 = "198.${18 + (idx shr 16)}.${(idx shr 8) and 0xff}.${idx and 0xff}"
        assertEquals(name, Sinkhole.nameFor(v4))
        val b = ByteArray(16)
        b[0] = 0x20; b[1] = 0x01; b[2] = 0x0d; b[3] = 0xb8.toByte()
        b[13] = (idx shr 16).toByte(); b[14] = (idx shr 8).toByte(); b[15] = idx.toByte()
        val v6 = java.net.InetAddress.getByAddress(b).hostAddress!!
        assertEquals(name, Sinkhole.nameFor(v6))
        assertNull(Sinkhole.nameFor("8.8.8.8"))
        // FNV-1a reference value for "a" (0xe40c292c)
        assertEquals(((0xe40c292cL) % 131072).toInt(), Sinkhole.indexOf("a"))
    }

    @Test
    fun detectsTelemetryButNotTheApps() {
        assertTrue(Blocklist.isTelemetry("mobile.events.data.microsoft.com"))
        assertTrue(Blocklist.isTelemetry("mobile.pipe.aria.microsoft.com"))
        assertFalse(Blocklist.isTelemetry("copilot.microsoft.com"))
        assertFalse(Blocklist.isTelemetry("login.microsoftonline.com"))
    }

    @Test
    fun savedLogLinesRoundTrip() {
        val p = LogDisk.parse("1793800000000,10123,\"WhatsApp, Business\",com.whatsapp.w4b,TCP,\"a\"\"b\",443,,ALLOWED,WIFI")
        assertEquals(10, p.size)
        assertEquals("WhatsApp, Business", p[2])
        assertEquals("a\"b", p[5])
        assertEquals("", p[7])
        assertEquals("WIFI", p[9])
    }

    @Test
    fun privacyGroupsMatchOnlyWhenEnabled() {
        Privacy.enabled.value = setOf("microsoft", "claude_telemetry")
        assertEquals("microsoft", Privacy.blockingGroup("mobile.pipe.aria.microsoft.com"))
        assertEquals("claude_telemetry", Privacy.blockingGroup("browser-intake-us5-datadoghq.com"))
        assertNull(Privacy.blockingGroup("claude.ai"))
        assertNull(Privacy.blockingGroup("samsung-auth.edge.hiyaapi.com")) // hiya group is off
        Privacy.enabled.value = setOf("hiya")
        assertEquals("hiya", Privacy.blockingGroup("samsung-auth.edge.hiyaapi.com"))
        Privacy.enabled.value = emptySet()
    }

    @Test
    fun parsesMvtIndexAndStix() {
        val yaml = """
indicators:
  -
    type: github
    name: Predator Spyware Indicators of Compromise
    sources:
      - Meta
    github:
      owner: mvt-project
      repo: mvt-indicators
      branch: main
      path: intellexa_predator/predator.stix2
  -
    type: download
    name: Other
    download_url: https://example.org/other.stix2
""".trimIndent()
        val idx = Spyware.parseIndex(yaml)
        assertEquals(2, idx.size)
        assertEquals("https://raw.githubusercontent.com/mvt-project/mvt-indicators/main/intellexa_predator/predator.stix2", idx[0].second)
        assertEquals("Predator Spyware Indicators of Compromise", idx[0].first)
        assertEquals("https://example.org/other.stix2", idx[1].second)

        val stix = """{"objects":[
          {"type":"indicator","pattern":"[domain-name:value = 'Bad.Example.COM']"},
          {"type":"indicator","pattern":"[app:id = 'com.spy.App']"},
          {"type":"indicator","pattern":"[file:hashes.sha256 = 'ABCDEF']"},
          {"type":"indicator","pattern":"[ipv4-addr:value = '203.0.113.9']"},
          {"type":"indicator","pattern":"[email-addr:value = 'x@y.z']"}]}"""
        val lines = Spyware.parseStix(stix, "Predator")
        assertEquals(4, lines.size)
        Spyware.load(lines)
        assertEquals("Predator", Spyware.domainSet("cdn.bad.example.com"))
        assertEquals("Predator", Spyware.ipSet("203.0.113.9"))
        assertNull(Spyware.domainSet("example.com"))
        assertTrue(lines.contains("app\tcom.spy.App\tPredator")) // package names keep their case
        Spyware.load(emptyList())
    }

    @Test
    fun pagesKeepWhatTheyNeedToShow() {
        assertTrue(Blocklist.isAllowed("cdn.cookielaw.org"))
        assertTrue(Blocklist.isAllowed("assets-jpcust.jwpsrv.com"))
        assertTrue(Blocklist.isAllowed("t.co"))
        assertFalse(Blocklist.isAllowed("googleads.g.doubleclick.net"))
        assertFalse(Blocklist.isAllowed("tpc.googlesyndication.com"))
    }

    @Test
    fun verdictBlockedFlag() {
        assertFalse(Verdict.ALLOWED.blocked)
        assertFalse(Verdict.DIRECT_SEEN.blocked)
        assertTrue(Verdict.BLOCKED_DIRECT.blocked)
        assertTrue(Verdict.BLOCKED_BYPASS.blocked)
    }
}

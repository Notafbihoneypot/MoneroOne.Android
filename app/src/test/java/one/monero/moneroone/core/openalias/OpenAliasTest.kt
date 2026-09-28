package one.monero.moneroone.core.openalias

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import one.monero.moneroone.core.wallet.FakeSharedPreferences
import one.monero.moneroone.core.wallet.SendFlow
import one.monero.moneroone.core.wallet.SendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec section 8.1: classification, TXT decode, OA1 parse and select, sanitize, answer mapping, pins. */
class OpenAliasTest {

    // ---------- Fixtures ----------

    private val getmoneroXmr =
        "888tNkZrPN6JsEgekjMnABU4TBzc2Dt29EPAvkRxbANsAnjyPbb3iQ1YBRk1UXcdRsiKc9dhwMVgN5S9cQUiyoogDavup3H"
    private val openaliasXmr =
        "46BeWrHpwXmHDpDEUmZBWZfoQpdc6HaERCNmx1pEYL2rAcuwufPN9rXHHtyUA4QVy66qeFQkn6sfK8aHYjA3jk3o1Bv16em"

    /** Valid on testnet only (the fake check below). */
    private val testnetAddress = "9" + "t".repeat(94)
    private val sendFlow = SendFlow("wallet-1")

    private val getmoneroRecords = listOf(
        "oa1:btc recipient_address=1KTexdemPdxSBcG55heUuTjDRYqbC5ZL8H; recipient_name=Monero Development; tx_description=Donation to Monero Core Team;",
        "oa1:xmr recipient_address=$getmoneroXmr; recipient_name=Monero Development; tx_description=Donation to Monero Core Team;",
    )

    private val openaliasRecords = listOf(
        "oa1:xmr recipient_address=$openaliasXmr; recipient_name=Monero Development; tx_description=Donation to Monero Core Team;",
        "v=spf1 include:spf.improvmx.com ~all",
        "oa1:btc recipient_address=1FhnVJi2V1k4MqXm2nHoEbY5LV7FPai7bb; recipient_name=Monero Development; tx_description=Donation to Monero Core Team;",
    )

    /** Fixture addresses are valid on mainnet; one that starts with 9 is valid on testnet only. */
    private val check: (String) -> AddressCheck = { address ->
        when {
            address.startsWith("9") && address.length == 95 -> AddressCheck.OTHER_NETWORK
            (address.startsWith("4") || address.startsWith("8")) && address.length in listOf(95, 106) -> AddressCheck.VALID
            else -> AddressCheck.INVALID
        }
    }

    /** TXT RDATA as the kit returns it: hex of length-prefixed character-strings. */
    private fun rdata(vararg strings: String): String {
        val bytes = mutableListOf<Byte>()
        for (s in strings) {
            val b = s.toByteArray(Charsets.UTF_8)
            require(b.size <= 255)
            bytes += b.size.toByte()
            bytes += b.toList()
        }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** One record of any length, split into 255-byte strings as DNS servers do. */
    private fun record(text: String): String {
        val b = text.toByteArray(Charsets.UTF_8)
        val chunks = b.toList().chunked(255).map { String(it.toByteArray(), Charsets.UTF_8) }
        return rdata(*chunks.toTypedArray())
    }

    private fun okJson(
        records: List<String>,
        secure: Boolean = true,
        bogus: Boolean = false,
        rcode: Int = 0,
        havedata: Boolean = records.isNotEmpty(),
        nxdomain: Boolean = false,
    ): String {
        val list = records.joinToString(",") { "\"$it\"" }
        return """{"status":"ok","rcode":$rcode,"havedata":$havedata,"nxdomain":$nxdomain,"secure":$secure,"bogus":$bogus,"why_bogus":"","records":[$list]}"""
    }

    private fun resolved(address: String, name: String?, dnssec: DnssecStatus) =
        OpenAliasOutcome.Resolved(OpenAliasResult(address, name, dnssec))

    private fun failed(failure: OpenAliasFailure) = OpenAliasOutcome.Failed(failure)

    // ---------- 4.1 Classification ----------

    private fun assertName(input: String, query: String, display: String) {
        assertEquals(input, RecipientInput.Name(OpenAliasName(display = display, query = query)), OpenAlias.classify(input))
    }

    @Test
    fun classifyNames() {
        assertName("donate@getmonero.org", "donate.getmonero.org", "donate@getmonero.org")
        assertName(" Donate@GetMonero.org ", "donate.getmonero.org", "donate@getmonero.org")
        assertName("donate.getmonero.org", "donate.getmonero.org", "donate.getmonero.org")
        assertName("openalias.org.", "openalias.org", "openalias.org")
        assertName("_pay.example.com", "_pay.example.com", "_pay.example.com")
        assertName("pay.xn--mnchen-3ya.de", "pay.xn--mnchen-3ya.de", "pay.xn--mnchen-3ya.de")
        assertName("pay.example.xn--p1ai", "pay.example.xn--p1ai", "pay.example.xn--p1ai")
    }

    @Test
    fun classifyAddresses() {
        assertEquals(RecipientInput.Address(getmoneroXmr), OpenAlias.classify(getmoneroXmr))
        val link = "monero:$openaliasXmr?tx_amount=1"
        assertEquals(RecipientInput.Address(link), OpenAlias.classify(link))
        assertEquals(RecipientInput.Address("joe"), OpenAlias.classify("joe"))
    }

    @Test
    fun classifyEmpty() {
        assertEquals(RecipientInput.Empty, OpenAlias.classify(""))
        assertEquals(RecipientInput.Empty, OpenAlias.classify("  \n "))
    }

    @Test
    fun classifyReserved() {
        for (input in listOf("@joe", "joe@monero.one", "monero.one", "a.b.monero.one", "MONERO.ONE")) {
            assertEquals(input, RecipientInput.Reserved, OpenAlias.classify(input))
        }
    }

    @Test
    fun classifyInvalidNames() {
        val longLabel = "a".repeat(64) + ".com"
        // 63 + 1 + 63 + 1 + 63 + 1 + 58 + 4 = 254 characters.
        val longName = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(58) + ".com"
        assertEquals(254, longName.length)
        val invalid = listOf(
            "@getmonero.org", "joe@", "a@b@c.com",
            "dоnate@getmonero.org", // Cyrillic "о"
            "-pay.example.com", "a..b.com", "pay.example.123",
            "pay.onion", "joe@localhost",
            "donate.getmonero.org:443", "pay.example.com/x",
            longLabel, longName,
        )
        for (input in invalid) {
            assertEquals(input, RecipientInput.InvalidName, OpenAlias.classify(input))
        }
    }

    @Test
    fun classifyLengthLimitsPassAtTheBoundary() {
        val label63 = "a".repeat(63) + ".com"
        assertTrue(OpenAlias.classify(label63) is RecipientInput.Name)
        val name253 = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(57) + ".com"
        assertEquals(253, name253.length)
        assertTrue(OpenAlias.classify(name253) is RecipientInput.Name)
    }

    // ---------- 4.2 TXT decode ----------

    @Test
    fun decodeOneString() {
        assertEquals("oa1:xmr a=b", OpenAlias.decodeTxt(rdata("oa1:xmr a=b")))
    }

    @Test
    fun decodeTwoStringsJoined() {
        assertEquals("oa1:xmr recipient_address=abc", OpenAlias.decodeTxt(rdata("oa1:xmr recipient_", "address=abc")))
    }

    @Test
    fun decodeLengthPastTheEndIsDropped() {
        assertNull(OpenAlias.decodeTxt("05616263"))
    }

    @Test
    fun decodeEmptyRdataIsDropped() {
        assertNull(OpenAlias.decodeTxt(""))
    }

    @Test
    fun decodeBadUtf8IsDropped() {
        assertNull(OpenAlias.decodeTxt("01ff"))
    }

    @Test
    fun decodeBadHexIsDropped() {
        assertNull(OpenAlias.decodeTxt("0g61"))
        assertNull(OpenAlias.decodeTxt("016"))
    }

    // ---------- 4.3 and 4.4 Parse and select ----------

    private fun select(records: List<String>, dnssec: DnssecStatus = DnssecStatus.VALID) =
        OpenAlias.select(records, dnssec, check)

    @Test
    fun getmoneroFixture() {
        assertEquals(resolved(getmoneroXmr, "Monero Development", DnssecStatus.VALID), select(getmoneroRecords))
    }

    @Test
    fun openaliasFixture() {
        assertEquals(resolved(openaliasXmr, "Monero Development", DnssecStatus.OFF), select(openaliasRecords, DnssecStatus.OFF))
    }

    @Test
    fun capitalPrefixIsAccepted() {
        assertEquals(resolved(getmoneroXmr, null, DnssecStatus.VALID), select(listOf("OA1:XMR recipient_address=$getmoneroXmr;")))
    }

    @Test
    fun longerPrefixIsSkipped() {
        assertEquals(failed(OpenAliasFailure.NO_RECORD), select(listOf("oa1:xmrfoo recipient_address=$getmoneroXmr;")))
    }

    @Test
    fun quotedValueWithSemicolonAndEscapedQuote() {
        val parsed = OpenAlias.parseOa1("oa1:xmr recipient_address=$getmoneroXmr; recipient_name=\"Joe; \\\"The\\\" Dev\";")
        assertEquals(listOf(getmoneroXmr), parsed?.addresses)
        assertEquals("Joe; \"The\" Dev", parsed?.recipientName)
    }

    @Test
    fun missingClosingQuoteSkipsTheRecord() {
        val text = "oa1:xmr recipient_address=$getmoneroXmr; recipient_name=\"Joe;"
        assertNull(OpenAlias.parseOa1(text))
        assertEquals(failed(OpenAliasFailure.NO_RECORD), select(listOf(text)))
    }

    @Test
    fun twoRecordsWithTheSameAddressAreAccepted() {
        val records = listOf(
            "oa1:xmr recipient_address=$getmoneroXmr;",
            "oa1:xmr recipient_address=$getmoneroXmr; tx_description=again;",
        )
        assertEquals(resolved(getmoneroXmr, null, DnssecStatus.VALID), select(records))
    }

    @Test
    fun twoRecordsWithDifferentAddressesAreRefused() {
        val records = listOf(
            "oa1:xmr recipient_address=$getmoneroXmr;",
            "oa1:xmr recipient_address=$openaliasXmr;",
        )
        assertEquals(failed(OpenAliasFailure.SEVERAL_ADDRESSES), select(records))
    }

    @Test
    fun oneRecordWithTwoAddressesIsRefused() {
        val records = listOf("oa1:xmr recipient_address=$getmoneroXmr; recipient_address=$openaliasXmr;")
        assertEquals(failed(OpenAliasFailure.SEVERAL_ADDRESSES), select(records))
    }

    @Test
    fun recordWithNoAddressGivesNoRecord() {
        assertEquals(failed(OpenAliasFailure.NO_RECORD), select(listOf("oa1:xmr recipient_name=Joe;")))
    }

    @Test
    fun paymentIdIsRefused() {
        val records = listOf("oa1:xmr recipient_address=$getmoneroXmr; tx_payment_id=abcdef0123456789;")
        assertEquals(failed(OpenAliasFailure.PAYMENT_ID), select(records))
    }

    @Test
    fun amountIsIgnored() {
        val records = listOf("oa1:xmr recipient_address=$getmoneroXmr; tx_amount=1.5;")
        assertEquals(resolved(getmoneroXmr, null, DnssecStatus.VALID), select(records))
    }

    @Test
    fun invalidAddress() {
        assertEquals(failed(OpenAliasFailure.INVALID_ADDRESS), select(listOf("oa1:xmr recipient_address=4abc;")))
    }

    @Test
    fun testnetAddressOnMainnet() {
        assertEquals(failed(OpenAliasFailure.WRONG_NETWORK), select(listOf("oa1:xmr recipient_address=$testnetAddress;")))
    }

    @Test
    fun differentNamesGiveNoName() {
        val records = listOf(
            "oa1:xmr recipient_address=$getmoneroXmr; recipient_name=Alice;",
            "oa1:xmr recipient_address=$getmoneroXmr; recipient_name=Bob;",
        )
        assertEquals(resolved(getmoneroXmr, null, DnssecStatus.VALID), select(records))
    }

    @Test
    fun segmentWithoutEqualsIsSkipped() {
        val records = listOf("oa1:xmr junk; recipient_address = $getmoneroXmr ; recipient_name=Joe")
        assertEquals(resolved(getmoneroXmr, "Joe", DnssecStatus.VALID), select(records))
    }

    // ---------- 4.5 Sanitize ----------

    @Test
    fun sanitizeRemovesBidiAndZeroWidth() {
        assertEquals("Monero Dev", OpenAlias.sanitizeName("Mon‮ero​ Dev"))
    }

    @Test
    fun sanitizeTurnsLineBreakIntoSpace() {
        assertEquals("Monero Development", OpenAlias.sanitizeName("Monero\nDevelopment"))
        assertEquals("Monero Development", OpenAlias.sanitizeName("  Monero \r\n\t Development  "))
    }

    @Test
    fun sanitizeCapsAtFortyCharacters() {
        val sixty = "abcdefghij".repeat(6)
        assertEquals("abcdefghij".repeat(4) + "…", OpenAlias.sanitizeName(sixty))
    }

    @Test
    fun sanitizeNothingLeftGivesNoName() {
        assertNull(OpenAlias.sanitizeName("\u0000\u0007​‮"))
    }

    // ---------- 3.7 Answer mapping ----------

    private fun map(json: String, synthesized: Int = 0, torOn: Boolean = false) =
        OpenAlias.mapAnswer(json, synthesized, torOn, check)

    private val getmoneroHex = getmoneroRecords.map(::record)

    @Test
    fun rule1ParseFailureIsNetworkError() {
        assertEquals(failed(OpenAliasFailure.NETWORK), map("not json"))
        assertEquals(failed(OpenAliasFailure.TOR), map("not json", torOn = true))
        assertEquals(failed(OpenAliasFailure.NETWORK), map("""{"status":"ok"}"""))
    }

    @Test
    fun rule2Timeout() {
        assertEquals(failed(OpenAliasFailure.TIMEOUT), map("""{"status":"timeout"}"""))
        assertEquals(failed(OpenAliasFailure.TIMEOUT), map("""{"status":"timeout"}""", torOn = true))
    }

    @Test
    fun rule3ErrorOrOtherStatus() {
        assertEquals(failed(OpenAliasFailure.NETWORK), map("""{"status":"error","error":"bad name"}"""))
        assertEquals(failed(OpenAliasFailure.TOR), map("""{"status":"error","error":"x"}""", torOn = true))
        assertEquals(failed(OpenAliasFailure.NETWORK), map("""{"status":"other"}"""))
    }

    @Test
    fun rule4SynthesizedWithBogusIsNetworkError() {
        val bogus = okJson(emptyList(), secure = false, bogus = true)
        assertEquals(failed(OpenAliasFailure.NETWORK), map(bogus, synthesized = 1))
        assertEquals(failed(OpenAliasFailure.TOR), map(bogus, synthesized = 1, torOn = true))
        assertEquals(failed(OpenAliasFailure.NETWORK), map(okJson(emptyList(), secure = false, rcode = 2), synthesized = 2))
    }

    @Test
    fun rule4SynthesizedWithGoodSecureAnswerStands() {
        assertEquals(
            resolved(getmoneroXmr, "Monero Development", DnssecStatus.VALID),
            map(okJson(getmoneroHex, secure = true), synthesized = 1)
        )
    }

    @Test
    fun rule5BogusIsDnssecFailed() {
        assertEquals(failed(OpenAliasFailure.DNSSEC_FAILED), map(okJson(emptyList(), secure = false, bogus = true, rcode = 2)))
    }

    @Test
    fun rule6NxdomainIsNoRecord() {
        assertEquals(failed(OpenAliasFailure.NO_RECORD), map(okJson(emptyList(), rcode = 3)))
        assertEquals(failed(OpenAliasFailure.NO_RECORD), map(okJson(emptyList(), nxdomain = true)))
    }

    @Test
    fun rule7OtherRcodeIsNetworkError() {
        assertEquals(failed(OpenAliasFailure.NETWORK), map(okJson(emptyList(), secure = false, rcode = 2)))
        assertEquals(failed(OpenAliasFailure.TOR), map(okJson(emptyList(), secure = false, rcode = 5), torOn = true))
    }

    @Test
    fun rule8NoDataIsNoRecord() {
        assertEquals(failed(OpenAliasFailure.NO_RECORD), map(okJson(emptyList(), havedata = false)))
        assertEquals(failed(OpenAliasFailure.NO_RECORD), map(okJson(emptyList(), havedata = true)))
    }

    @Test
    fun rule9SecureGivesDnssecValid() {
        assertEquals(resolved(getmoneroXmr, "Monero Development", DnssecStatus.VALID), map(okJson(getmoneroHex, secure = true)))
    }

    @Test
    fun rule9InsecureGivesDnssecOff() {
        val hex = openaliasRecords.map(::record)
        assertEquals(resolved(openaliasXmr, "Monero Development", DnssecStatus.OFF), map(okJson(hex, secure = false)))
    }

    @Test
    fun rule9RecordsWithNoXmrGiveNoRecord() {
        val hex = listOf(record("v=spf1 include:spf.improvmx.com ~all"), record("google-site-verification=abc"))
        assertEquals(failed(OpenAliasFailure.NO_RECORD), map(okJson(hex)))
    }

    // ---------- 4.6 Pins ----------

    private class Clock(var now: Long = 1_700_000_000L) {
        fun tick(): Long = now++
    }

    private fun summary(input: String, address: String): OpenAliasSummary {
        val name = (OpenAlias.classify(input) as RecipientInput.Name).name
        return OpenAliasSummary(name, address, DnssecStatus.VALID, PinState.None)
    }

    @Test
    fun pinStates() {
        val clock = Clock()
        val store = OpenAliasPinStore(FakeSharedPreferences(), clock::tick)
        assertEquals(PinState.None, store.state("donate.getmonero.org", getmoneroXmr))
        store.record("donate.getmonero.org", getmoneroXmr)
        assertEquals(PinState.Same(1_700_000_000L), store.state("donate.getmonero.org", getmoneroXmr))
        assertEquals(PinState.Changed(1_700_000_000L), store.state("donate.getmonero.org", openaliasXmr))
    }

    @Test
    fun pinsAreStoredAsSpecJson() {
        val prefs = FakeSharedPreferences()
        OpenAliasPinStore(prefs) { 1234L }.record("donate.getmonero.org", getmoneroXmr)
        val stored = Json.parseToJsonElement(prefs.getString("pins_mainnet", null)!!).jsonObject
        val entry = stored["donate.getmonero.org"]!!.jsonObject
        assertEquals(getmoneroXmr, entry["address"]!!.jsonPrimitive.content)
        assertEquals(1234L, entry["pinnedAt"]!!.jsonPrimitive.long)
        assertEquals("openalias_pins", OpenAliasPinStore.PREFS_NAME)
    }

    @Test
    fun atAndDotFormsShareOnePin() {
        val store = OpenAliasPinStore(FakeSharedPreferences(), Clock()::tick)
        assertTrue(store.recordAfterSend(SendState.Success(sendFlow, "tx"), summary("donate@getmonero.org", getmoneroXmr), getmoneroXmr))
        val dotted = (OpenAlias.classify("donate.getmonero.org") as RecipientInput.Name).name
        assertTrue(store.state(dotted.query, getmoneroXmr) is PinState.Same)
    }

    @Test
    fun entry501RemovesTheOldest() {
        val clock = Clock()
        val store = OpenAliasPinStore(FakeSharedPreferences(), clock::tick)
        for (i in 0 until 500) store.record("n$i.example.com", getmoneroXmr)
        assertEquals(500, store.count())
        store.record("n500.example.com", getmoneroXmr)
        assertEquals(500, store.count())
        assertEquals(PinState.None, store.state("n0.example.com", getmoneroXmr))
        assertTrue(store.state("n1.example.com", getmoneroXmr) is PinState.Same)
        assertTrue(store.state("n500.example.com", getmoneroXmr) is PinState.Same)
    }

    @Test
    fun clearAllEmptiesBothNetworks() {
        val store = OpenAliasPinStore(FakeSharedPreferences(), Clock()::tick)
        store.record("donate.getmonero.org", getmoneroXmr, OpenAliasPinStore.Network.MAINNET)
        store.record("donate.getmonero.org", testnetAddress, OpenAliasPinStore.Network.TESTNET)
        store.clearAll()
        assertEquals(PinState.None, store.state("donate.getmonero.org", getmoneroXmr, OpenAliasPinStore.Network.MAINNET))
        assertEquals(PinState.None, store.state("donate.getmonero.org", testnetAddress, OpenAliasPinStore.Network.TESTNET))
    }

    @Test
    fun flowWritesAPinOnlyOnSuccess() {
        val store = OpenAliasPinStore(FakeSharedPreferences(), Clock()::tick)
        val named = summary("donate@getmonero.org", getmoneroXmr)
        assertFalse(store.recordAfterSend(SendState.Idle, named, getmoneroXmr))
        assertFalse(store.recordAfterSend(SendState.Sending(sendFlow), named, getmoneroXmr))
        assertFalse(store.recordAfterSend(SendState.Error(sendFlow, "failed"), named, getmoneroXmr))
        assertFalse(store.recordAfterSend(SendState.Success(sendFlow, "tx"), null, getmoneroXmr))
        assertEquals(0, store.count())
        assertTrue(store.recordAfterSend(SendState.Success(sendFlow, "tx"), named, getmoneroXmr))
        assertTrue(store.state("donate.getmonero.org", getmoneroXmr) is PinState.Same)
    }
}

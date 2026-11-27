package ai.aneesiqbal.rozan.airplay.pairing

import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Rate limits and lockouts (02 §9.1, §9.2), through the service and in their parts. */
class LockoutTest {
    private val fixture = PairingFixture(PinMode.DIGEST)
    private val service = fixture.service
    private val display = fixture.display
    private val clock = fixture.clock
    private val uri = "rtsp://h/1"

    private fun wrong(code: String): String = ((code.toInt() + 1) % 10_000).toString().padStart(4, '0')

    private fun ConnectionPairing.setup(authorization: String? = null): SetupAuthorization =
        authorizeSetup("SETUP", uri, authorization, "dev", null)

    /** One wrong Digest answer on [connection]; returns what the receiver said. */
    private fun failDigest(connection: ConnectionPairing): SetupAuthorization {
        val nonce = DigestClient.nonce(assertIs<SetupAuthorization.Challenge>(connection.setup()).wwwAuthenticate)
        return connection.setup(DigestClient.authorization(wrong(display.lastCode), nonce, uri))
    }

    /** [count] Digest failures from [client], spaced so that the code throttles never interfere. */
    private fun failDigest(
        client: InetAddress,
        count: Int,
    ): SetupAuthorization {
        val connection = service.openConnection(client)
        var last: SetupAuthorization = SetupAuthorization.Authorized
        repeat(count) {
            clock.advanceSeconds(6)
            last = failDigest(connection)
        }
        return last
    }

    private fun pinStartRefused(client: InetAddress) {
        val before = display.events.size
        assertPairingError(470, false, service.pinStart(client, null), "pair-pin-start from $client")
        assertEquals(before, display.events.size, "nothing shown")
    }

    @Test
    fun `five failures are free and the sixth locks the address out of everything`() {
        val sender = Addresses.sender
        assertIs<SetupAuthorization.Challenge>(failDigest(sender, 5))
        assertFalse(PinResult.LOCKED_OUT in display.results)

        val refused = assertIs<SetupAuthorization.Refused>(failDigest(sender, 1))
        assertEquals(401, refused.status)
        assertFalse(refused.close)
        assertEquals(PinResult.LOCKED_OUT, display.results.last())
        val shown = display.shownCodes.size

        // While locked: no codes, nothing checked, plain 401 for Digest, 470 for the PIN endpoints.
        pinStartRefused(sender)
        val fresh = service.openConnection(sender)
        val lockedSetup = assertIs<SetupAuthorization.Refused>(fresh.setup())
        assertEquals(401, lockedSetup.status)
        assertPairingError(470, false, fresh.pairSetupPin(PinPairingClient(pin = "0000").step1()))
        assertEquals(shown, display.shownCodes.size)

        // Other addresses are unaffected.
        assertIs<PairingReply.Ok>(service.pinStart(Addresses.other, null))
    }

    @Test
    fun `lockouts last 30 s and double with each further failure up to 10 min`() {
        val sender = Addresses.sender
        failDigest(sender, 6)
        clock.advanceSeconds(29)
        pinStartRefused(sender)
        clock.advanceSeconds(1)
        assertIs<PairingReply.Ok>(service.pinStart(sender, null))

        // The 7th failure locks for 60 s. That was the address's third unpaired code in 10 minutes, so wait
        // the code budget out first; the failure record is kept for an hour.
        clock.advanceSeconds(600)
        assertIs<SetupAuthorization.Refused>(failDigest(sender, 1))
        clock.advanceSeconds(59)
        pinStartRefused(sender)
        clock.advanceSeconds(1)
        assertIs<PairingReply.Ok>(service.pinStart(sender, null))
    }

    @Test
    fun `a success clears the failure record`() {
        val sender = Addresses.sender
        failDigest(sender, 5)
        clock.advanceSeconds(6)
        val connection = service.openConnection(sender)
        val nonce = DigestClient.nonce(assertIs<SetupAuthorization.Challenge>(connection.setup()).wwwAuthenticate)
        assertSame(
            SetupAuthorization.Authorized,
            connection.setup(DigestClient.authorization(display.lastCode, nonce, uri)),
        )
        assertIs<SetupAuthorization.Challenge>(failDigest(sender, 5))
        assertFalse(PinResult.LOCKED_OUT in display.results)
    }

    @Test
    fun `SRP and Digest failures count together`() {
        val sender = Addresses.sender
        failDigest(sender, 3)
        clock.advanceSeconds(6)
        assertIs<PairingReply.Ok>(service.pinStart(sender, null))
        repeat(3) {
            val connection = service.openConnection(sender)
            val client = PinPairingClient(pin = wrong(display.lastCode))
            val step1 = assertIs<PairingReply.Ok>(connection.pairSetupPin(client.step1()), "attempt $it")
            assertPairingError(470, true, connection.pairSetupPin(client.step2(step1.body)))
        }
        assertEquals(PinResult.LOCKED_OUT, display.results.last())
        pinStartRefused(sender)
    }

    @Test
    fun `IPv6 addresses are counted one by one, not by their prefix`() {
        val a = Addresses.ipv6(1, 1)
        val b = Addresses.ipv6(1, 2)
        assertNotEquals(lockoutKey(a), lockoutKey(b))
        failDigest(a, 6)
        pinStartRefused(a)
        assertIs<PairingReply.Ok>(service.pinStart(b, null), "another host in the same /64 is not locked out")
    }

    @Test
    fun `lockout keys`() {
        assertEquals("10.0.1.2", lockoutKey(InetAddress.getByName("10.0.1.2")))
        assertNotEquals(
            lockoutKey(InetAddress.getByName("fe80::1")),
            lockoutKey(InetAddress.getByName("fe80::abcd:1")),
        )
        assertEquals(lockoutKey(InetAddress.getByName("fe80::1")), lockoutKey(InetAddress.getByName("fe80:0::0:1")))
        val scoped = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 1)
        assertEquals(lockoutKey(InetAddress.getByName("fe80::1")), lockoutKey(scoped), "the scope is left out")
    }

    @Test
    fun `one code per address and none within 5 s of the last`() {
        val sender = Addresses.sender
        val connection = service.openConnection(sender)
        assertIs<SetupAuthorization.Challenge>(connection.setup())
        clock.advanceSeconds(1)
        pinStartRefused(sender)
        clock.advanceSeconds(4)
        assertIs<PairingReply.Ok>(service.pinStart(sender, null))
        assertEquals(2, display.shownCodes.size)
    }

    @Test
    fun `at most 6 new codes per minute across all addresses`() {
        // One prompt at a time: each address's Digest prompt ends (its connection closes) before the next.
        for (n in 1..6) {
            val prompt = service.openConnection(Addresses.ipv4(n))
            assertIs<SetupAuthorization.Challenge>(prompt.setup(), "address $n")
            prompt.close()
        }
        pinStartRefused(Addresses.ipv4(7))
        val connection = service.openConnection(Addresses.ipv4(8))
        assertIs<SetupAuthorization.Refused>(connection.setup(), "a Digest prompt cannot show a code either")
        clock.advanceSeconds(60)
        assertIs<PairingReply.Ok>(service.pinStart(Addresses.ipv4(7), null))
    }

    @Test
    fun `more than 20 failures in 10 min stop new codes for 10 min and end the prompts on screen`() {
        // 7 addresses, 3 Digest failures each (within each code's budget of 4), 11 s apart so that the
        // 6-codes-per-minute cap never applies. Each gives up (closes) before the next can get a prompt;
        // the last one's is on screen when its third failure, the 21st, starts the suspension.
        var last: SetupAuthorization? = null
        for (n in 1..7) {
            clock.advanceSeconds(11)
            val connection = service.openConnection(Addresses.ipv4(n))
            repeat(3) { last = failDigest(connection) }
            if (n < 7) {
                assertIs<SetupAuthorization.Challenge>(last)
                connection.close()
            }
        }
        assertIs<SetupAuthorization.Refused>(last)
        val finished = display.events.filterIsInstance<RecordingDisplay.Finished>()
        assertEquals(List(6) { PinResult.CANCELLED } + PinResult.LOCKED_OUT, finished.map { it.result })
        assertEquals(Addresses.ipv4(7), finished.last().client)

        clock.advanceSeconds(61)
        pinStartRefused(Addresses.ipv4(100))
        clock.advanceSeconds(TimeUnit.MINUTES.toSeconds(10) - 61)
        assertIs<PairingReply.Ok>(service.pinStart(Addresses.ipv4(100), null))
    }

    @Test
    fun `lockout table - free failures, doubling and the cap`() {
        val table = LockoutTable()
        val t0 = 1_000L
        val second = TimeUnit.SECONDS.toNanos(1)
        repeat(5) { assertFalse(table.recordFailure("k", t0), "failure ${it + 1}") }
        assertFalse(table.isLocked("k", t0))
        assertTrue(table.recordFailure("k", t0))
        assertTrue(table.isLocked("k", t0 + 30 * second - 1))
        assertFalse(table.isLocked("k", t0 + 30 * second))
        var now = t0
        for (expected in listOf(60L, 120, 240, 480, 600, 600, 600)) {
            now += 1_000 * second
            assertTrue(table.recordFailure("k", now))
            assertTrue(table.isLocked("k", now + expected * second - 1), "$expected s")
            assertFalse(table.isLocked("k", now + expected * second), "$expected s")
        }
        table.recordSuccess("k")
        assertFalse(table.isLocked("k", now))
        assertEquals(0, table.size)
    }

    @Test
    fun `lockout table - idle records are dropped after an hour`() {
        val table = LockoutTable()
        val hour = TimeUnit.HOURS.toNanos(1)
        table.recordFailure("idle", 0)
        repeat(6) { table.recordFailure("locked", 0) }
        table.recordFailure("recent", hour / 2)
        table.prune(hour - 1)
        assertEquals(3, table.size)
        // A lockout lasts at most 10 min after the last failure, so an hour-idle record is never locked.
        table.prune(hour)
        assertEquals(1, table.size)
        assertFalse(table.recordFailure("recent", hour), "its second failure is still free")
    }

    @Test
    fun `lockout table - full of locked records it never evicts them`() {
        val table = LockoutTable(capacity = 4)
        for (k in 0 until 4) repeat(6) { table.recordFailure("locked$k", 0) }
        assertFalse(table.recordFailure("new", 1))
        assertEquals(4, table.size)
        for (k in 0 until 4) assertTrue(table.isLocked("locked$k", 1))
    }

    @Test
    fun `lockout table - evicts the oldest unlocked record when full`() {
        val table = LockoutTable(capacity = 3)
        table.recordFailure("old", 0)
        table.recordFailure("newer", 10)
        repeat(6) { table.recordFailure("locked", 20) }
        table.recordFailure("newest", 30)
        assertEquals(3, table.size)
        // "old" was evicted: it starts again from one failure, "newer" kept its count.
        repeat(4) { assertFalse(table.recordFailure("newer", 40)) }
        assertTrue(table.recordFailure("newer", 40), "newer had 1 failure before, so this is its 6th")
        assertTrue(table.isLocked("locked", 40))
    }

    @Test
    fun `global failure counter`() {
        val counter = GlobalFailures()
        val minute = TimeUnit.MINUTES.toNanos(1)
        repeat(20) { assertFalse(counter.record(it.toLong())) }
        assertFalse(counter.isSuspended(20))
        // Failures older than 10 min no longer count.
        assertFalse(counter.record(10 * minute))
        assertFalse(counter.isSuspended(10 * minute))
        repeat(20) { counter.record(11 * minute) }
        assertTrue(counter.isSuspended(11 * minute))
        assertTrue(counter.isSuspended(21 * minute - 1))
        assertFalse(counter.isSuspended(21 * minute))
    }

    @Test
    fun `an address gets at most 3 unpaired codes in 10 min, so it cannot take the global budget`() {
        val sender = Addresses.sender
        repeat(3) {
            assertIs<PairingReply.Ok>(service.pinStart(sender, null))
            clock.advanceSeconds(5)
        }
        assertEquals(3, display.shownCodes.size)
        pinStartRefused(sender)
        assertPairingError(470, false, service.pinStart(sender, null), "polling does not help")
        // Superseded and expired codes count alike; the window frees the first one 10 min after it.
        clock.advanceSeconds(TimeUnit.MINUTES.toSeconds(10) - 15 - 1)
        service.tick(sessionActive = false)
        assertEquals(PinResult.CANCELLED, display.results.last(), "the third code expired")
        pinStartRefused(sender)
        clock.advanceSeconds(1)
        assertIs<PairingReply.Ok>(service.pinStart(sender, null))
        assertEquals(4, display.shownCodes.size)
    }

    @Test
    fun `while one address's code is on screen other addresses get 409 and nothing is shown`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val service = fixture.service
        assertIs<PairingReply.Ok>(service.pinStart(Addresses.sender, "Owner's Mac"))
        val events = fixture.display.events.size
        assertPairingError(409, true, service.pinStart(Addresses.other, "Owner's Mac"))
        val refused =
            assertIs<SetupAuthorization.Refused>(
                service.openConnection(Addresses.other).authorizeSetup("SETUP", uri, null, "dev", "Owner's Mac"),
            )
        assertEquals(409, refused.status)
        assertTrue(refused.close)
        assertEquals(events, fixture.display.events.size, "the first prompt is the only one, untouched")

        // The prompt's own address may still replace it; once it is over, another address gets a code.
        fixture.clock.advanceSeconds(5)
        assertIs<PairingReply.Ok>(service.pinStart(Addresses.sender, null))
        service.openConnection(Addresses.sender).pinPair(PinPairingClient(pin = fixture.display.lastCode))
        assertIs<PairingReply.Ok>(service.pinStart(Addresses.other, null))
        assertEquals(
            Addresses.other,
            fixture.display.events
                .filterIsInstance<RecordingDisplay.Shown>()
                .last()
                .client,
        )
    }

    @Test
    fun `a Digest prompt holds the screen until its connection closes`() {
        val prompt = service.openConnection(Addresses.sender)
        assertIs<SetupAuthorization.Challenge>(prompt.setup())
        assertPairingError(409, true, service.pinStart(Addresses.other, null))
        prompt.close()
        assertEquals(PinResult.CANCELLED, display.results.last())
        assertIs<PairingReply.Ok>(service.pinStart(Addresses.other, null))
    }

    @Test
    fun `prompt callbacks reach the screen in the order the prompts changed`() {
        assertIs<PairingReply.Ok>(service.pinStart(Addresses.sender, null))
        clock.advanceSeconds(120)
        val finishing = CountDownLatch(1)
        val release = CountDownLatch(1)
        display.onEvent = { event ->
            if (event is RecordingDisplay.Finished) {
                finishing.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        // One thread notices the expiry and is still telling the screen when another asks for a code.
        val expiring = Thread { service.tick(sessionActive = false) }.apply { start() }
        assertTrue(finishing.await(5, TimeUnit.SECONDS))
        var reply: PairingReply? = null
        val asking = Thread { reply = service.pinStart(Addresses.other, null) }.apply { start() }
        asking.join(300)
        assertTrue(asking.isAlive, "the new prompt waits until the old one's finish has been delivered")
        release.countDown()
        expiring.join(5_000)
        asking.join(5_000)
        assertIs<PairingReply.Ok>(reply)
        assertEquals(
            listOf(RecordingDisplay.Finished::class.java, RecordingDisplay.Shown::class.java),
            display.events.drop(1).map { it.javaClass },
        )
    }

    @Test
    fun `once its prompt is over an address gets a new code at once`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val service = fixture.service
        // Paired: the same sender pairs again right away (the user mirrors again).
        assertIs<PairingReply.Ok>(service.pinStart(Addresses.sender, null))
        service.openConnection(Addresses.sender).pinPair(PinPairingClient(pin = fixture.display.lastCode))
        assertIs<PairingReply.Ok>(service.pinStart(Addresses.sender, null))
        assertEquals(2, fixture.display.shownCodes.size)
        // Cancelled: a Digest prompt whose connection closed.
        fixture.clock.advanceSeconds(5)
        val prompt = service.openConnection(Addresses.sender)
        assertIs<SetupAuthorization.Challenge>(prompt.authorizeSetup("SETUP", uri, null, "dev", null))
        prompt.close()
        assertIs<SetupAuthorization.Challenge>(
            service.openConnection(Addresses.sender).authorizeSetup("SETUP", uri, null, "dev", null),
        )
        assertEquals(4, fixture.display.shownCodes.size)
    }

    @Test
    fun `an address has one code at a time, PIN or Digest`() {
        val prompt = service.openConnection(Addresses.sender)
        assertIs<SetupAuthorization.Challenge>(prompt.setup())
        clock.advanceSeconds(5)
        assertIs<PairingReply.Ok>(service.pinStart(Addresses.sender, null))
        assertEquals(listOf(PinResult.CANCELLED), display.results, "the Digest prompt was replaced")
        prompt.close()
        assertEquals(listOf(PinResult.CANCELLED), display.results, "and its close ends nothing else")
    }

    @Test
    fun `codes that pair do not count against the address`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        repeat(5) {
            assertIs<PairingReply.Ok>(fixture.service.pinStart(Addresses.sender, null), "code ${it + 1}")
            fixture.service.openConnection(Addresses.sender).pinPair(PinPairingClient(pin = fixture.display.lastCode))
            fixture.clock.advanceSeconds(5)
        }
        assertEquals(List(5) { PinResult.ACCEPTED }, fixture.display.results)
    }

    @Test
    fun `code issuance limits`() {
        val issuance = CodeIssuance()
        val second = TimeUnit.SECONDS.toNanos(1)
        assertTrue(issuance.mayIssue("a", 0))
        issuance.issued("a", 0)
        assertTrue(issuance.mayIssue("a", 0), "spacing is the service's, for a code still on screen")
        for (k in 1..5) issuance.issued("k$k", 0)
        assertFalse(issuance.mayIssue("b", 59 * second))
        assertTrue(issuance.mayIssue("b", 60 * second))

        // Per client: 3 unpaired codes in 10 minutes; one that pairs is given back.
        val minute = TimeUnit.MINUTES.toNanos(1)
        issuance.issued("c", minute)
        issuance.issued("c", 2 * minute)
        issuance.issued("c", 3 * minute)
        assertFalse(issuance.mayIssue("c", 4 * minute))
        issuance.paired("c", 2 * minute)
        assertTrue(issuance.mayIssue("c", 4 * minute))
        issuance.issued("c", 4 * minute)
        assertFalse(issuance.mayIssue("c", 11 * minute - 1))
        assertTrue(issuance.mayIssue("c", 11 * minute), "the first is 10 minutes old")
        issuance.paired("unknown", 0)
    }
}

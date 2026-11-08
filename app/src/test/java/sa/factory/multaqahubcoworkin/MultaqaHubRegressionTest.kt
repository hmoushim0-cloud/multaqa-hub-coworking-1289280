package sa.factory.multaqahubcoworkin

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import sa.factory.multaqahubcoworkin.data.repository.MultaqaHubRepositoryImpl
import sa.factory.multaqahubcoworkin.engine.*
import sa.factory.multaqahubcoworkin.hardware.HardwareSensorDriver
import sa.factory.multaqahubcoworkin.model.MultaqaHubItem

class MultaqaHubRegressionTest {

    @Test
    fun testCheckoutCalculationAndVat15() = runBlocking {
        val sensorDriver = HardwareSensorDriver()
        val repository = MultaqaHubRepositoryImpl(sensorDriver)

        val item1 = MultaqaHubItem("t1", "اشتراك مكتب مرن شهري", "دخول 24/7 مع مواقف", 100.0, "معتمد", "الاشتراكات")
        val item2 = MultaqaHubItem("t2", "حزمة باقة قاعات الاجتماعات", "10 ساعات ذكية", 200.0, "معتمد", "الاشتراكات")

        repository.addToCart(item1)
        repository.addToCart(item2)

        val order = repository.checkout("Mada Contactless EMV")

        assertEquals(300.0, order.subtotalSar, 0.01)
        assertEquals(45.0, order.vatSar, 0.01)
        assertEquals(345.0, order.totalSar, 0.01)
        assertEquals(0, repository.activeCart.value.size)
    }

    @Test
    fun testIso14443aBccVerification() {
        val engine = RfidNfcTurnstileProtocolEngine()
        val uid = byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte())
        val expectedBcc = (0x04 xor 0xA1 xor 0xB2 xor 0xC3).toByte()

        assertTrue("Valid BCC check must pass", engine.verifyIso14443aBcc(uid, expectedBcc))
        assertFalse("Corrupted BCC must fail", engine.verifyIso14443aBcc(uid, (expectedBcc.toInt() xor 0x01).toByte()))
    }

    @Test
    fun testTurnstileAccessGrantedForValidBadge() {
        val engine = RfidNfcTurnstileProtocolEngine()
        val decision = engine.processBadgeSwipe("04A1B2C3", isEntryLane = true)

        assertEquals(TurnstileState.ACCESS_GRANTED_UNLOCKED, decision.state)
        assertTrue(decision.state.isUnlocked)
        assertEquals(500, decision.relayPulseDurationMs)
        assertEquals(5000, decision.passageTimeoutMs)
        assertTrue(decision.authSignatureHex.isNotEmpty())
    }

    @Test
    fun testTurnstileAccessDeniedForUnknownBadge() {
        val engine = RfidNfcTurnstileProtocolEngine()
        val decision = engine.processBadgeSwipe("99999999", isEntryLane = true)

        assertEquals(TurnstileState.ACCESS_DENIED_UNREGISTERED, decision.state)
        assertFalse(decision.state.isUnlocked)
        assertEquals(0, decision.relayPulseDurationMs)
    }

    @Test
    fun testTurnstileAccessDeniedForExpiredMembership() {
        val engine = RfidNfcTurnstileProtocolEngine()
        // 04112233 was seeded as expired
        val decision = engine.processBadgeSwipe("04112233", isEntryLane = true)

        assertEquals(TurnstileState.ACCESS_DENIED_EXPIRED, decision.state)
        assertFalse(decision.state.isUnlocked)
        assertTrue(decision.reasonMessage.contains("expired"))
    }

    @Test
    fun testTurnstileAntiPassbackViolation() {
        val engine = RfidNfcTurnstileProtocolEngine()
        val badgeUid = "04D4E5F6"

        // First entry: granted
        val firstEntry = engine.processBadgeSwipe(badgeUid, isEntryLane = true)
        assertEquals(TurnstileState.ACCESS_GRANTED_UNLOCKED, firstEntry.state)

        // Second sequential entry without exit: denied by Anti-Passback
        val secondEntry = engine.processBadgeSwipe(badgeUid, isEntryLane = true)
        assertEquals(TurnstileState.ACCESS_DENIED_ANTIPASSBACK, secondEntry.state)
        assertFalse(secondEntry.state.isUnlocked)

        // Exit: granted
        val exitSwipe = engine.processBadgeSwipe(badgeUid, isEntryLane = false)
        assertEquals(TurnstileState.ACCESS_GRANTED_UNLOCKED, exitSwipe.state)
    }

    @Test
    fun testSmartDeskReservationSuccess() {
        val engine = SmartDeskReservationEngine()
        val now = System.currentTimeMillis()
        val result = engine.reserveDesk(
            deskId = "DESK-A1",
            memberId = "MEM-101",
            startEpochMs = now + 3600000L,
            endEpochMs = now + 7200000L
        )

        assertTrue(result.isSuccess)
        assertTrue(result.digitalPassToken.isNotEmpty())
        val desk = engine.getDesk("DESK-A1")
        assertNotNull(desk)
        assertEquals(DeskOccupancyState.RESERVED, desk?.occupancyState)
    }

    @Test
    fun testSmartDeskReservationConflict() {
        val engine = SmartDeskReservationEngine()
        val now = System.currentTimeMillis()

        // First reservation
        engine.reserveDesk("DESK-A2", "MEM-101", now + 3600000L, now + 7200000L, now)

        // Second conflicting reservation
        val conflictResult = engine.reserveDesk("DESK-A2", "MEM-102", now + 3600000L, now + 7200000L, now)
        assertFalse(conflictResult.isSuccess)
        assertTrue(conflictResult.message.contains("already reserved"))
    }

    @Test
    fun testSmartDeskCheckinAndRelease() {
        val engine = SmartDeskReservationEngine()
        val now = System.currentTimeMillis()

        engine.reserveDesk("POD-01", "MEM-101", now, now + 3600000L, now)
        val checkInOk = engine.checkInDesk("POD-01", "MEM-101", now)
        assertTrue("Check-in must succeed for reserving member", checkInOk)
        assertEquals(DeskOccupancyState.OCCUPIED, engine.getDesk("POD-01")?.occupancyState)

        val releaseOk = engine.releaseDesk("POD-01")
        assertTrue(releaseOk)
        assertEquals(DeskOccupancyState.AVAILABLE, engine.getDesk("POD-01")?.occupancyState)
    }

    @Test
    fun testSmartDeskAutoReleaseNoShows() {
        val engine = SmartDeskReservationEngine()
        val now = System.currentTimeMillis()
        val pastStart = now - (20 * 60 * 1000L) // 20 minutes ago (past 15m grace)

        engine.reserveDesk("ROOM-M1", "MEM-101", pastStart, pastStart + 3600000L, pastStart)
        val released = engine.autoReleaseNoShows(now)

        assertTrue("No-show room must be auto-released", released.contains("ROOM-M1"))
        assertEquals(DeskOccupancyState.AVAILABLE, engine.getDesk("ROOM-M1")?.occupancyState)
    }
}

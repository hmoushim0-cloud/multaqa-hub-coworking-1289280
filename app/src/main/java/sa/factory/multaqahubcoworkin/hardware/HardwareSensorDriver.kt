package sa.factory.multaqahubcoworkin.hardware

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import sa.factory.multaqahubcoworkin.engine.RfidNfcTurnstileProtocolEngine
import sa.factory.multaqahubcoworkin.engine.SmartDeskReservationEngine
import kotlin.random.Random

data class HardwareTelemetryFrame(
    val peripheralName: String,
    val connectionState: String,
    val livePayload: String,
    val timestamp: Long = System.currentTimeMillis()
)

class HardwareSensorDriver {
    private val peripheral = "13.56 MHz RFID Turnstile Access + NFC Door Lock"
    private val turnstileEngine = RfidNfcTurnstileProtocolEngine()
    private val deskEngine = SmartDeskReservationEngine()

    fun observePeripheralStream(): Flow<HardwareTelemetryFrame> = flow {
        emit(HardwareTelemetryFrame(peripheral, "CONNECTING", "13.56 MHz RFID Turnstile Reader & NFC Door Lock Initializing..."))
        delay(300)

        // Baseline badge verification
        val baselineDecision = turnstileEngine.processBadgeSwipe("04A1B2C3", isEntryLane = true)
        val availableDesks = deskEngine.getAvailableDeskCount()

        emit(
            HardwareTelemetryFrame(
                peripheral,
                "ONLINE",
                "Turnstile: ${baselineDecision.state.code} | Relay: 500ms Solenoid | Available Desks: $availableDesks"
            )
        )

        var cycle = 0
        while (true) {
            delay(1500)
            cycle++

            val isEntry = (cycle % 2 == 1)
            val badgeUid = if (cycle % 3 == 0) "04D4E5F6" else "04A1B2C3"
            val decision = turnstileEngine.processBadgeSwipe(badgeUid, isEntryLane = isEntry)
            val freeDesks = deskEngine.getAvailableDeskCount()

            val payload = "Turnstile #1: ${decision.state.code} | Lane: ${if (isEntry) "IN" else "OUT"} | Member: ${decision.memberName} | Free Desks: $freeDesks | Frame #$cycle"
            emit(HardwareTelemetryFrame(peripheral, "ACTIVE_STREAMING", payload))
        }
    }

    fun getTurnstileEngine(): RfidNfcTurnstileProtocolEngine = turnstileEngine
    fun getDeskEngine(): SmartDeskReservationEngine = deskEngine
}

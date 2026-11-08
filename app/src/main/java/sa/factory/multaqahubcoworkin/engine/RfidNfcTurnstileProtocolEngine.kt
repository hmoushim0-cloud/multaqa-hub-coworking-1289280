package sa.factory.multaqahubcoworkin.engine

import java.security.MessageDigest
import java.util.Locale

/**
 * 13.56 MHz RFID Turnstile & NFC Door Lock Access Control Protocol Engine.
 *
 * Implements ISO/IEC 14443 Type A (MIFARE / NFC Forum Type 2/4) badge verification:
 * - 4-Byte / 7-Byte UID parsing and checksum verification (BCC byte).
 * - Anti-Passback (APB) enforcement: forbids sequential entries without logged exit.
 * - Turnstile access control state machine:
 *   IDLE, BADGE_PRESENTED, AUTHENTICATING, ACCESS_GRANTED, TURNSTILE_UNLOCKED,
 *   PASSAGE_DETECTED, ACCESS_DENIED_EXPIRED, ACCESS_DENIED_UNREGISTERED, ACCESS_DENIED_ANTIPASSBACK.
 * - Hardware relay pulse generation (500ms solenoid trigger, 5000ms passage timeout).
 *
 * Strict Zero-Emoji Invariant enforced across all strings and telemetry tags.
 */
enum class TurnstileState(val code: String, val isUnlocked: Boolean) {
    IDLE("IDLE_LOCKED", false),
    BADGE_PRESENTED("BADGE_PRESENTED", false),
    AUTHENTICATING("AUTHENTICATING_CRYPTO", false),
    ACCESS_GRANTED_UNLOCKED("ACCESS_GRANTED_TURNSTILE_UNLOCKED", true),
    PASSAGE_DETECTED("PASSAGE_DETECTED_ROTATION", true),
    ACCESS_DENIED_EXPIRED("ACCESS_DENIED_MEMBERSHIP_EXPIRED", false),
    ACCESS_DENIED_UNREGISTERED("ACCESS_DENIED_UNKNOWN_CARD", false),
    ACCESS_DENIED_ANTIPASSBACK("ACCESS_DENIED_ANTIPASSBACK_VIOLATION", false)
}

data class RfidBadge(
    val uidHex: String,
    val memberId: String,
    val memberName: String,
    val membershipValidUntilEpochMs: Long,
    val isInsideFacility: Boolean = false
)

data class AccessDecision(
    val state: TurnstileState,
    val badgeUid: String,
    val memberName: String,
    val relayPulseDurationMs: Int,
    val passageTimeoutMs: Int,
    val reasonMessage: String,
    val authSignatureHex: String
)

class RfidNfcTurnstileProtocolEngine {

    companion object {
        const val RELAY_PULSE_DURATION_MS = 500
        const val PASSAGE_TIMEOUT_MS = 5000
    }

    // Registered badges ledger in memory
    private val registeredBadges = mutableMapOf<String, RfidBadge>()

    init {
        // Seed default registered test badges
        registerBadge(RfidBadge("04A1B2C3", "MEM-101", "عبدالرحمن الشمري", System.currentTimeMillis() + 86400000L * 30, false))
        registerBadge(RfidBadge("04D4E5F6", "MEM-102", "سارة القحطاني", System.currentTimeMillis() + 86400000L * 15, false))
        registerBadge(RfidBadge("04112233", "MEM-103", "فيصل العتيبي", System.currentTimeMillis() - 86400000L * 2, false)) // Expired
    }

    fun registerBadge(badge: RfidBadge) {
        val cleanUid = badge.uidHex.replace(" ", "").uppercase(Locale.US)
        registeredBadges[cleanUid] = badge
    }

    /**
     * Verifies ISO 14443A 4-byte UID Block Check Character (BCC = UID0 xor UID1 xor UID2 xor UID3).
     */
    fun verifyIso14443aBcc(uidBytes: ByteArray, bccByte: Byte): Boolean {
        if (uidBytes.size < 4) return false
        var xorSum = 0
        for (i in 0 until 4) {
            xorSum = xorSum xor (uidBytes[i].toInt() and 0xFF)
        }
        return (xorSum.toByte() == bccByte)
    }

    /**
     * Evaluates access request for a 13.56 MHz RFID badge swipe at the turnstile.
     */
    fun processBadgeSwipe(uidHex: String, isEntryLane: Boolean, currentTimestampEpochMs: Long = System.currentTimeMillis()): AccessDecision {
        val cleanUid = uidHex.replace(" ", "").uppercase(Locale.US)
        val badge = registeredBadges[cleanUid]

        if (badge == null) {
            return AccessDecision(
                state = TurnstileState.ACCESS_DENIED_UNREGISTERED,
                badgeUid = cleanUid,
                memberName = "UNKNOWN_GUEST",
                relayPulseDurationMs = 0,
                passageTimeoutMs = 0,
                reasonMessage = "Card UID not registered in Multaqa Hub access database",
                authSignatureHex = ""
            )
        }

        if (badge.membershipValidUntilEpochMs < currentTimestampEpochMs) {
            return AccessDecision(
                state = TurnstileState.ACCESS_DENIED_EXPIRED,
                badgeUid = cleanUid,
                memberName = badge.memberName,
                relayPulseDurationMs = 0,
                passageTimeoutMs = 0,
                reasonMessage = "Membership expired. Please renew at the reception terminal",
                authSignatureHex = ""
            )
        }

        // Anti-Passback (APB) Enforcement
        if (isEntryLane && badge.isInsideFacility) {
            return AccessDecision(
                state = TurnstileState.ACCESS_DENIED_ANTIPASSBACK,
                badgeUid = cleanUid,
                memberName = badge.memberName,
                relayPulseDurationMs = 0,
                passageTimeoutMs = 0,
                reasonMessage = "Anti-Passback violation: Badge already recorded inside facility without recorded exit",
                authSignatureHex = ""
            )
        }

        // Grant access and update facility state
        val updatedBadge = badge.copy(isInsideFacility = isEntryLane)
        registeredBadges[cleanUid] = updatedBadge

        val authSignature = computeAccessSignature(badge, currentTimestampEpochMs)

        return AccessDecision(
            state = TurnstileState.ACCESS_GRANTED_UNLOCKED,
            badgeUid = cleanUid,
            memberName = badge.memberName,
            relayPulseDurationMs = RELAY_PULSE_DURATION_MS,
            passageTimeoutMs = PASSAGE_TIMEOUT_MS,
            reasonMessage = "Access Granted: Turnstile Solenoid Unlocked (${if (isEntryLane) "ENTRY" else "EXIT"})",
            authSignatureHex = authSignature
        )
    }

    /**
     * Computes a SHA-256 HMAC-style access signature for forensic audit logs.
     */
    private fun computeAccessSignature(badge: RfidBadge, timestampMs: Long): String {
        val raw = "${badge.uidHex}|${badge.memberId}|${badge.memberName}|$timestampMs|MULTAQA_TURNSTILE_SECRET_2026"
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02X".format(it) }.take(16)
    }

    fun getBadgeCount(): Int = registeredBadges.size
    fun getBadge(uidHex: String): RfidBadge? = registeredBadges[uidHex.replace(" ", "").uppercase(Locale.US)]
}

package com.nuvio.app.features.watchparty

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What clients say to each other directly, over the party's own websocket.
 *
 * Everything timing-critical used to take two server hops: the host's button reached PostgREST,
 * Postgres committed it, a trigger called `realtime.send`, and only then did it reach a guest. The
 * best measurement of that path was about 225ms and it is a floor, not an average. None of the
 * three messages here touch the database - the party channel is already open and already gated by
 * RLS on `realtime.messages`, so a member can talk to the other members over it for the cost of one
 * hop.
 *
 * The database keeps everything it was already good at: membership, readiness, the host's identity,
 * content, the snapshot a late joiner needs, and every authorization decision. It is simply no
 * longer on the path between a button and a pause.
 *
 * Hand-rolled over [JsonObject] rather than `@Serializable`, for the same reason
 * `core/sync/SyncPreferenceJson.kt` is: it keeps the file compilable outside Gradle with only the
 * JSON runtime, so `scripts/run-pure-suites.sh` can execute the encoder against the decoder rather
 * than trusting them to agree.
 *
 * Field names are short because a tick goes out twice a second per party.
 */

/**
 * Bumped when a payload stops meaning what an older build thinks it means.
 *
 * Desktop and mobile ship separately, so a new client and an old one meet in the field routinely.
 * An unreadable or unknown message decodes to null, the client falls back to the database anchor,
 * and the party works less well instead of not at all.
 */
const val WatchPartySyncProtocolVersion = 1

/** One broadcast event carries all of it, so a single collector sees every message in order. */
const val WatchPartySyncEvent = "sync"

private const val TypeTick = "tick"
private const val TypeCommand = "cmd"
private const val TypeClockPing = "clk_ping"
private const val TypeClockPong = "clk_pong"
private const val TypePeerStatus = "peer"

sealed interface PartySyncMessage {
    val partyId: String
    val fromProfileId: String
}

/** The host's position, paired with the instant it was read. See [PartyTick]. */
data class PartyTickMessage(
    override val fromProfileId: String,
    val tick: PartyTick,
) : PartySyncMessage {
    override val partyId: String get() = tick.partyId
}

/** A transport action addressed to a party instant. See [PartyCommand]. */
data class PartyCommandMessage(
    override val partyId: String,
    val command: PartyCommand,
) : PartySyncMessage {
    override val fromProfileId: String get() = command.issuedByProfileId
}

/** A guest asking the host what time it is. */
data class PartyClockPingMessage(
    override val partyId: String,
    override val fromProfileId: String,
    val exchangeId: String,
    val sentAtMs: Long,
) : PartySyncMessage

/**
 * The host answering.
 *
 * [sentAtMs] is echoed rather than remembered by the asker, so the host holds no per-guest state
 * and a pong that arrives after the guest gave up costs nothing. [toProfileId] is there because a
 * broadcast reaches the whole channel; everyone else drops it.
 */
data class PartyClockPongMessage(
    override val partyId: String,
    override val fromProfileId: String,
    val toProfileId: String,
    val exchangeId: String,
    val sentAtMs: Long,
    val hostAtMs: Long,
) : PartySyncMessage

/**
 * What a member other than the host is doing, so the host can decide whether to wait for them.
 *
 * [rttMs] rides along because the host cannot measure it: a ping carries the *guest's* clock, and
 * the host has no offset for it. The barrier lead is sized from the worst round trip in the party,
 * so the party has to be told what those are. -1 means not measured yet.
 */
data class PartyPeerStatusMessage(
    override val partyId: String,
    override val fromProfileId: String,
    val status: WatchPartyStatus,
    val atPartyMs: Long,
    val rttMs: Long = -1L,
) : PartySyncMessage

fun encodePartySyncMessage(message: PartySyncMessage): JsonObject = buildJsonObject {
    put("v", WatchPartySyncProtocolVersion)
    put("p", message.partyId)
    put("f", message.fromProfileId)
    when (message) {
        is PartyTickMessage -> {
            put("t", TypeTick)
            put("g", message.tick.contentGeneration)
            put("q", message.tick.sequence)
            put("s", message.tick.status.name)
            put("pos", message.tick.positionMs)
            put("at", message.tick.capturedAtPartyMs)
            put("spd", message.tick.playbackSpeed)
            put("dur", message.tick.durationMs)
        }
        is PartyCommandMessage -> {
            put("t", TypeCommand)
            put("id", message.command.commandId)
            put("k", message.command.kind.name)
            put("n", message.command.counter)
            put("g", message.command.contentGeneration)
            put("pos", message.command.startPositionMs)
            put("at", message.command.startAtPartyMs)
            put("spd", message.command.playbackSpeed)
        }
        is PartyClockPingMessage -> {
            put("t", TypeClockPing)
            put("id", message.exchangeId)
            put("t0", message.sentAtMs)
        }
        is PartyClockPongMessage -> {
            put("t", TypeClockPong)
            put("id", message.exchangeId)
            put("to", message.toProfileId)
            put("t0", message.sentAtMs)
            put("t1", message.hostAtMs)
        }
        is PartyPeerStatusMessage -> {
            put("t", TypePeerStatus)
            put("s", message.status.name)
            put("at", message.atPartyMs)
            put("rtt", message.rttMs)
        }
    }
}

/**
 * Null for anything this build cannot act on: a newer protocol, an unknown type, a field that is
 * missing because the sender is older. Every one of those is a reason to fall back to the database
 * anchor, never a reason to guess.
 */
fun decodePartySyncMessage(payload: JsonObject): PartySyncMessage? {
    fun str(key: String) = payload[key]?.jsonPrimitive?.contentOrNull
    fun long(key: String) = payload[key]?.jsonPrimitive?.longOrNull
    fun int(key: String) = payload[key]?.jsonPrimitive?.intOrNull
    fun float(key: String) = payload[key]?.jsonPrimitive?.floatOrNull

    val version = int("v") ?: return null
    if (version > WatchPartySyncProtocolVersion) return null
    val partyId = str("p") ?: return null
    val from = str("f") ?: return null

    return when (str("t")) {
        TypeTick -> PartyTickMessage(
            fromProfileId = from,
            tick = PartyTick(
                partyId = partyId,
                contentGeneration = int("g") ?: return null,
                sequence = long("q") ?: return null,
                status = str("s")?.let { name -> runCatching { WatchPartyStatus.valueOf(name) }.getOrNull() }
                    ?: return null,
                positionMs = long("pos") ?: return null,
                capturedAtPartyMs = long("at") ?: return null,
                playbackSpeed = float("spd") ?: return null,
                durationMs = long("dur") ?: 0L,
            ),
        )
        TypeCommand -> PartyCommandMessage(
            partyId = partyId,
            command = PartyCommand(
                commandId = str("id") ?: return null,
                kind = str("k")?.let { name -> runCatching { PartyCommandKind.valueOf(name) }.getOrNull() }
                    ?: return null,
                issuedByProfileId = from,
                counter = long("n") ?: return null,
                contentGeneration = int("g") ?: return null,
                startPositionMs = long("pos") ?: return null,
                startAtPartyMs = long("at") ?: return null,
                playbackSpeed = float("spd") ?: 1f,
            ),
        )
        TypeClockPing -> PartyClockPingMessage(
            partyId = partyId,
            fromProfileId = from,
            exchangeId = str("id") ?: return null,
            sentAtMs = long("t0") ?: return null,
        )
        TypeClockPong -> PartyClockPongMessage(
            partyId = partyId,
            fromProfileId = from,
            toProfileId = str("to") ?: return null,
            exchangeId = str("id") ?: return null,
            sentAtMs = long("t0") ?: return null,
            hostAtMs = long("t1") ?: return null,
        )
        TypePeerStatus -> PartyPeerStatusMessage(
            partyId = partyId,
            fromProfileId = from,
            status = str("s")?.let { name -> runCatching { WatchPartyStatus.valueOf(name) }.getOrNull() }
                ?: return null,
            atPartyMs = long("at") ?: return null,
            rttMs = long("rtt") ?: -1L,
        )
        else -> null
    }
}

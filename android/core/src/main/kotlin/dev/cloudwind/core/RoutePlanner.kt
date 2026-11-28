package dev.cloudwind.core

/** Where a file may go. Camera photos are cloud-OK; sensitive folders are Home-only. */
enum class Policy { CLOUD_OK, HOME_ONLY }

/**
 * Every folder defaults to cloud-OK (Home Wi-Fi Only = off) unless the user explicitly marks it Home-only.
 * Folders are matched exactly, ignoring a trailing slash.
 */
fun folderPolicy(relPath: String, homeOnly: Set<String>, cloudOk: Set<String>): Policy {
    val p = relPath.trim().trimEnd('/')
    return when {
        p in homeOnly -> Policy.HOME_ONLY
        p in cloudOk -> Policy.CLOUD_OK
        else -> Policy.CLOUD_OK
    }
}

data class FileInfo(val size: Long, val policy: Policy, val inCloud: Boolean = false)

/**
 * User-configurable gates. They only restrain *big* files; small ones always go (within the cloud's own limits).
 * The battery gate means "charging, or at least [batteryMinPct]% battery". Direct sends obey the battery gate
 * but never the Wi-Fi gate (they are on Wi-Fi by definition).
 */
data class Gates(
    val bigBytes: Long = 50L shl 20,
    val wifiOnlyForBig: Boolean = true,
    val batteryGate: Boolean = true,
    val batteryMinPct: Int = 40,
) {
    /** Keeps a hand-edited or old setting inside the supported 30..90 range. */
    fun normalized() = copy(batteryMinPct = batteryMinPct.coerceIn(MIN_BATTERY, MAX_BATTERY), bigBytes = bigBytes.coerceAtLeast(1))

    companion object {
        const val MIN_BATTERY = 30
        const val MAX_BATTERY = 90
    }
}

data class Conditions(
    /** The PC answered a pinned handshake on the current network. */
    val lanReachable: Boolean,
    val internet: Boolean,
    val onWifi: Boolean,
    val charging: Boolean,
    val batteryPct: Int,
)

enum class Wait { HOME_NETWORK, WIFI, BATTERY, NETWORK, INBOX_FULL }

sealed interface Decision {
    /** Send straight to the PC. */
    data object Lan : Decision
    /** Encrypt and upload to the cloud inbox. */
    data object Cloud : Decision
    /** Already in the cloud; the PC pulls it. Nothing to do from the phone. */
    data object InCloud : Decision
    data class Wait(val why: dev.cloudwind.core.Wait) : Decision
}

/** One route per file, chosen just before it starts. Pure, so every rule is unit-tested. */
object RoutePlanner {
    fun isBig(f: FileInfo, g: Gates) = f.size >= g.bigBytes

    fun batteryOk(f: FileInfo, c: Conditions, g: Gates): Boolean =
        !(isBig(f, g) && g.batteryGate) || c.charging || c.batteryPct >= g.batteryMinPct

    fun decide(f: FileInfo, c: Conditions, gates: Gates, cloud: Limits?): Decision {
        val g = gates.normalized()
        if (c.lanReachable) return if (batteryOk(f, c, g)) Decision.Lan else Decision.Wait(Wait.BATTERY)
        if (f.policy == Policy.HOME_ONLY) return Decision.Wait(Wait.HOME_NETWORK)
        if (f.inCloud) return Decision.InCloud
        if (!c.internet) return Decision.Wait(Wait.NETWORK)
        if (cloud != null) {
            if (f.size > cloud.max_object_bytes) return Decision.Wait(Wait.HOME_NETWORK) // too big for the cloud: only the LAN can take it
            if (f.size > cloud.free_bytes) return Decision.Wait(Wait.INBOX_FULL)
        }
        if (isBig(f, g)) {
            if (g.wifiOnlyForBig && !c.onWifi) return Decision.Wait(Wait.WIFI)
            if (!batteryOk(f, c, g)) return Decision.Wait(Wait.BATTERY)
        }
        return Decision.Cloud
    }

    /**
     * A cloud upload is under way and the PC just became reachable: finish it only if little is left,
     * otherwise cancel it and go direct (the two routes can't share partial progress).
     */
    fun finishCloudInstead(remainingBytes: Long, threshold: Long = 64L shl 20) = remainingBytes in 0..threshold
}

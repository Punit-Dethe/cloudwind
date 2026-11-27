package dev.cloudwind.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import dev.cloudwind.core.Conditions

/** Reads what the route planner needs to know about the phone right now. */
object Device {
    /** Wi-Fi or Ethernet: somewhere the PC could be. Cellular can't be "the same network". */
    fun onLocalNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun conditions(ctx: Context, lanReachable: Boolean): Conditions {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        // "Wi-Fi" here means a local connection that isn't metered, so a phone hotspot doesn't count.
        val unmeteredLocal = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).let { if (it in 0..100) it else 100 }
        return Conditions(lanReachable, internet, unmeteredLocal, bm.isCharging, pct)
    }
}

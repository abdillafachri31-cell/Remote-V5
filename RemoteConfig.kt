package id.arunika.remote

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections

object RemoteConfig {
    private const val NAME = "arunika_private"
    private const val KEY = "access_key"
    private const val ACTIVE = "active"
    private fun prefs(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    @Synchronized fun token(context: Context): String {
        prefs(context).getString(KEY, null)?.let { if (it.matches(Regex("[a-f0-9]{32}"))) return it }
        return regenerate(context)
    }

    @Synchronized fun regenerate(context: Context): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val value = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        prefs(context).edit().putString(KEY, value).apply()
        return value
    }

    fun enabled(c: Context) = prefs(c).getBoolean(ACTIVE, false)
    fun setEnabled(c: Context, value: Boolean) = prefs(c).edit().putBoolean(ACTIVE, value).apply()

    // Tailscale assigns addresses in 100.64.0.0/10; bind ONLY there, never public Wi-Fi or cellular.
    fun tailscaleIp(): Inet4Address? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
                .filter { it.isUp && !it.isLoopback && (it.name.startsWith("tun") || it.name.startsWith("tailscale") || it.name.startsWith("wg")) }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull {
                    val addr = it.address
                    (addr[0].toInt() and 255) == 100 && ((addr[1].toInt() and 255) in 64..127)
                }
        } catch (_: Exception) { null }
    }
}

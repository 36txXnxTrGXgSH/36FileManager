package com.filemanager36.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Détection de l'adresse du téléphone sur le réseau local.
 *
 * On n'utilise pas `WifiManager.connectionInfo.ipAddress` : il est obsolète depuis
 * Android 12, renvoie 0 sur un partage de connexion, et ne voit pas les interfaces
 * Ethernet USB ni les points d'accès mobiles.
 */
object NetworkUtil {

    /**
     * Familles d'interfaces, dans l'ordre de préférence.
     *
     * `MOBILE` et `TUNNEL` existent pour être **écartées**, pas classées en dernier.
     * Jusqu'à la 2.0.2 elles étaient simplement reléguées au rang 9 : Wi-Fi coupé et
     * données mobiles actives, `localAddress()` renvoyait l'adresse de l'opérateur et le
     * serveur de partage s'y liait — exactement ce que la documentation de
     * `HttpShareServer.start` interdit. Beaucoup d'opérateurs attribuent une IPv4 en
     * 10.x, que `isSiteLocalAddress` accepte : le second garde-fou ne rattrapait rien.
     */
    private enum class Nic(val rank: Int) { WIFI(0), HOTSPOT(1), WIRED(2), OTHER(5), MOBILE(9), TUNNEL(9) }

    private fun classify(name: String): Nic {
        val n = name.lowercase()
        return when {
            n.startsWith("wlan") -> Nic.WIFI
            n.startsWith("ap") || n.startsWith("swlan") -> Nic.HOTSPOT
            n.startsWith("eth") || n.startsWith("usb") || n.startsWith("rndis") -> Nic.WIRED
            // rmnet / ccmni / pdp : données mobiles selon les fabricants.
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
                n.startsWith("ppp") || n.startsWith("clat") -> Nic.MOBILE
            n.startsWith("tun") || n.startsWith("tap") || n.startsWith("ipsec") ||
                n.startsWith("wg") -> Nic.TUNNEL
            else -> Nic.OTHER
        }
    }

    /** Interfaces sur lesquelles il est acceptable d'ouvrir un serveur. */
    private fun isBindable(kind: Nic) = kind != Nic.MOBILE && kind != Nic.TUNNEL

    /**
     * Adresse IPv4 sur laquelle **lier le serveur de partage**, ou null si l'appareil
     * n'est sur aucun réseau local. Les interfaces de données mobiles et de tunnel
     * (VPN, Tailscale…) sont exclues : sur les premières le téléphone serait exposé
     * depuis Internet, sur les secondes l'adresse n'est de toute façon pas joignable
     * depuis l'ordinateur du même salon.
     */
    fun localAddress(): String? = addresses(bindableOnly = true).firstOrNull()

    /** Toutes les adresses IPv4 locales, à titre d'information. */
    fun candidates(): List<String> = addresses(bindableOnly = false)

    private fun addresses(bindableOnly: Boolean): List<String> = runCatching {
        val found = mutableListOf<Pair<Int, String>>()
        for (nif in NetworkInterface.getNetworkInterfaces().toList()) {
            if (!nif.isUp || nif.isLoopback) continue
            val kind = classify(nif.name)
            if (bindableOnly && !isBindable(kind)) continue
            for (addr in nif.inetAddresses.toList()) {
                if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                // Une adresse liée doit aussi être privée : un opérateur qui exposerait
                // une IPv4 publique sur une interface au nom inattendu ne doit pas
                // passer par la seule vertu de son nom.
                if (bindableOnly && !addr.isSiteLocalAddress && !addr.isLinkLocalAddress) continue
                found += kind.rank to addr.hostAddress.orEmpty()
            }
        }
        found.filter { it.second.isNotBlank() }.sortedBy { it.first }.map { it.second }
    }.getOrDefault(emptyList())

    /** Adresse de diffusion associée à une adresse locale, pour la découverte UDP. */
    fun broadcastAddresses(): List<InetAddress> = runCatching {
        val out = mutableListOf<InetAddress>()
        for (nif in NetworkInterface.getNetworkInterfaces().toList()) {
            if (!nif.isUp || nif.isLoopback) continue
            if (!isBindable(classify(nif.name))) continue
            for (ia in nif.interfaceAddresses) {
                ia.broadcast?.let { out += it }
            }
        }
        // 255.255.255.255 en repli : certains routeurs ne relaient pas la diffusion
        // dirigée, mais laissent passer la diffusion limitée.
        out + InetAddress.getByName("255.255.255.255")
    }.getOrDefault(listOf(InetAddress.getByName("255.255.255.255")))

    /** L'appareil est-il sur un réseau Wi-Fi ou filaire (et non sur les données mobiles) ? */
    fun onLocalNetwork(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    /** Nom lisible de l'appareil, affiché à l'autre bout. */
    fun deviceName(): String {
        val manufacturer = android.os.Build.MANUFACTURER.orEmpty()
        val model = android.os.Build.MODEL.orEmpty()
        val name = if (model.startsWith(manufacturer, ignoreCase = true)) model
        else "$manufacturer $model"
        return name.trim().ifBlank { "Appareil Android" }
            .replaceFirstChar { it.uppercase() }
    }
}

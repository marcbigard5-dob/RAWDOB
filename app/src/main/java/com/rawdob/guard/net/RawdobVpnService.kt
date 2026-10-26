package com.rawdob.guard.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.rawdob.guard.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local DNS-filter VPN. It routes only DNS queries addressed to the virtual resolver
 * into the TUN. It does not tunnel arbitrary IP traffic and does not inspect HTTPS content.
 */
class RawdobVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProtection()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        if (!running.get()) startProtection()
        return START_STICKY
    }

    private fun startProtection() {
        val descriptor = Builder()
            .setSession("RAWDOB DNS Protection")
            .setMtu(1500)
            .addAddress(VIRTUAL_INTERFACE, 32)
            .addDnsServer(VIRTUAL_DNS)
            .addRoute(VIRTUAL_DNS, 32)
            .setBlocking(true)
            .establish()

        if (descriptor == null) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
            stopSelf()
            return
        }

        tun = descriptor
        running.set(true)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply()
        worker = Thread({ packetLoop(descriptor) }, "RAWDOB-DNS-TUN").apply {
            isDaemon = true
            start()
        }
    }

    private fun packetLoop(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(32767)
        while (running.get()) {
            try {
                val length = input.read(buffer)
                if (length <= 0) continue
                val packet = buffer.copyOf(length)
                val reply = processDnsPacket(packet) ?: continue
                output.write(reply)
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_QUERIES, getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_QUERIES, 0L) + 1L)
                    .apply()
            } catch (_: Exception) {
                if (running.get()) {
                    // Do not crash the process on a malformed packet; continue monitoring.
                }
            }
        }
    }

    private fun processDnsPacket(packet: ByteArray): ByteArray? {
        if (packet.size < 28 || (packet[0].toInt() ushr 4 and 0xF) != 4) return null
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        if (ipHeaderLength < 20 || packet.size < ipHeaderLength + 8) return null
        if ((packet[9].toInt() and 0xFF) != 17) return null // UDP only
        val destIp = packet.copyOfRange(16, 20).joinToString(".") { (it.toInt() and 0xFF).toString() }
        if (destIp != VIRTUAL_DNS) return null
        val udpOffset = ipHeaderLength
        val destPort = u16(packet, udpOffset + 2)
        if (destPort != 53) return null
        val udpLength = u16(packet, udpOffset + 4)
        if (udpLength < 8 || udpOffset + udpLength > packet.size) return null
        val dnsStart = udpOffset + 8
        val dnsQuery = packet.copyOfRange(dnsStart, udpOffset + udpLength)
        val domain = readQuestionName(dnsQuery) ?: return null
        val blocked = isBlocked(domain)
        val dnsReply = if (blocked) blockedReply(dnsQuery) else upstreamQuery(dnsQuery) ?: return null
        return buildResponsePacket(packet, ipHeaderLength, dnsReply)
    }

    private fun upstreamQuery(query: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                if (!protect(socket)) return null
                socket.soTimeout = 2500
                val server = InetAddress.getByName(UPSTREAM_DNS)
                socket.send(DatagramPacket(query, query.size, server, 53))
                val response = ByteArray(4096)
                val reply = DatagramPacket(response, response.size)
                socket.receive(reply)
                response.copyOf(reply.length)
            }
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun isBlocked(domain: String): Boolean {
        val custom = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_BLOCKLIST, "").orEmpty()
            .lines().map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val builtIn = setOf("doubleclick.net", "googlesyndication.com", "googleadservices.com")
        return (builtIn + custom).any { domain == it || domain.endsWith(".$it") }
    }

    private fun blockedReply(query: ByteArray): ByteArray {
        val reply = query.copyOf()
        if (reply.size >= 12) {
            val flags = u16(reply, 2)
            put16(reply, 2, (flags or 0x8000 or 0x0080) and 0xFFF0 or 0x0003)
            put16(reply, 6, 0) // no answer records for NXDOMAIN
            put16(reply, 8, 0)
            put16(reply, 10, 0)
        }
        return reply
    }

    private fun buildResponsePacket(original: ByteArray, ipLen: Int, dns: ByteArray): ByteArray {
        val udpLen = 8 + dns.size
        val out = ByteArray(ipLen + udpLen)
        original.copyInto(out, 0, 0, ipLen + 8)
        // Swap IPv4 addresses.
        for (i in 0..3) {
            out[12 + i] = original[16 + i]
            out[16 + i] = original[12 + i]
        }
        val oldSrcPort = original[ipLen]
        val oldSrcPort2 = original[ipLen + 1]
        out[ipLen] = original[ipLen + 2]
        out[ipLen + 1] = original[ipLen + 3]
        out[ipLen + 2] = oldSrcPort
        out[ipLen + 3] = oldSrcPort2
        put16(out, 2, out.size)
        put16(out, ipLen + 4, udpLen)
        dns.copyInto(out, ipLen + 8)
        put16(out, 10, 0)
        put16(out, 10, checksum(out, 0, ipLen))
        put16(out, ipLen + 6, 0)
        put16(out, ipLen + 6, udpChecksum(out, ipLen, udpLen))
        return out
    }

    private fun readQuestionName(dns: ByteArray): String? {
        if (dns.size < 17 || u16(dns, 4) < 1) return null
        var p = 12
        val labels = mutableListOf<String>()
        while (p < dns.size) {
            val size = dns[p++].toInt() and 0xFF
            if (size == 0) break
            if ((size and 0xC0) != 0 || size > 63 || p + size > dns.size) return null
            labels += String(dns, p, size, Charsets.US_ASCII).lowercase()
            p += size
        }
        if (labels.isEmpty() || p + 4 > dns.size) return null
        return labels.joinToString(".")
    }

    private fun udpChecksum(packet: ByteArray, udpStart: Int, udpLength: Int): Int {
        val pseudo = ByteArray(12 + udpLength)
        packet.copyInto(pseudo, 0, 12, 20)
        pseudo[9] = 17
        put16(pseudo, 10, udpLength)
        packet.copyInto(pseudo, 12, udpStart, udpStart + udpLength)
        return checksum(pseudo, 0, pseudo.size).let { if (it == 0) 0xFFFF else it }
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += (((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if (i < end) sum += ((bytes[i].toInt() and 0xFF) shl 8).toLong()
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun stopProtection() {
        running.set(false)
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopProtection()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val channelId = "rawdob_protection"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "RAWDOB protection", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("RAWDOB protection active")
            .setContentText("Local DNS filtering is running")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.rawdob.guard.STOP_VPN"
        const val PREFS = "rawdob_security"
        const val KEY_ACTIVE = "vpn_active"
        const val KEY_QUERIES = "dns_queries"
        const val KEY_BLOCKLIST = "custom_blocklist"
        private const val NOTIFICATION_ID = 7401
        private const val VIRTUAL_INTERFACE = "10.111.0.1"
        private const val VIRTUAL_DNS = "10.111.0.2"
        private const val UPSTREAM_DNS = "1.1.1.1"
    }
}

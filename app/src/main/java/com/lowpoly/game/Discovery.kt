package com.lowpoly.game

import java.net.*

/** LAN discovery: the host broadcasts a small UDP beacon, joiners listen for it. */
object Discovery {
    const val PORT = 48999

    data class Server(val ip: String, val name: String, val players: Int, val seen: Long)

    class Beacon(name: String, private val players: () -> Int) : Thread() {
        private val nm = name.replace("|", " ").take(24)
        @Volatile private var go = true
        fun end() { go = false; interrupt() }
        override fun run() {
            val s = try { DatagramSocket().apply { broadcast = true } } catch (_: Exception) { return }
            try {
                while (go) {
                    val data = "LOWPOLY1|$nm|${players()}".toByteArray()
                    try {
                        for (ni in NetworkInterface.getNetworkInterfaces()) {
                            if (!ni.isUp || ni.isLoopback) continue
                            for (ia in ni.interfaceAddresses) {
                                val b = ia.broadcast ?: continue
                                try { s.send(DatagramPacket(data, data.size, b, PORT)) } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                    try { sleep(1500) } catch (_: InterruptedException) {}
                }
            } finally { s.close() }
        }
    }

    class Listener(private val onChange: (List<Server>) -> Unit) : Thread() {
        @Volatile private var go = true
        private var sock: DatagramSocket? = null
        fun end() { go = false; sock?.close() }
        override fun run() {
            val found = LinkedHashMap<String, Server>()
            val s = try {
                DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(PORT)); soTimeout = 1000 }
            } catch (_: Exception) { return }
            sock = s
            val buf = ByteArray(256)
            var last = ""
            try {
                while (go) {
                    try {
                        val p = DatagramPacket(buf, buf.size); s.receive(p)
                        val parts = String(p.data, 0, p.length).split("|")
                        if (parts.size >= 3 && parts[0] == "LOWPOLY1") {
                            val ip = p.address.hostAddress ?: continue
                            found[ip] = Server(ip, parts[1], parts[2].toIntOrNull() ?: 0, System.currentTimeMillis())
                        }
                    } catch (_: SocketTimeoutException) {}
                    val now = System.currentTimeMillis()
                    found.values.removeAll { now - it.seen > 5000 }
                    val list = found.values.toList()
                    val key = list.joinToString { it.ip + it.name + it.players }
                    if (key != last) { last = key; onChange(list) }
                }
            } catch (_: Exception) {} finally { s.close() }
        }
    }
}

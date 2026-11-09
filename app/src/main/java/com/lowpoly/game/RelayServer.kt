package com.lowpoly.game

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Serves the game (index.html) and a Kotlin port of mp.php on the same port,
 * so the page's relative fetch('mp.php') keeps working unchanged.
 */
class RelayServer(private val ctx: Context, port: Int = PORT) : NanoHTTPD(port) {

    companion object { const val PORT = 8080 }

    private class P(var n: String, var x: Double, var y: Double, var z: Double, var r: Double, var q: Double,
                    var c: Double, var g: Int, var tm: Int, var rd: Int, var t: Double)
    private class M(val from: String, val type: String, val data: String, val t: Double)

    private val lock = Any()
    private val players = LinkedHashMap<String, P>()
    private val msgs = HashMap<String, MutableList<M>>()
    private var wRain = 0; private var wTod = .25; private var wT = 0.0; private var wV = 0

    fun playerCount(): Int = synchronized(lock) { players.size }

    private val html: ByteArray by lazy { ctx.assets.open("index.html").use { it.readBytes() } }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri.endsWith("mp.php") -> relay(session)
            uri == "/" || uri == "/index.html" ->
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html.inputStream(), html.size.toLong())
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun json(o: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", o.toString()).also {
            it.addHeader("Cache-Control", "no-store")
        }

    private fun clean(s: String) = s.filter { it.isLetterOrDigit() }
    private fun r2(d: Double) = (d * 100).roundToInt() / 100.0
    private fun r3(d: Double) = (d * 1000).roundToInt() / 1000.0

    private fun relay(session: IHTTPSession): Response {
        var body = ""
        if (session.method == Method.POST) {
            val files = HashMap<String, String>()
            try { session.parseBody(files) } catch (_: Exception) {}
            body = files["postData"] ?: ""
        }
        val inp = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val id = clean(inp.optString("id", ""))
        val now = System.currentTimeMillis() / 1000.0
        if (id.length < 6 || id.length > 24) return json(JSONObject().put("p", JSONObject()).put("now", now))

        synchronized(lock) {
            players.entries.removeAll { now - it.value.t > 8 }
            val it = msgs.entries.iterator()
            while (it.hasNext()) {
                val e = it.next(); e.value.removeAll { m -> now - m.t >= 20 }
                if (e.value.isEmpty()) it.remove()
            }
            var mine: List<M> = emptyList()
            if (inp.optBoolean("leave", false) || inp.optInt("leave", 0) != 0) {
                players.remove(id); msgs.remove(id)
            } else if (players.containsKey(id) || players.size < 24) {
                val nm = inp.optString("n", "Player").filter { c -> c.isLetterOrDigit() || c == ' ' || c == '_' || c == '-' }
                players[id] = P(
                    nm.take(14).ifEmpty { "Player" },
                    r2(inp.optDouble("x", 0.0)), r2(inp.optDouble("y", 0.0)), r2(inp.optDouble("z", 0.0)),
                    r3(inp.optDouble("r", 0.0)), r3(inp.optDouble("q", 0.0)), r2(inp.optDouble("c", 0.0)),
                    inp.optInt("g", 0).coerceIn(0, 9), inp.optInt("tm", 0).coerceIn(0, 2),
                    inp.optInt("rd", 0).coerceIn(0, 2), now
                )
                inp.optJSONObject("w")?.let { w ->
                    if (w.has("tod")) {
                        wRain = if (w.optBoolean("rain", false) || w.optInt("rain", 0) != 0) 1 else 0
                        wTod = abs(w.optDouble("tod", 0.0)) % 1.0
                        wT = now; wV++
                    }
                }
                val sg: JSONArray? = inp.optJSONArray("sig")
                if (sg != null) for (i in 0 until minOf(sg.length(), 30)) {
                    val m = sg.optJSONObject(i) ?: continue
                    val to = clean(m.optString("to", "")); val d = m.optString("data", ""); val ty = m.optString("type", "")
                    if (!players.containsKey(to) || d.length > 8000 || ty !in listOf("offer", "answer", "ice", "ev") ||
                        (msgs[to]?.size ?: 0) > 80) continue
                    msgs.getOrPut(to) { mutableListOf() }.add(M(id, ty, d, now))
                }
                mine = msgs.remove(id) ?: emptyList()
            }

            val po = JSONObject()
            for ((k, p) in players) if (k != id) po.put(k, JSONObject()
                .put("n", p.n).put("x", p.x).put("y", p.y).put("z", p.z).put("r", p.r).put("q", p.q)
                .put("c", p.c).put("g", p.g).put("tm", p.tm).put("rd", p.rd).put("t", p.t))
            val mo = JSONArray()
            mine.forEach { mo.put(JSONObject().put("from", it.from).put("type", it.type).put("data", it.data).put("t", it.t)) }
            return json(JSONObject().put("p", po)
                .put("w", JSONObject().put("rain", wRain).put("tod", wTod).put("t", wT).put("v", wV))
                .put("m", mo).put("now", now))
        }
    }
}

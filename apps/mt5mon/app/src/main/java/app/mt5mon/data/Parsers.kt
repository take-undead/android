package app.mt5mon.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ParseError(message: String) : Exception(message)

/** JSON の読み取り（PicoCalc の status.c / attack.c / nippo.c / notice.c / vpschat main.c と同じ項目） */
object Parsers {
    private val fmtMin = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** "YYYY-MM-DD HH:MM" → 1970-01-01 0:00 からの通しの分（JST のまま）。読めなければ -1 */
    fun jstAbsMinutes(s: String): Long {
        if (s.length < 16) return -1
        return try {
            val t = LocalDateTime.parse(s.substring(0, 16), fmtMin)
            t.toEpochSecond(java.time.ZoneOffset.UTC) / 60
        } catch (e: Exception) {
            -1
        }
    }

    /** 数値の文字列での小数点以下の桁数（価格の桁を知るため） */
    private fun decimals(o: JSONObject, key: String): Int {
        val v = o.opt(key) ?: return 0
        val s = v.toString()
        val dot = s.indexOf('.')
        return if (dot < 0) 0 else s.length - dot - 1
    }

    private fun JSONObject.str(key: String, def: String = ""): String = if (has(key) && !isNull(key)) optString(key, def) else def
    private fun JSONObject.num(key: String, def: Double = 0.0): Double = if (has(key) && !isNull(key)) optDouble(key, def) else def
    private fun JSONObject.bool(key: String, def: Boolean = false): Boolean = if (has(key) && !isNull(key)) optBoolean(key, def) else def
    private fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)
    private fun JSONObject.arr(key: String): JSONArray? = optJSONArray(key)

    private fun root(body: String, what: String): JSONObject = try {
        JSONObject(body)
    } catch (e: JSONException) {
        throw ParseError("$what が読めない（${body.length} 文字）")
    }

    /** 確定足の列（t0_jst・dt・o/h/l/c）を Bar に */
    private fun bars(o: JSONObject, maxBars: Int): List<Bar> {
        val t0 = jstAbsMinutes(o.str("t0_jst"))
        val dt = o.arr("dt") ?: return emptyList()
        val oa = o.arr("o") ?: return emptyList()
        val ha = o.arr("h") ?: return emptyList()
        val la = o.arr("l") ?: return emptyList()
        val ca = o.arr("c") ?: return emptyList()
        var nb = minOf(dt.length(), oa.length(), ha.length(), la.length(), ca.length())
        val skip = if (nb > maxBars) nb - maxBars else 0
        val out = ArrayList<Bar>(nb - skip)
        for (i in skip until nb) {
            out.add(Bar(oa.optDouble(i).toFloat(), ha.optDouble(i).toFloat(), la.optDouble(i).toFloat(), ca.optDouble(i).toFloat(), t0 + dt.optInt(i)))
        }
        return out
    }

    fun status(body: String): Status {
        val j = root(body, "JSON")
        val v = j.optInt("v", 0)
        if (v != 1) throw ParseError("未対応の版 v=$v")
        val tick = j.obj("tick") ?: JSONObject()
        var digits = decimals(tick, "bid")
        if (digits < 3) digits = 3 // 157.800 が 157.8 で届いても 3 桁で出す
        val acc = j.obj("account") ?: JSONObject()
        val positions = ArrayList<Position>()
        j.arr("positions")?.let { a ->
            for (i in 0 until a.length()) {
                val p = a.optJSONObject(i) ?: continue
                positions.add(
                    Position(
                        ticket = p.optLong("ticket", 0),
                        symbol = p.str("symbol"),
                        ea = p.str("ea", "?"),
                        buy = p.str("type") == "buy",
                        lots = p.num("lots"),
                        open = p.num("open"),
                        digits = decimals(p, "open"),
                        sl = p.num("sl"),
                        tp = p.num("tp"),
                        profit = p.num("profit"),
                        openJst = p.str("open_jst"),
                    )
                )
            }
        }
        val eas = ArrayList<Ea>()
        j.arr("eas")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                eas.add(Ea(e.str("name", "?"), e.bool("alive")))
            }
        }
        var m1 = emptyList<Bar>()
        var cur: Bar? = null
        j.obj("m1")?.let { m ->
            m1 = bars(m, 240)
            m.obj("cur")?.let { c ->
                val b = Bar(c.num("o").toFloat(), c.num("h").toFloat(), c.num("l").toFloat(), c.num("c").toFloat(), jstAbsMinutes(c.str("t_jst")))
                // ティックがまだ無い足は 0 で届く
                if (b.o > 0 && b.h > 0 && b.l > 0 && b.c > 0 && b.min >= 0) cur = b
            }
        }
        return Status(
            symbol = j.str("symbol", "?"),
            genJst = j.str("gen_jst"),
            connected = j.bool("connected"),
            stale = j.bool("stale", true),
            digits = digits,
            bid = tick.num("bid"),
            ask = tick.num("ask"),
            spreadPips = tick.num("spread_pips"),
            tickJst = tick.str("time_jst"),
            currency = acc.str("currency"),
            balance = acc.num("balance"),
            equity = acc.num("equity"),
            profit = acc.num("profit"),
            margin = acc.num("margin"),
            marginLevel = acc.num("margin_level"),
            positions = positions,
            eas = eas,
            m1 = m1,
            cur = cur,
        )
    }

    fun hist(body: String): Hist {
        val j = root(body, "bars の JSON")
        val tf = j.optInt("tf", 0)
        if (tf <= 0 || jstAbsMinutes(j.str("t0_jst")) < 0) throw ParseError("bars の形が違う")
        return Hist(tf, bars(j, 160))
    }

    fun sup(body: String): Sup {
        val j = root(body, "sup.json")
        val a = j.obj("alive") ?: JSONObject()
        val c = j.obj("cmd") ?: JSONObject()
        val e = j.obj("ea") ?: JSONObject()
        val ch = e.obj("chg24h") ?: JSONObject()
        val decisions = ArrayList<Decision>()
        j.arr("decisions")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 5)) {
                val o = arr.optJSONObject(i) ?: continue
                // 数が文字列で来ることがあるので文字列として読んでから数にする（PicoCalc と同じ）
                val pUp = o.str("p_up")
                decisions.add(
                    Decision(
                        t = o.str("t"),
                        seq = o.str("seq", "0").toDoubleOrNull()?.toInt() ?: 0,
                        bias = o.str("bias", "0").toDoubleOrNull()?.toInt() ?: 0,
                        risk = o.str("risk", "0").toDoubleOrNull()?.toInt() ?: 0,
                        pUp = if (pUp.isEmpty()) -1.0 else pUp.toDoubleOrNull() ?: -1.0,
                        res = o.str("res"),
                        why = o.str("why"),
                    )
                )
            }
        }
        val latest = ArrayList<String>()
        j.arr("latest")?.let { arr -> for (i in 0 until minOf(arr.length(), 12)) latest.add(arr.optString(i, "")) }
        return Sup(
            updatedJst = j.str("updated_jst"),
            aliveT = a.str("t"), aliveStatus = a.str("status"), aliveNote = a.str("note"),
            seq = c.num("seq").toInt(), cmdT = c.str("t"), bias = c.num("bias").toInt(), risk = c.num("risk").toInt(),
            lotFactor = c.num("lot_factor", 1.0), pUp = c.num("p_up", -1.0),
            lineBuy = c.num("line_buy"), lineSell = c.num("line_sell"), buyStop = c.num("buy_stop"), sellStop = c.num("sell_stop"),
            outlook = c.str("outlook"), regime = c.str("regime"), reason = c.str("reason"),
            eaVer = e.str("ver"), eaT = e.str("t"), bid = e.num("bid"), pos = e.str("pos", "none"),
            posLots = e.num("pos_lots"), posPips = e.num("pos_pips"), posMin = e.num("pos_min").toInt(),
            gui = e.str("gui", "auto"), eaSeq = e.num("seq").toInt(), dir = e.num("dir").toInt(),
            todayDecision = e.bool("today_decision"), spread = e.num("spread"), offsetH = e.num("offset_h", 3.0).toInt(), err = e.str("err"),
            balance = e.num("balance"), equity = e.num("equity"), lastClose = e.str("last_close"), lastPips = e.num("last_pips"),
            chgUsdJpy = ch.num("USDJPY"), chgEurUsd = ch.num("EURUSD"), chgEurJpy = ch.num("EURJPY"), chgUsOil = ch.num("USOIL"),
            weekHigh = e.num("week_high"), weekLow = e.num("week_low"),
            decisions = decisions, latest = latest,
        )
    }

    private fun JSONArray.s(i: Int): String = if (i < length() && !isNull(i)) optString(i, "") else ""
    private fun JSONArray.n(i: Int): Double = if (i < length() && !isNull(i)) optDouble(i, 0.0) else 0.0

    fun attack(body: String): Attack {
        val j = root(body, "f2b.json")
        val jails = ArrayList<Jail>()
        j.arr("jails")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONArray(i) ?: continue
                jails.add(Jail(r.s(1), r.s(2), r.n(3).toInt(), r.n(4).toInt(), r.n(5).toInt(), r.n(6).toInt()))
            }
        }
        val top = ArrayList<TopIp>()
        j.arr("top")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONArray(i) ?: continue
                top.add(TopIp(r.s(0), r.n(1).toInt(), r.n(2).toInt(), r.s(3), r.s(4), r.s(5).firstOrNull() ?: ' ', r.s(6), r.s(7), r.s(8)))
            }
        }
        val perm = ArrayList<PermIp>()
        j.arr("perm")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONArray(i) ?: continue
                perm.add(PermIp(r.s(0), r.s(1), r.s(2), r.n(3).toLong(), r.s(4), r.s(5)))
            }
        }
        val recent = ArrayList<AttackEvent>()
        j.arr("recent")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONArray(i) ?: continue
                recent.add(AttackEvent(r.s(0), r.s(1), r.s(2), r.s(3), r.s(4)))
            }
        }
        return Attack(j.str("updated_jst"), j.str("since_jst"), j.optInt("nperm", 0), jails, top, perm, recent)
    }

    fun stats(body: String): Stats {
        val j = root(body, "stats.json")
        val countries = ArrayList<CountryStat>()
        j.arr("countries")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONArray(i) ?: continue
                countries.add(CountryStat(r.s(0), r.s(1), r.n(2).toInt(), r.n(3).toLong()))
            }
        }
        val nets = ArrayList<NetStat>()
        j.arr("nets")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONArray(i) ?: continue
                nets.add(NetStat(r.s(0), r.s(1), r.n(2).toInt(), r.n(3).toLong()))
            }
        }
        return Stats(j.str("made_jst"), j.str("from"), j.str("to"), j.optInt("ips", 0), j.optLong("fails", 0), j.optInt("ncountries", 0), countries, nets)
    }

    fun chat(body: String): ChatPage {
        val j = root(body, "chat.json")
        val msgs = ArrayList<ChatMsg>()
        j.arr("msgs")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONArray(i) ?: continue
                msgs.add(ChatMsg(r.n(0).toInt(), r.s(1), r.s(2), r.s(3)))
            }
        }
        return ChatPage(j.bool("busy"), j.optInt("busy_s", 0), j.optInt("last", 0), msgs)
    }

    fun notice(body: String): Notice {
        val j = root(body, "notice.json")
        return Notice(
            doneJst = j.str("done_jst"), alertJst = j.str("alert_jst"),
            spread = j.num("spread_pips"), thr = j.num("threshold_pips"),
            seqBefore = j.optInt("seq_before", 0), seqAfter = j.optInt("seq_after", 0),
            text = j.str("text"),
        )
    }

    fun postResult(body: String): PostResult {
        val j = root(body, "応答")
        val ok = j.bool("ok")
        return PostResult(ok, j.str("msg", if (ok) "済み" else "断られた"))
    }
}

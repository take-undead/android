package app.mt5mon.data

// VPS の status_server.py が返す JSON を読んだ結果。形の正は picoclac の AGENTS.md「mt5mon」と VPS の tools/vps/*.py

/** /status.json（v=1） */
data class Status(
    val symbol: String,
    val genJst: String,
    val connected: Boolean,
    val stale: Boolean,
    val digits: Int,
    val bid: Double,
    val ask: Double,
    val spreadPips: Double,
    val tickJst: String,
    val currency: String,
    val balance: Double,
    val equity: Double,
    val profit: Double,
    val margin: Double,
    val marginLevel: Double,
    val positions: List<Position>,
    val eas: List<Ea>,
    val m1: List<Bar>,        // 確定足（古い順）。min は 1970-01-01 0:00（JST）からの通しの分
    val cur: Bar?,            // 形成中の足（ティックが無ければ null）
)

data class Position(
    val ticket: Long,
    val symbol: String,
    val ea: String,
    val buy: Boolean,
    val lots: Double,
    val open: Double,
    val digits: Int,
    val sl: Double,
    val tp: Double,
    val profit: Double,
    val openJst: String,
)

data class Ea(val name: String, val alive: Boolean)

/** ローソク足 1 本。min は 1970-01-01 0:00 からの通しの分（JST のまま。% 1440 で 0 時からの分） */
data class Bar(val o: Float, val h: Float, val l: Float, val c: Float, val min: Long)

/** /bars.json?tf=&n=（VPS が貯めた M1 をまとめた足） */
data class Hist(val tf: Int, val bars: List<Bar>)

/** /sup.json（Claude 監督の判断・EA_One の状態） */
data class Sup(
    val updatedJst: String,
    val aliveT: String, val aliveStatus: String, val aliveNote: String,
    val seq: Int, val cmdT: String, val bias: Int, val risk: Int, val lotFactor: Double, val pUp: Double,
    val lineBuy: Double, val lineSell: Double, val buyStop: Double, val sellStop: Double,
    val outlook: String, val regime: String, val reason: String,
    val eaVer: String, val eaT: String, val bid: Double, val pos: String, val posLots: Double, val posPips: Double, val posMin: Int,
    val gui: String, val eaSeq: Int, val dir: Int, val todayDecision: Boolean, val spread: Double, val offsetH: Int, val err: String,
    val balance: Double, val equity: Double, val lastClose: String, val lastPips: Double,
    val chgUsdJpy: Double, val chgEurUsd: Double, val chgEurJpy: Double, val chgUsOil: Double,
    val weekHigh: Double, val weekLow: Double,
    val decisions: List<Decision>,
    val latest: List<String>,
)

data class Decision(val t: String, val seq: Int, val bias: Int, val risk: Int, val pUp: Double, val res: String, val why: String)

/** /f2b.json（攻撃の記録） */
data class Attack(
    val updatedJst: String,
    val since: String,
    val nperm: Int,
    val jails: List<Jail>,
    val top: List<TopIp>,
    val perm: List<PermIp>,
    val recent: List<AttackEvent>,
)

data class Jail(val label: String, val ports: String, val curBanned: Int, val totalBanned: Int, val totalFailed: Int, val curFailed: Int)
data class TopIp(val ip: String, val fails: Int, val bans: Int, val ports: String, val last: String, val state: Char, val until: String, val cc: String, val country: String)
data class PermIp(val ip: String, val t: String, val ports: String, val pkts: Long, val cc: String, val country: String)
data class AttackEvent(val t: String, val jail: String, val act: String, val ip: String, val cc: String)

/** /stats.json（国別の集計） */
data class Stats(
    val made: String, val from: String, val to: String,
    val ips: Int, val fails: Long, val ncountries: Int,
    val countries: List<CountryStat>,
    val nets: List<NetStat>,
)

data class CountryStat(val cc: String, val name: String, val ips: Int, val fails: Long)
data class NetStat(val net: String, val cc: String, val ips: Int, val fails: Long)

/** /chat.json */
data class ChatPage(val busy: Boolean, val busySec: Int, val last: Int, val msgs: List<ChatMsg>)
data class ChatMsg(val num: Int, val who: String, val t: String, val text: String) // who: me / ai / err

/**
 * /notice.json（スプレッド警報・値動き警報 → Claude 監督の調査結果）。
 * move = 値動き警報（type=move / kind=move_alert。EA_One v0.16、2026-10-14 に picoclac の notice.c に追加されたものと同じ読み方）。
 * move のときは VPS が値動きの pips を spread_pips にも入れる。movePips は窓の高値−安値、moveSec は窓の秒、moveDir は向き（+1 上げ / -1 下げ / 0 不明）
 */
data class Notice(
    val doneJst: String, val alertJst: String, val spread: Double, val thr: Double,
    val seqBefore: Int, val seqAfter: Int, val text: String,
    val move: Boolean = false, val movePips: Double = 0.0, val moveSec: Int = 60, val moveDir: Int = 0,
)

/** POST の応答 {"ok":…,"msg":…} */
data class PostResult(val ok: Boolean, val msg: String)

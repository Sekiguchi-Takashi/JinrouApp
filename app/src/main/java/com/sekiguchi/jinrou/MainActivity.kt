package com.sekiguchi.jinrou

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.random.Random

// =====================================================
// データ定義
// =====================================================

enum class Role(val jp: String, val desc: String, val isWolf: Boolean) {
    VILLAGER("村人", "特殊能力はありません。推理と投票で村を守りましょう。", false),
    SEER("占い師", "毎晩1人を占い、人狼かどうかを知ることができます。", false),
    MEDIUM("霊能者", "処刑された人が人狼だったかどうかを知ることができます。", false),
    HUNTER("狩人", "毎晩1人を護衛し、人狼の襲撃から守ります。", false),
    WEREWOLF("人狼", "毎晩1人を襲撃します。仲間の人狼が誰か分かります。", true)
}

enum class Animal(val jp: String) {
    RABBIT("うさぎ"), FOX("きつね"), CAT("ねこ"), DOG("いぬ"),
    BEAR("くま"), OWL("ふくろう"), SQUIRREL("りす")
}

class Player(val id: Int, val pname: String, val animal: Animal) {
    var role: Role = Role.VILLAGER
    var alive = true
}

// =====================================================
// ゲームエンジン（ロジック）
// =====================================================

class GameEngine {

    companion object {
        val NAMES = listOf("ミミ", "コン", "タマ", "ポチ", "クマ吉", "ホウ", "リスケ")
    }

    val players = ArrayList<Player>()
    var humanId = -1
    var dayCount = 0

    // プレイヤー（人間）だけが知っている情報
    val humanSeerResults = LinkedHashMap<Int, Boolean>()   // id -> 人狼か
    val humanMediumResults = LinkedHashMap<Int, Boolean>()
    var humanMediumNew: String? = null

    // 公開情報
    val publishedSeer = HashSet<Int>()
    val publicBlack = HashSet<Int>()
    val publicWhite = HashSet<Int>()
    var claimedSeerId = -1

    // CPU占い師の記録
    val cpuSeerResults = LinkedHashMap<Int, Boolean>()

    val morningLog = ArrayList<String>()
    var lastVictim: Player? = null
    var lastExecuted: Player? = null
    var lastVotes: Map<Int, Int> = emptyMap()

    fun human() = players[humanId]
    fun alive() = players.filter { it.alive }

    fun setup() {
        players.clear()
        val animals = Animal.values()
        for (i in 0 until 7) players.add(Player(i, NAMES[i], animals[i]))
        val roles = mutableListOf(
            Role.VILLAGER, Role.VILLAGER, Role.SEER, Role.MEDIUM,
            Role.HUNTER, Role.WEREWOLF, Role.WEREWOLF
        )
        roles.shuffle()
        for (i in 0 until 7) players[i].role = roles[i]
        humanId = Random.nextInt(7)
    }

    // 0=続行 1=村人チーム勝利 2=人狼チーム勝利
    fun winner(): Int {
        val w = alive().count { it.role.isWolf }
        val v = alive().size - w
        if (w == 0) return 1
        if (w >= v) return 2
        return 0
    }

    private fun cpuWolfTarget(): Player? {
        val cands = alive().filter { !it.role.isWolf }
        if (cands.isEmpty()) return null
        if (claimedSeerId >= 0) {
            val seer = players[claimedSeerId]
            if (seer.alive && !seer.role.isWolf && Random.nextInt(100) < 70) return seer
        }
        return cands.random()
    }

    private fun cpuSeerTarget(seer: Player): Player? {
        val others = alive().filter { it.id != seer.id }
        val cands = others.filter { !cpuSeerResults.containsKey(it.id) }
        if (cands.isNotEmpty()) return cands.random()
        return if (others.isNotEmpty()) others.random() else null
    }

    private fun cpuGuardTarget(hunter: Player): Player? {
        val cands = alive().filter { it.id != hunter.id }
        if (cands.isEmpty()) return null
        if (claimedSeerId >= 0 && claimedSeerId != hunter.id) {
            val seer = players[claimedSeerId]
            if (seer.alive && Random.nextInt(100) < 60) return seer
        }
        return cands.random()
    }

    fun resolveNight(humanWolfTarget: Player?, humanSeerTarget: Player?, humanGuardTarget: Player?) {
        dayCount++
        morningLog.clear()

        val seer = players.firstOrNull { it.role == Role.SEER && it.alive }
        val hunter = players.firstOrNull { it.role == Role.HUNTER && it.alive }
        val wolvesAlive = players.filter { it.role.isWolf && it.alive }

        // 占い
        if (seer != null) {
            if (seer.id == humanId) {
                if (humanSeerTarget != null) {
                    humanSeerResults[humanSeerTarget.id] = humanSeerTarget.role.isWolf
                }
            } else {
                val t = cpuSeerTarget(seer)
                if (t != null) cpuSeerResults[t.id] = t.role.isWolf
            }
        }

        // 護衛
        val guard: Player? = when {
            hunter == null -> null
            hunter.id == humanId -> humanGuardTarget
            else -> cpuGuardTarget(hunter)
        }

        // 襲撃
        val target: Player? = when {
            wolvesAlive.isEmpty() -> null
            wolvesAlive.any { it.id == humanId } -> humanWolfTarget
            else -> cpuWolfTarget()
        }

        lastVictim = null
        if (target != null) {
            if (guard != null && guard.id == target.id) {
                // 護衛成功 → 犠牲者なし
            } else {
                target.alive = false
                lastVictim = target
            }
        }

        // CPU占い師の朝のCO（生きていれば）
        if (seer != null && seer.id != humanId && seer.alive) {
            claimedSeerId = seer.id
            val last = cpuSeerResults.entries.lastOrNull()
            if (last != null) {
                val t = players[last.key]
                if (last.value) {
                    publicBlack.add(t.id)
                    morningLog.add("${seer.pname}「占いCO！ ${t.pname} は 人狼 だった！」")
                } else {
                    publicWhite.add(t.id)
                    morningLog.add("${seer.pname}「占いCO。${t.pname} は 人狼ではなかった よ」")
                }
            }
        }

        // 霊能結果（前日の処刑者）
        val medium = players.firstOrNull { it.role == Role.MEDIUM && it.alive }
        val exd = lastExecuted
        if (medium != null && exd != null) {
            val res = exd.role.isWolf
            val resText = if (res) "人狼だった！" else "人狼ではなかった"
            if (medium.id == humanId) {
                humanMediumResults[exd.id] = res
                humanMediumNew = "霊能結果：昨日処刑された ${exd.pname} は $resText"
            } else {
                morningLog.add("${medium.pname}「霊能結果：昨日処刑された ${exd.pname} は $resText」")
            }
        }
        lastExecuted = null
    }

    fun discussionLines(): List<String> {
        val lines = ArrayList<String>()
        val av = alive()
        val templates = listOf(
            "%s がちょっと怪しい気がするなぁ…",
            "%s、昨日なんだか静かだったよね？",
            "ぼくは村人だよ！%s の方が怪しいと思う！",
            "うーん、%s の言動が気になる…",
            "%s を信じていいのかな…？"
        )
        for (p in av) {
            if (p.id == humanId) continue
            if (p.id == claimedSeerId) continue
            val suspects = av.filter {
                it.id != p.id && (p.role != Role.WEREWOLF || !it.role.isWolf)
            }
            if (suspects.isEmpty()) continue
            val black = suspects.filter { publicBlack.contains(it.id) }
            if (black.isNotEmpty()) {
                val t = black.random()
                lines.add("${p.pname}「${t.pname} は人狼と占われてる！今日は ${t.pname} に投票しよう！」")
            } else {
                val notWhite = suspects.filter { !publicWhite.contains(it.id) }
                val pool = if (notWhite.isNotEmpty()) notWhite else suspects
                val t = pool.random()
                lines.add(p.pname + "「" + templates.random().format(t.pname) + "」")
            }
        }
        return lines
    }

    fun runVote(humanVote: Player?): Player {
        val votes = HashMap<Int, Int>()
        for (v in alive()) {
            if (v.id == humanId) {
                if (humanVote != null) votes[v.id] = humanVote.id
                continue
            }
            val cands0 = alive().filter { it.id != v.id }
            val candsW = if (v.role.isWolf) cands0.filter { !it.role.isWolf } else cands0
            val cands = if (candsW.isNotEmpty()) candsW else cands0
            val black = cands.filter { publicBlack.contains(it.id) }
            val pick = if (black.isNotEmpty()) {
                black.random()
            } else {
                val notWhite = cands.filter { !publicWhite.contains(it.id) && it.id != claimedSeerId }
                if (notWhite.isNotEmpty()) notWhite.random() else cands.random()
            }
            votes[v.id] = pick.id
        }
        lastVotes = votes
        val tally = votes.values.groupingBy { it }.eachCount()
        val max = tally.values.maxOrNull() ?: 0
        val top = tally.filter { it.value == max }.keys.toList()
        val executed = players[top.random()]
        executed.alive = false
        lastExecuted = executed
        return executed
    }

    fun publishHumanSeer(): List<String> {
        claimedSeerId = humanId
        val msgs = ArrayList<String>()
        for ((id, isWolf) in humanSeerResults) {
            if (publishedSeer.add(id)) {
                val nm = players[id].pname
                if (isWolf) {
                    publicBlack.add(id)
                    msgs.add("あなた「占いCO！ $nm は 人狼 だ！」")
                } else {
                    publicWhite.add(id)
                    msgs.add("あなた「占いCO。$nm は 人狼ではない」")
                }
            }
        }
        return msgs
    }

    fun publishHumanMedium(): List<String> {
        val msgs = ArrayList<String>()
        for ((id, isWolf) in humanMediumResults) {
            val nm = players[id].pname
            val resText = if (isWolf) "人狼だった" else "人狼ではなかった"
            msgs.add("あなた「霊能CO：$nm は $resText」")
        }
        humanMediumResults.clear()
        return msgs
    }
}

// =====================================================
// キャラクター描画（アニメ風どうぶつ・全部Canvas手描き）
// =====================================================

object CharacterArt {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private fun bodyColor(a: Animal) = when (a) {
        Animal.RABBIT -> Color.parseColor("#F7F3EC")
        Animal.FOX -> Color.parseColor("#F2A254")
        Animal.CAT -> Color.parseColor("#B7BEC9")
        Animal.DOG -> Color.parseColor("#C8935B")
        Animal.BEAR -> Color.parseColor("#9C6B43")
        Animal.OWL -> Color.parseColor("#B39A7C")
        Animal.SQUIRREL -> Color.parseColor("#DE9057")
    }

    private fun irisColor(a: Animal) = when (a) {
        Animal.RABBIT -> Color.parseColor("#D95A73")
        Animal.FOX -> Color.parseColor("#7A4A20")
        Animal.CAT -> Color.parseColor("#3E9E60")
        Animal.DOG -> Color.parseColor("#5B4038")
        Animal.BEAR -> Color.parseColor("#4A342A")
        Animal.OWL -> Color.parseColor("#E8A020")
        Animal.SQUIRREL -> Color.parseColor("#6B4A2A")
    }

    private fun darken(c0: Int): Int {
        val f = 0.72f
        return Color.rgb(
            (Color.red(c0) * f).toInt(),
            (Color.green(c0) * f).toInt(),
            (Color.blue(c0) * f).toInt()
        )
    }

    private fun lighten(c0: Int): Int {
        fun l(v: Int) = (v + (255 - v) * 0.6f).toInt()
        return Color.rgb(l(Color.red(c0)), l(Color.green(c0)), l(Color.blue(c0)))
    }

    fun draw(c: Canvas, a: Animal, cx: Float, cy: Float, size: Float, alive: Boolean) {
        val hr = size * 0.27f
        val hy = cy - size * 0.06f
        val col = bodyColor(a)
        val dark = darken(col)
        p.style = Paint.Style.FILL

        // リスのしっぽ（後ろに描く）
        if (a == Animal.SQUIRREL) {
            p.color = Color.parseColor("#C1683A")
            val tail = RectF(cx + hr * 0.5f, hy - hr * 0.4f, cx + hr * 1.7f, hy + hr * 1.8f)
            c.drawOval(tail, p)
            p.color = Color.parseColor("#E08A55")
            c.drawOval(
                RectF(tail.left + hr * 0.25f, tail.top + hr * 0.3f,
                      tail.right - hr * 0.15f, tail.bottom - hr * 0.3f), p)
        }

        drawEars(c, a, cx, hy, hr, col, dark)

        // 体
        p.color = col
        c.drawOval(RectF(cx - hr * 0.85f, hy + hr * 0.55f, cx + hr * 0.85f, hy + hr * 1.9f), p)
        p.color = lighten(col)
        c.drawOval(RectF(cx - hr * 0.45f, hy + hr * 0.8f, cx + hr * 0.45f, hy + hr * 1.8f), p)

        // 顔
        p.color = col
        c.drawCircle(cx, hy, hr, p)

        // マズル（口まわりの明るいパッチ）
        if (a == Animal.FOX || a == Animal.BEAR || a == Animal.DOG) {
            p.color = lighten(col)
            c.drawOval(RectF(cx - hr * 0.42f, hy + hr * 0.1f, cx + hr * 0.42f, hy + hr * 0.75f), p)
        }

        // アニメ風の大きな目
        val eyeY = hy - hr * 0.05f
        val eyeDX = hr * 0.42f
        val ew = hr * 0.30f
        val eh = hr * 0.42f
        for (sgn in intArrayOf(-1, 1)) {
            val ex = cx + sgn * eyeDX
            if (alive) {
                p.color = Color.WHITE
                c.drawOval(RectF(ex - ew, eyeY - eh, ex + ew, eyeY + eh), p)
                p.color = irisColor(a)
                c.drawOval(RectF(ex - ew * 0.8f, eyeY - eh * 0.75f, ex + ew * 0.8f, eyeY + eh * 0.9f), p)
                p.color = Color.BLACK
                c.drawOval(RectF(ex - ew * 0.45f, eyeY - eh * 0.4f, ex + ew * 0.45f, eyeY + eh * 0.6f), p)
                p.color = Color.WHITE
                c.drawCircle(ex - ew * 0.25f, eyeY - eh * 0.3f, ew * 0.24f, p)
                c.drawCircle(ex + ew * 0.3f, eyeY + eh * 0.25f, ew * 0.12f, p)
            } else {
                stroke.color = Color.DKGRAY
                stroke.strokeWidth = hr * 0.09f
                c.drawLine(ex - ew, eyeY - eh * 0.6f, ex + ew, eyeY + eh * 0.6f, stroke)
                c.drawLine(ex + ew, eyeY - eh * 0.6f, ex - ew, eyeY + eh * 0.6f, stroke)
            }
        }

        // 鼻と口
        if (a == Animal.OWL) {
            p.color = Color.parseColor("#F5A623")
            val beak = Path()
            beak.moveTo(cx - hr * 0.12f, hy + hr * 0.15f)
            beak.lineTo(cx + hr * 0.12f, hy + hr * 0.15f)
            beak.lineTo(cx, hy + hr * 0.42f)
            beak.close()
            c.drawPath(beak, p)
        } else {
            p.color = Color.parseColor("#5B4038")
            c.drawOval(RectF(cx - hr * 0.09f, hy + hr * 0.22f, cx + hr * 0.09f, hy + hr * 0.36f), p)
            stroke.color = Color.parseColor("#5B4038")
            stroke.strokeWidth = hr * 0.05f
            c.drawLine(cx, hy + hr * 0.36f, cx, hy + hr * 0.48f, stroke)
            val m = RectF(cx - hr * 0.22f, hy + hr * 0.32f, cx + hr * 0.22f, hy + hr * 0.62f)
            c.drawArc(m, 20f, 140f, false, stroke)
        }

        // ほっぺ
        p.color = Color.argb(90, 255, 120, 140)
        c.drawOval(RectF(cx - hr * 0.85f, hy + hr * 0.12f, cx - hr * 0.45f, hy + hr * 0.36f), p)
        c.drawOval(RectF(cx + hr * 0.45f, hy + hr * 0.12f, cx + hr * 0.85f, hy + hr * 0.36f), p)

        // ねこのヒゲ
        if (a == Animal.CAT) {
            stroke.color = Color.parseColor("#8A8F9C")
            stroke.strokeWidth = hr * 0.045f
            for (sgn in intArrayOf(-1, 1)) {
                c.drawLine(cx + sgn * hr * 0.55f, hy + hr * 0.28f,
                           cx + sgn * hr * 1.05f, hy + hr * 0.18f, stroke)
                c.drawLine(cx + sgn * hr * 0.55f, hy + hr * 0.40f,
                           cx + sgn * hr * 1.05f, hy + hr * 0.42f, stroke)
            }
        }

        // 死亡時はグレーのベール
        if (!alive) {
            p.color = Color.argb(110, 60, 60, 70)
            c.drawCircle(cx, cy, size * 0.5f, p)
        }
    }

    private fun drawEars(c: Canvas, a: Animal, cx: Float, hy: Float, hr: Float, col: Int, dark: Int) {
        p.style = Paint.Style.FILL
        when (a) {
            Animal.RABBIT -> {
                for (sgn in intArrayOf(-1, 1)) {
                    p.color = col
                    val bx = cx + sgn * hr * 0.55f
                    val e = RectF(bx - hr * 0.22f, hy - hr * 2.0f, bx + hr * 0.22f, hy - hr * 0.3f)
                    c.drawRoundRect(e, hr * 0.22f, hr * 0.22f, p)
                    p.color = Color.parseColor("#FFB6C8")
                    c.drawRoundRect(
                        RectF(e.left + hr * 0.09f, e.top + hr * 0.18f,
                              e.right - hr * 0.09f, e.bottom - hr * 0.35f),
                        hr * 0.15f, hr * 0.15f, p)
                }
            }
            Animal.FOX, Animal.CAT -> {
                for (sgn in intArrayOf(-1, 1)) {
                    val bx = cx + sgn * hr * 0.62f
                    val ear = Path()
                    ear.moveTo(bx - hr * 0.35f, hy - hr * 0.55f)
                    ear.lineTo(bx + hr * 0.35f, hy - hr * 0.55f)
                    ear.lineTo(bx + sgn * hr * 0.1f, hy - hr * 1.35f)
                    ear.close()
                    p.color = if (a == Animal.FOX) dark else col
                    c.drawPath(ear, p)
                    p.color = Color.parseColor("#FFB6C8")
                    val inner = Path()
                    inner.moveTo(bx - hr * 0.18f, hy - hr * 0.6f)
                    inner.lineTo(bx + hr * 0.18f, hy - hr * 0.6f)
                    inner.lineTo(bx + sgn * hr * 0.06f, hy - hr * 1.1f)
                    inner.close()
                    c.drawPath(inner, p)
                }
            }
            Animal.DOG -> {
                for (sgn in intArrayOf(-1, 1)) {
                    p.color = dark
                    val bx = cx + sgn * hr * 0.95f
                    c.drawOval(RectF(bx - hr * 0.3f, hy - hr * 0.75f, bx + hr * 0.3f, hy + hr * 0.4f), p)
                }
            }
            Animal.BEAR -> {
                for (sgn in intArrayOf(-1, 1)) {
                    p.color = col
                    c.drawCircle(cx + sgn * hr * 0.68f, hy - hr * 0.78f, hr * 0.32f, p)
                    p.color = lighten(col)
                    c.drawCircle(cx + sgn * hr * 0.68f, hy - hr * 0.78f, hr * 0.17f, p)
                }
            }
            Animal.OWL -> {
                for (sgn in intArrayOf(-1, 1)) {
                    val bx = cx + sgn * hr * 0.7f
                    val tuft = Path()
                    tuft.moveTo(bx - hr * 0.25f, hy - hr * 0.55f)
                    tuft.lineTo(bx + hr * 0.25f, hy - hr * 0.5f)
                    tuft.lineTo(bx + sgn * hr * 0.2f, hy - hr * 1.15f)
                    tuft.close()
                    p.color = dark
                    c.drawPath(tuft, p)
                }
            }
            Animal.SQUIRREL -> {
                for (sgn in intArrayOf(-1, 1)) {
                    p.color = col
                    c.drawCircle(cx + sgn * hr * 0.6f, hy - hr * 0.85f, hr * 0.26f, p)
                    p.color = Color.parseColor("#FFB6C8")
                    c.drawCircle(cx + sgn * hr * 0.6f, hy - hr * 0.85f, hr * 0.13f, p)
                }
            }
        }
    }
}

class CharacterView(context: Context, private val animal: Animal, private val aliveFlag: Boolean) : View(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height).toFloat()
        CharacterArt.draw(canvas, animal, width / 2f, height / 2f, s, aliveFlag)
    }
}

// =====================================================
// ドラクエ風の街の背景（昼/夜）
// =====================================================

class TownView(context: Context, private val isNight: Boolean) : View(context) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val rnd = Random(7)

        // 空
        val skyTop = if (isNight) Color.parseColor("#0B1035") else Color.parseColor("#7EC8F2")
        val skyBot = if (isNight) Color.parseColor("#25306B") else Color.parseColor("#CDEDFB")
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, skyTop, skyBot, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h * 0.55f, p)
        p.shader = null

        if (isNight) {
            // 月と星
            p.color = Color.parseColor("#FFF6C9")
            c.drawCircle(w * 0.8f, h * 0.12f, w * 0.07f, p)
            p.color = Color.parseColor("#1A2255")
            c.drawCircle(w * 0.8f - w * 0.028f, h * 0.11f, w * 0.055f, p)
            p.color = Color.WHITE
            repeat(45) {
                c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.4f,
                             1.5f + rnd.nextFloat() * 2f, p)
            }
        } else {
            // 太陽と雲
            p.color = Color.parseColor("#FFE28A")
            c.drawCircle(w * 0.82f, h * 0.12f, w * 0.08f, p)
            p.color = Color.WHITE
            for (i in 0..2) {
                val cxc = w * (0.1f + 0.3f * i)
                val cyc = h * (0.07f + 0.03f * i)
                c.drawOval(RectF(cxc, cyc, cxc + w * 0.22f, cyc + h * 0.045f), p)
                c.drawOval(RectF(cxc + w * 0.05f, cyc - h * 0.02f, cxc + w * 0.17f, cyc + h * 0.03f), p)
            }
        }

        // 遠くの丘
        p.color = if (isNight) Color.parseColor("#1B2450") else Color.parseColor("#9CCB86")
        c.drawOval(RectF(-w * 0.3f, h * 0.42f, w * 0.7f, h * 0.62f), p)
        c.drawOval(RectF(w * 0.4f, h * 0.44f, w * 1.3f, h * 0.62f), p)

        // 地面
        p.color = if (isNight) Color.parseColor("#2E3B33") else Color.parseColor("#79B364")
        c.drawRect(0f, h * 0.52f, w, h, p)

        // 石畳の道
        p.color = if (isNight) Color.parseColor("#4A4E5C") else Color.parseColor("#C9C2AE")
        val road = Path()
        road.moveTo(w * 0.36f, h)
        road.lineTo(w * 0.45f, h * 0.55f)
        road.lineTo(w * 0.55f, h * 0.55f)
        road.lineTo(w * 0.68f, h)
        road.close()
        c.drawPath(road, p)
        p.color = if (isNight) Color.parseColor("#3A3E4A") else Color.parseColor("#AFA890")
        var yy = h * 0.58f
        while (yy < h) {
            val t = (yy - h * 0.55f) / (h * 0.45f)
            val half = w * 0.05f + t * w * 0.11f
            c.drawRect(w * 0.5f - half, yy, w * 0.5f + half, yy + 4f, p)
            yy += h * 0.05f + t * h * 0.03f
        }

        // 家（DQの村っぽく）
        drawHouse(c, w * 0.05f, h * 0.33f, w * 0.26f, h * 0.23f)
        drawHouse(c, w * 0.69f, h * 0.36f, w * 0.26f, h * 0.20f)
        drawHouse(c, w * 0.36f, h * 0.29f, w * 0.28f, h * 0.27f)
    }

    private fun drawHouse(c: Canvas, x: Float, y: Float, hw: Float, hh: Float) {
        val wallTop = y + hh * 0.4f
        p.color = if (isNight) Color.parseColor("#5A5346") else Color.parseColor("#EFE3C8")
        c.drawRect(x, wallTop, x + hw, y + hh, p)
        p.color = if (isNight) Color.parseColor("#3B342B") else Color.parseColor("#8A6B4A")
        c.drawRect(x, wallTop, x + hw, wallTop + hh * 0.06f, p)
        // 屋根
        p.color = if (isNight) Color.parseColor("#7A3A3A") else Color.parseColor("#C6553F")
        val roof = Path()
        roof.moveTo(x - hw * 0.08f, wallTop)
        roof.lineTo(x + hw / 2f, y)
        roof.lineTo(x + hw * 1.08f, wallTop)
        roof.close()
        c.drawPath(roof, p)
        // 窓（夜は灯りがともる）
        p.color = if (isNight) Color.parseColor("#FFD97A") else Color.parseColor("#7EC8F2")
        c.drawRect(x + hw * 0.13f, wallTop + hh * 0.16f, x + hw * 0.33f, wallTop + hh * 0.4f, p)
        c.drawRect(x + hw * 0.67f, wallTop + hh * 0.16f, x + hw * 0.87f, wallTop + hh * 0.4f, p)
        // ドア
        p.color = if (isNight) Color.parseColor("#4A3626") else Color.parseColor("#8A5A34")
        c.drawRect(x + hw * 0.42f, wallTop + hh * 0.25f, x + hw * 0.58f, y + hh, p)
    }
}

// =====================================================
// MainActivity（全画面をコードで構築・XMLなし）
// =====================================================

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private var engine = GameEngine()
    private var night = false
    private var currentDayLines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
        showTitle()
    }

    // ---------- UIヘルパー ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setScreen(content: View) {
        root.removeAllViews()
        root.addView(TownView(this, night), FrameLayout.LayoutParams(-1, -1))
        val sc = ScrollView(this)
        sc.isFillViewport = true
        sc.addView(content)
        root.addView(sc, FrameLayout.LayoutParams(-1, -1))
    }

    private fun panel(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(18), dp(24), dp(18), dp(32))
        return l
    }

    private fun card(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(16), dp(16), dp(16), dp(16))
        val bg = GradientDrawable()
        bg.setColor(Color.argb(205, 16, 20, 44))
        bg.cornerRadius = dp(14).toFloat()
        bg.setStroke(dp(2), Color.WHITE)
        l.background = bg
        return l
    }

    private fun tv(text: String, sizeSp: Float = 15f, bold: Boolean = false,
                   color: Int = Color.WHITE): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD)
        t.setLineSpacing(0f, 1.15f)
        return t
    }

    private fun btn(text: String, color: Int = Color.parseColor("#3D6BD8"),
                    onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 16f
        b.setTextColor(Color.WHITE)
        b.transformationMethod = null
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(12).toFloat()
        bg.setStroke(dp(2), Color.WHITE)
        b.background = bg
        b.setPadding(dp(16), dp(12), dp(16), dp(12))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun space(hpx: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(1, hpx)
        return v
    }

    private fun charCell(pl: Player, sizeDp: Int, onClick: ((Player) -> Unit)?): LinearLayout {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER
        cell.addView(CharacterView(this, pl.animal, pl.alive),
            LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
        val nameText = pl.pname + if (!pl.alive) " †" else ""
        val name = tv(nameText, 12f, true,
            if (pl.alive) Color.WHITE else Color.parseColor("#9AA0B5"))
        name.gravity = Gravity.CENTER
        cell.addView(name)
        if (engine.humanId >= 0 && pl.id == engine.humanId) {
            val you = tv("YOU", 10f, true, Color.parseColor("#FFD97A"))
            you.gravity = Gravity.CENTER
            cell.addView(you)
        }
        if (onClick != null && pl.alive) cell.setOnClickListener { onClick(pl) }
        return cell
    }

    private fun charGrid(list: List<Player>, sizeDp: Int, perRow: Int,
                         onClick: ((Player) -> Unit)?): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        var row = LinearLayout(this)
        list.forEachIndexed { i, pl ->
            if (i % perRow == 0) {
                row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER
                col.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(dp(3), dp(4), dp(3), dp(4))
            row.addView(charCell(pl, sizeDp, onClick), lp)
        }
        return col
    }

    private fun centerChar(pl: Player, sizeDp: Int): LinearLayout {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.HORIZONTAL
        wrap.gravity = Gravity.CENTER
        wrap.addView(charCell(pl, sizeDp, null))
        return wrap
    }

    // ---------- タイトル / ルール ----------

    private fun showTitle() {
        night = false
        val pn = panel()
        pn.gravity = Gravity.CENTER_HORIZONTAL
        pn.addView(space(dp(20)))
        val titleT = tv("どうぶつ人狼", 34f, true, Color.WHITE)
        titleT.gravity = Gravity.CENTER
        titleT.setShadowLayer(8f, 0f, 4f, Color.argb(180, 0, 0, 0))
        pn.addView(titleT)
        val sub = tv("〜 月夜の村の推理ゲーム 〜", 14f, false, Color.parseColor("#FFE28A"))
        sub.gravity = Gravity.CENTER
        sub.setShadowLayer(6f, 0f, 2f, Color.argb(180, 0, 0, 0))
        pn.addView(sub)
        pn.addView(space(dp(14)))

        val preview = ArrayList<Player>()
        Animal.values().forEachIndexed { i, an -> preview.add(Player(i, GameEngine.NAMES[i], an)) }
        val cd0 = card()
        cd0.addView(charGrid(preview, 58, 4, null))
        pn.addView(cd0, LinearLayout.LayoutParams(-1, -2))
        pn.addView(space(dp(14)))

        val sp = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
        val g = sp.getInt("games", 0)
        if (g > 0) {
            val st = tv("戦績: ${sp.getInt("wins", 0)}勝 / ${g}戦", 13f, true, Color.WHITE)
            st.gravity = Gravity.CENTER
            st.setShadowLayer(6f, 0f, 2f, Color.argb(180, 0, 0, 0))
            pn.addView(st)
            pn.addView(space(dp(10)))
        }

        pn.addView(btn("はじめる", Color.parseColor("#D8703D")) { startGame() },
            LinearLayout.LayoutParams(-1, -2))
        pn.addView(space(dp(10)))
        pn.addView(btn("ルール") { showRules() }, LinearLayout.LayoutParams(-1, -2))
        setScreen(pn)
    }

    private fun showRules() {
        val pn = panel()
        val cd = card()
        cd.addView(tv("📖 ルール", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(space(dp(10)))
        cd.addView(tv("【ゲームの流れ】", 15f, true))
        cd.addView(tv(
            "🌙 夜\n" +
            "・全員目を閉じます。\n" +
            "・人狼が相談し、襲撃する相手を決めます。\n" +
            "・占い師が1人を占います。\n" +
            "・狩人が1人を護衛します。\n" +
            "・朝になると、襲撃された人（護衛成功なら0人）が判明します。", 14f))
        cd.addView(space(dp(8)))
        cd.addView(tv(
            "☀️ 昼\n" +
            "・生き残った全員で話し合います。\n" +
            "・「誰が人狼か」を推理します。\n" +
            "・投票を行い、最も票が多かった人が処刑されます。\n" +
            "・処刑後、再び夜になります。", 14f))
        cd.addView(space(dp(10)))
        cd.addView(tv("【勝利条件】", 15f, true))
        cd.addView(tv(
            "・村人チーム：すべての人狼を処刑すれば勝利。\n" +
            "・人狼チーム：人狼の人数が村人側の人数と同じになれば勝利。", 14f))
        cd.addView(space(dp(10)))
        cd.addView(tv("【構成（総数7人）】", 15f, true))
        cd.addView(tv("村人 ×2 / 占い師 ×1 / 霊能者 ×1 / 狩人 ×1 / 人狼 ×2", 14f))
        cd.addView(space(dp(14)))
        cd.addView(btn("タイトルへ戻る") { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- ゲーム開始 / 役職確認 ----------

    private fun startGame() {
        engine = GameEngine()
        engine.setup()
        currentDayLines = ArrayList()
        showRoleReveal()
    }

    private fun showRoleReveal() {
        night = false
        val h = engine.human()
        val pn = panel()
        val cd = card()
        cd.addView(tv("あなたのキャラクター", 14f, true, Color.parseColor("#FFE28A")))
        cd.addView(centerChar(h, 128))
        val nm = tv("${h.pname}（${h.animal.jp}）", 18f, true)
        nm.gravity = Gravity.CENTER
        cd.addView(nm)
        cd.addView(space(dp(10)))
        val roleT = tv("役職: ${h.role.jp}", 22f, true,
            if (h.role.isWolf) Color.parseColor("#FF9B9B") else Color.parseColor("#A8E6A1"))
        roleT.gravity = Gravity.CENTER
        cd.addView(roleT)
        cd.addView(tv(h.role.desc, 14f))
        if (h.role.isWolf) {
            val partner = engine.players.first { it.role.isWolf && it.id != h.id }
            cd.addView(space(dp(6)))
            cd.addView(tv("🐺 仲間の人狼: ${partner.pname}（${partner.animal.jp}）",
                15f, true, Color.parseColor("#FF9B9B")))
        }
        cd.addView(space(dp(16)))
        cd.addView(btn("最初の夜へ", Color.parseColor("#5A4FD8")) { beginNight() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- 夜フェーズ ----------

    private fun beginNight() {
        night = true
        val h = engine.human()
        if (!h.alive) {
            showNightSleep("あなたは天から静かに村を見守っている…（観戦中）")
            return
        }
        when (h.role) {
            Role.WEREWOLF -> showWolfChoose()
            Role.SEER -> showSeerChoose()
            Role.HUNTER -> showHunterChoose()
            else -> showNightSleep("夜が更けていく…。あなたは静かに眠りについた。")
        }
    }

    private fun showNightSleep(msg: String) {
        val pn = panel()
        val cd = card()
        cd.addView(tv("🌙 夜", 22f, true, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(8)))
        cd.addView(tv(msg))
        cd.addView(space(dp(16)))
        cd.addView(btn("朝を待つ", Color.parseColor("#5A4FD8")) { finishNight(null, null, null) })
        pn.addView(cd)
        setScreen(pn)
    }

    private fun showWolfChoose() {
        val pn = panel()
        val cd = card()
        cd.addView(tv("🐺 夜 - 人狼の襲撃", 20f, true, Color.parseColor("#FF9B9B")))
        val partner = engine.players.firstOrNull { it.role.isWolf && it.id != engine.humanId }
        if (partner != null) {
            val ptext = "仲間の人狼: ${partner.pname}" + if (!partner.alive) "（死亡）" else ""
            cd.addView(tv(ptext, 13f, false, Color.parseColor("#FFC9C9")))
        }
        cd.addView(space(dp(8)))
        cd.addView(tv("襲撃する相手を選んでください"))
        cd.addView(space(dp(10)))
        val cands = engine.alive().filter { !it.role.isWolf }
        cd.addView(charGrid(cands, 80, 3) { t -> finishNight(t, null, null) })
        pn.addView(cd)
        setScreen(pn)
    }

    private fun showSeerChoose() {
        val pn = panel()
        val cd = card()
        cd.addView(tv("🔮 夜 - 占い", 20f, true, Color.parseColor("#C9B6FF")))
        cd.addView(space(dp(8)))
        cd.addView(tv("占う相手を選んでください"))
        cd.addView(space(dp(10)))
        val known = engine.humanSeerResults.keys
        var cands = engine.alive().filter { it.id != engine.humanId && !known.contains(it.id) }
        if (cands.isEmpty()) cands = engine.alive().filter { it.id != engine.humanId }
        cd.addView(charGrid(cands, 80, 3) { t -> finishNight(null, t, null) })
        pn.addView(cd)
        setScreen(pn)
    }

    private fun showHunterChoose() {
        val pn = panel()
        val cd = card()
        cd.addView(tv("🛡️ 夜 - 護衛", 20f, true, Color.parseColor("#A8D8FF")))
        cd.addView(space(dp(8)))
        cd.addView(tv("人狼の襲撃から守る相手を選んでください（自分以外）"))
        cd.addView(space(dp(10)))
        val cands = engine.alive().filter { it.id != engine.humanId }
        cd.addView(charGrid(cands, 80, 3) { t -> finishNight(null, null, t) })
        pn.addView(cd)
        setScreen(pn)
    }

    private fun finishNight(w: Player?, s: Player?, g: Player?) {
        engine.resolveNight(w, s, g)
        if (s != null) showSeerResult(s) else showMorning()
    }

    private fun showSeerResult(t: Player) {
        val isWolf = engine.humanSeerResults[t.id] == true
        val pn = panel()
        val cd = card()
        cd.addView(tv("🔮 占い結果", 20f, true, Color.parseColor("#C9B6FF")))
        cd.addView(centerChar(t, 100))
        val res = tv(
            if (isWolf) "${t.pname} は 人狼 だ！" else "${t.pname} は 人狼ではない",
            18f, true,
            if (isWolf) Color.parseColor("#FF9B9B") else Color.parseColor("#A8E6A1"))
        res.gravity = Gravity.CENTER
        cd.addView(res)
        cd.addView(space(dp(8)))
        cd.addView(tv("※この結果はあなただけが知っています。昼にCO（公開）できます。", 12f,
            false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(14)))
        cd.addView(btn("朝になる") { showMorning() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- 朝 / 昼 / 投票 ----------

    private fun showMorning() {
        night = false
        val e = engine
        val pn = panel()
        val cd = card()
        cd.addView(tv("☀️ ${e.dayCount}日目の朝", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(space(dp(8)))

        val v = e.lastVictim
        if (v != null) {
            cd.addView(centerChar(v, 96))
            val who = if (v.id == e.humanId) "あなた（${v.pname}）" else v.pname
            val vt = tv("昨夜、$who が襲撃されてしまった…", 16f, true, Color.parseColor("#FF9B9B"))
            vt.gravity = Gravity.CENTER
            cd.addView(vt)
        } else {
            val vt = tv("昨夜は誰も犠牲にならなかった。平和な朝だ！", 16f, true,
                Color.parseColor("#A8E6A1"))
            vt.gravity = Gravity.CENTER
            cd.addView(vt)
        }

        e.humanMediumNew?.let {
            cd.addView(space(dp(10)))
            cd.addView(tv("【あなただけの情報】", 13f, true, Color.parseColor("#C9B6FF")))
            cd.addView(tv(it, 15f, false, Color.parseColor("#C9B6FF")))
            e.humanMediumNew = null
        }

        if (e.morningLog.isNotEmpty()) {
            cd.addView(space(dp(10)))
            for (line in e.morningLog) cd.addView(tv("・$line", 14f))
        }

        cd.addView(space(dp(16)))
        val w = e.winner()
        if (w != 0) {
            cd.addView(btn("結果を見る", Color.parseColor("#D8703D")) { showGameOver(w) })
        } else {
            cd.addView(btn("昼の話し合いへ") {
                currentDayLines = ArrayList(e.discussionLines())
                showDay()
            })
        }
        pn.addView(cd)
        setScreen(pn)
    }

    private fun showDay() {
        val e = engine
        val h = e.human()
        val pn = panel()
        val cd = card()
        cd.addView(tv("💬 ${e.dayCount}日目の昼 - 話し合い", 19f, true, Color.parseColor("#FFE28A")))
        var meText = "あなた: ${h.pname}（${h.role.jp}）"
        if (!h.alive) meText += " †死亡"
        cd.addView(tv(meText, 13f, false, Color.parseColor("#BFD0FF")))
        if (h.role.isWolf) {
            val partner = e.players.first { it.role.isWolf && it.id != h.id }
            cd.addView(tv("🐺 仲間: ${partner.pname}" + if (!partner.alive) "（死亡）" else "",
                12f, false, Color.parseColor("#FFC9C9")))
        }
        cd.addView(space(dp(8)))
        cd.addView(charGrid(e.players, 58, 4, null))
        cd.addView(space(dp(8)))

        for (line in currentDayLines) {
            cd.addView(tv(line, 14f))
            cd.addView(space(dp(6)))
        }

        // 占い師（人間）の手元の結果とCO
        if (h.alive && h.role == Role.SEER && e.humanSeerResults.isNotEmpty()) {
            cd.addView(space(dp(6)))
            cd.addView(tv("【あなたの占い結果（非公開分含む）】", 13f, true, Color.parseColor("#C9B6FF")))
            for ((id, isw) in e.humanSeerResults) {
                val mark = if (e.publishedSeer.contains(id)) "（公開済）" else ""
                cd.addView(tv("・${e.players[id].pname}: " +
                    (if (isw) "人狼" else "人狼ではない") + mark, 13f, false,
                    Color.parseColor("#C9B6FF")))
            }
            if (e.humanSeerResults.keys.any { !e.publishedSeer.contains(it) }) {
                cd.addView(space(dp(6)))
                cd.addView(btn("🔮 占い結果を公開する（CO）", Color.parseColor("#7A4FD8")) {
                    currentDayLines.addAll(e.publishHumanSeer())
                    showDay()
                })
            }
        }

        // 霊能者（人間）のCO
        if (h.alive && h.role == Role.MEDIUM && e.humanMediumResults.isNotEmpty()) {
            cd.addView(space(dp(6)))
            cd.addView(btn("👻 霊能結果を公開する（CO）", Color.parseColor("#4F7AD8")) {
                currentDayLines.addAll(e.publishHumanMedium())
                showDay()
            })
        }

        cd.addView(space(dp(14)))
        if (h.alive) {
            cd.addView(btn("投票へ進む", Color.parseColor("#D8703D")) { showVote() })
        } else {
            cd.addView(btn("開票へ（観戦）") {
                val ex = e.runVote(null)
                showExecution(ex)
            })
        }
        pn.addView(cd)
        setScreen(pn)
    }

    private fun showVote() {
        val e = engine
        val pn = panel()
        val cd = card()
        cd.addView(tv("🗳️ 投票", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(space(dp(6)))
        cd.addView(tv("処刑する相手に投票してください"))
        cd.addView(space(dp(10)))
        val cands = e.alive().filter { it.id != e.humanId }
        cd.addView(charGrid(cands, 80, 3) { t ->
            val ex = e.runVote(t)
            showExecution(ex)
        })
        pn.addView(cd)
        setScreen(pn)
    }

    private fun showExecution(ex: Player) {
        val e = engine
        val pn = panel()
        val cd = card()
        cd.addView(tv("⚖️ 開票結果", 20f, true, Color.parseColor("#FFC9C9")))
        cd.addView(space(dp(8)))
        for ((voterId, targetId) in e.lastVotes) {
            val vn = if (voterId == e.humanId) "あなた" else e.players[voterId].pname
            cd.addView(tv("$vn → ${e.players[targetId].pname}", 14f))
        }
        cd.addView(space(dp(10)))
        cd.addView(centerChar(ex, 96))
        val exn = if (ex.id == e.humanId) "あなた（${ex.pname}）" else ex.pname
        val et = tv("$exn が処刑された…", 16f, true, Color.parseColor("#FF9B9B"))
        et.gravity = Gravity.CENTER
        cd.addView(et)
        cd.addView(space(dp(16)))
        val w = e.winner()
        if (w != 0) {
            cd.addView(btn("結果を見る", Color.parseColor("#D8703D")) { showGameOver(w) })
        } else {
            cd.addView(btn("夜になる", Color.parseColor("#5A4FD8")) { beginNight() })
        }
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- ゲーム終了 ----------

    private fun showGameOver(w: Int) {
        night = false
        val e = engine
        val humanWolf = e.human().role.isWolf
        val humanWin = (w == 1 && !humanWolf) || (w == 2 && humanWolf)

        val sp = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
        sp.edit()
            .putInt("games", sp.getInt("games", 0) + 1)
            .putInt("wins", sp.getInt("wins", 0) + if (humanWin) 1 else 0)
            .apply()

        val pn = panel()
        val cd = card()
        val wt = tv(
            if (w == 1) "🎉 村人チームの勝利！" else "🐺 人狼チームの勝利！",
            22f, true,
            if (w == 1) Color.parseColor("#A8E6A1") else Color.parseColor("#FF9B9B"))
        wt.gravity = Gravity.CENTER
        cd.addView(wt)
        val ht = tv(if (humanWin) "あなたの勝ちです！" else "あなたの負けです…", 16f, true)
        ht.gravity = Gravity.CENTER
        cd.addView(ht)
        cd.addView(space(dp(12)))
        cd.addView(tv("【役職公開】", 14f, true, Color.parseColor("#FFE28A")))
        for (p2 in e.players) {
            var line = "${p2.pname}（${p2.animal.jp}）: ${p2.role.jp}"
            if (!p2.alive) line += " †"
            if (p2.id == e.humanId) line += " ← あなた"
            cd.addView(tv(line, 14f, false,
                if (p2.role.isWolf) Color.parseColor("#FF9B9B") else Color.WHITE))
        }
        cd.addView(space(dp(16)))
        cd.addView(btn("もう一度あそぶ", Color.parseColor("#D8703D")) { startGame() })
        cd.addView(space(dp(8)))
        cd.addView(btn("タイトルへ") { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }
}

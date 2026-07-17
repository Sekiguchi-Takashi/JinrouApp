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
    BEAR("くま"), OWL("ふくろう"), SQUIRREL("りす"),
    KOALA("こあら"), PENGUIN("ぺんぎん")
}

class Player(val id: Int, val pname: String, val animal: Animal) {
    var role: Role = Role.VILLAGER
    var alive = true
}

// 昼の発言（吹き出し・まとめ図用の構造化データ）
class Talk(val speakerId: Int, val text: String, val targetId: Int, val suspect: Boolean)

// =====================================================
// ゲームエンジン（ロジック）
// =====================================================

class GameEngine {

    companion object {
        val NAMES = listOf("ミミ", "コン", "タマ", "ポチ", "クマ吉", "ホウ", "リスケ", "コアタ", "ペン太")
        const val N = 9
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

    // 占い師フェーズ（2日目の朝から）
    var seerPhaseStarted = false          // 最初の名乗り出が済んだか
    val seerClaimants = ArrayList<Int>()  // 名乗り出た占い師（本物/偽物混在・以後増えない）
    var fakeSeerId = -1                   // 偽占い師（人狼）のid
    val suspicionBoost = HashSet<Int>()   // フェーズで黒と言われた人（みんなが疑いやすくなる）
    private val cpuSeerAnnounced = HashSet<Int>()
    private val fakeAccused = HashSet<Int>()

    // CPU占い師の記録
    val cpuSeerResults = LinkedHashMap<Int, Boolean>()

    // 自由会話・説得・名探偵システム
    var humanTrust = true                 // あなたの発言の信用度（予想が外れると失う）
    var persuadedToday = false            // 1日1回だけ説得できる
    val persuaded = HashMap<Int, Int>()   // listenerId -> 採用した疑い先
    val humanClaims = HashSet<Int>()      // あなたが「人狼だ」と主張した相手
    var wolfGrudge = false                // 人狼に相方をチクってしまった→狙われる
    val voteStreak = HashMap<Int, Int>()  // 投票で人狼を当てた連続回数
    var detectiveId = -1                  // 名探偵の称号を持つキャラ
    var detectivePick = -1                // 今日の名探偵の予想
    var newDetectiveJustNow = false       // 今回の開票で名探偵が誕生した

    val morningLog = ArrayList<String>()
    var lastVictim: Player? = null
    var lastExecuted: Player? = null
    var lastVotes: Map<Int, Int> = emptyMap()
    val wolfVictimIds = ArrayList<Int>()   // 人狼に襲撃されたキャラ（夜画面の下に表示）

    fun human() = players[humanId]
    fun alive() = players.filter { it.alive }

    fun setup() {
        players.clear()
        val animals = Animal.values()
        for (i in 0 until N) players.add(Player(i, NAMES[i], animals[i]))
        val roles = mutableListOf(
            Role.VILLAGER, Role.VILLAGER, Role.VILLAGER, Role.VILLAGER,
            Role.SEER, Role.MEDIUM, Role.HUNTER,
            Role.WEREWOLF, Role.WEREWOLF
        )
        roles.shuffle()
        for (i in 0 until N) players[i].role = roles[i]
        humanId = Random.nextInt(N)
        dayCount = 1   // 1日目は昼の話し合いから始まる
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
        // 相方をチクられた恨み → あなたを狙う
        if (wolfGrudge) {
            val h = players[humanId]
            if (h.alive && !h.role.isWolf && Random.nextInt(100) < 80) return h
        }
        val realSeer = players.firstOrNull { it.role == Role.SEER }
        if (realSeer != null && realSeer.alive &&
            seerClaimants.contains(realSeer.id) && Random.nextInt(100) < 70) {
            return realSeer
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
        val claimAlive = seerClaimants.map { players[it] }
            .filter { it.alive && it.id != hunter.id }
        if (claimAlive.size >= 2) return claimAlive.random()          // 2人COなら必ずどちらかを守る
        if (claimAlive.size == 1 && Random.nextInt(100) < 60) return claimAlive[0]
        return cands.random()
    }

    fun resolveNight(humanWolfTarget: Player?, humanSeerTarget: Player?, humanGuardTarget: Player?) {
        dayCount++
        morningLog.clear()
        persuaded.clear()
        persuadedToday = false

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
                wolfVictimIds.add(target.id)
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
                // あなたが人狼だと主張した相手が、公開の場でシロと判明 → 信用を失う
                if (!res && humanTrust && humanClaims.contains(exd.id)) {
                    humanTrust = false
                    morningLog.add("${players[humanId].pname} の予想（${exd.pname} が人狼）は外れだった…みんなの信用を失ってしまった。")
                }
            }
        }
        lastExecuted = null
    }

    // ---------- 占い師フェーズ ----------

    // 最初のフェーズで名乗り出を確定（以後、途中から名乗り出ることはない）
    fun ensureSeerPhase(humanClaims: Boolean) {
        if (seerPhaseStarted) return
        seerPhaseStarted = true
        val realSeer = players.firstOrNull { it.role == Role.SEER }
        if (realSeer != null && realSeer.alive) {
            if (realSeer.id == humanId) {
                if (humanClaims) seerClaimants.add(realSeer.id)
            } else if (Random.nextInt(100) < 75) {
                seerClaimants.add(realSeer.id)   // 本物も名乗り出ないことがある
            }
        }
        // 人狼のうち1人（CPU）が偽占い師として名乗り出ることがある
        val cpuWolves = players.filter { it.role.isWolf && it.alive && it.id != humanId }
        if (cpuWolves.isNotEmpty() && Random.nextInt(100) < 55) {
            fakeSeerId = cpuWolves.random().id
            seerClaimants.add(fakeSeerId)
        }
        seerClaimants.shuffle()
    }

    // 今朝の占い師フェーズの発言（名乗り出た者だけが話す）
    fun seerPhaseTalks(): List<Talk> {
        val talks = ArrayList<Talk>()
        var realTalk: Talk? = null
        val realSeer = players.firstOrNull { it.role == Role.SEER }

        // 本物（CPU）の発表：本当のことを言う
        if (realSeer != null && realSeer.alive && realSeer.id != humanId &&
            seerClaimants.contains(realSeer.id)) {
            val e2 = cpuSeerResults.entries.lastOrNull { !cpuSeerAnnounced.contains(it.key) }
            if (e2 != null) {
                cpuSeerAnnounced.add(e2.key)
                val t = players[e2.key]
                realTalk = if (e2.value) {
                    publicBlack.add(t.id); suspicionBoost.add(t.id)
                    Talk(realSeer.id, "占い結果！ ${t.pname} は 人狼 だ！", t.id, true)
                } else {
                    publicWhite.add(t.id); if (humanClaims.contains(t.id)) humanTrust = false
                    Talk(realSeer.id, "占い結果。${t.pname} は 人狼ではない よ", t.id, false)
                }
                talks.add(realTalk!!)
            }
        }

        // 本物（あなた）の発表：名乗り出ていれば未公開分を発表
        if (realSeer != null && realSeer.alive && realSeer.id == humanId &&
            seerClaimants.contains(humanId)) {
            for ((id, isWolf) in humanSeerResults) {
                if (publishedSeer.add(id)) {
                    val nm = players[id].pname
                    val tk = if (isWolf) {
                        publicBlack.add(id); suspicionBoost.add(id)
                        Talk(humanId, "占い結果！ $nm は 人狼 だ！", id, true)
                    } else {
                        publicWhite.add(id); if (humanClaims.contains(id)) humanTrust = false
                        Talk(humanId, "占い結果。$nm は 人狼ではない", id, false)
                    }
                    talks.add(tk)
                    if (realTalk == null) realTalk = tk
                }
            }
        }

        // 偽物（人狼）の発表：本物のマネをするか、人狼以外を「人狼」と言う
        val fake = if (fakeSeerId >= 0) players[fakeSeerId] else null
        if (fake != null && fake.alive) {
            val copy = realTalk != null && Random.nextInt(100) < 50
            if (copy) {
                val rt = realTalk!!
                talks.add(Talk(fake.id, rt.text, rt.targetId, rt.suspect))
                if (rt.suspect) { publicBlack.add(rt.targetId); suspicionBoost.add(rt.targetId) }
                else publicWhite.add(rt.targetId); if (humanClaims.contains(rt.targetId)) humanTrust = false
            } else {
                val cands = alive().filter {
                    it.id != fake.id && !it.role.isWolf && !fakeAccused.contains(it.id)
                }
                if (cands.isNotEmpty()) {
                    val t = cands.random()
                    fakeAccused.add(t.id)
                    publicBlack.add(t.id); suspicionBoost.add(t.id)
                    talks.add(Talk(fake.id, "占い結果！ ${t.pname} は 人狼 だ！", t.id, true))
                }
            }
        }
        return talks
    }

    // ---------- 自由会話・説得 ----------

    // 疑い話し合いの前の自由な発言（無言もある）
    fun freeTalks(): List<Talk> {
        val talks = ArrayList<Talk>()
        val av = alive()
        for (p in av) {
            if (p.id == humanId) continue
            val others = av.filter { it.id != p.id }
            if (others.isEmpty()) continue
            val r = Random.nextInt(100)
            when {
                r < 18 -> { /* あえて無言 */ }
                r < 40 -> talks.add(Talk(p.id, "ぼくは人狼じゃないよ！ほんとだよ！", p.id, false))
                r < 58 -> {
                    val t = others.random()
                    talks.add(Talk(p.id, "${t.pname} は信用できると思うんだ。", t.id, false))
                }
                r < 76 -> {
                    val t = others.random()
                    talks.add(Talk(p.id, "${t.pname} が占い師なんじゃないかな？", t.id, false))
                }
                else -> {
                    val t = others.random()
                    talks.add(Talk(p.id, "狩人は ${t.pname} っぽい気がする。", t.id, false))
                }
            }
        }
        if (detectiveId >= 0 && players[detectiveId].alive) {
            talks.add(0, Talk(detectiveId, "ふっふっふ…名探偵のぼくに任せたまえ！", detectiveId, false))
        }
        return talks
    }

    // あなたが listener に「target が人狼だと思う」とこっそり伝える
    fun persuade(listener: Player, target: Player): Talk {
        persuadedToday = true
        val me = players[humanId]
        // 信用を失っている → 無視される
        if (!humanTrust) {
            return Talk(listener.id, "……（${me.pname} の話はもう信用できないなあ）", humanId, false)
        }
        // あなたが人狼で名探偵を説得しようとすると、2回に1回バレる
        if (me.role.isWolf && listener.id == detectiveId && Random.nextInt(2) == 0) {
            publicBlack.add(humanId)
            suspicionBoost.add(humanId)
            humanTrust = false
            return Talk(listener.id,
                "……キミ、さっきから様子が変だよ。まさか、キミが人狼なんじゃないのか！？",
                humanId, true)
        }
        humanClaims.add(target.id)
        // 人狼本人に相方の人狼を伝えてしまった → 恨まれて狙われる
        if (listener.role.isWolf && target.role.isWolf && target.id != listener.id) {
            wolfGrudge = true
            return Talk(listener.id,
                "へえ…${target.pname} が人狼ねえ。……おもしろいこと言うんだね、キミ。",
                target.id, false)
        }
        return if (Random.nextInt(100) < 70) {
            persuaded[listener.id] = target.id
            if (listener.id == detectiveId) {
                Talk(listener.id, "なるほど…名探偵のカンにビビッときた。${target.pname} が怪しいぞ！",
                    target.id, true)
            } else {
                Talk(listener.id, "なるほど…${target.pname} が怪しいのか。覚えておくよ。",
                    target.id, true)
            }
        } else {
            Talk(listener.id, "うーん、ぼくは ${target.pname} が人狼だとは思わないなあ。",
                target.id, false)
        }
    }

    fun discussionTalks(): List<Talk> {
        val talks = ArrayList<Talk>()
        val av = alive()
        val suspectTpl = listOf(
            "%s がちょっと怪しい気がするなぁ…",
            "%s、昨日なんだか静かだったよね？",
            "ぼくは村人だよ！%s の方が怪しいと思う！",
            "うーん、%s の言動が気になる…",
            "%s を信じていいのかな…？"
        )
        val trustTpl = listOf(
            "%s は白判定が出てるし、信じていいと思う！",
            "%s は人狼じゃないって占われてるよね。",
            "ぼくは %s を信頼してるよ。"
        )
        // 名探偵の今日の予想（みんなが同調する）
        detectivePick = -1
        if (detectiveId >= 0 && players[detectiveId].alive) {
            val det = players[detectiveId]
            val dcands = av.filter { it.id != det.id }
            val pick = persuaded[det.id]?.let { players[it] }?.takeIf { it.alive }
                ?: dcands.filter { publicBlack.contains(it.id) }.randomOrNull()
                ?: dcands.filter { suspicionBoost.contains(it.id) }.randomOrNull()
                ?: dcands.filter { !publicWhite.contains(it.id) }.randomOrNull()
                ?: dcands.randomOrNull()
            if (pick != null) {
                detectivePick = pick.id
                talks.add(Talk(det.id, "🎩 名探偵のカン！ ${pick.pname} が人狼だ！", pick.id, true))
            }
        }

        for (p in av) {
            if (p.id == humanId) continue
            if (p.id == detectiveId) continue            // 名探偵はもう発言した
            if (seerClaimants.contains(p.id)) continue   // 占い師CO中は昼は静かに
            val suspects = av.filter {
                it.id != p.id && (p.role != Role.WEREWOLF || !it.role.isWolf)
            }
            if (suspects.isEmpty()) continue

            // 自由会話であなたに説得された意見を採用することがある
            val padopt = persuaded[p.id]?.let { players[it] }
                ?.takeIf { it.alive && it.id != p.id && (p.role != Role.WEREWOLF || !it.role.isWolf) }
            if (padopt != null && Random.nextInt(100) < 80) {
                talks.add(Talk(p.id,
                    "${players[humanId].pname} の言うとおり、${padopt.pname} が怪しい気がしてきた…",
                    padopt.id, true))
                continue
            }

            // 名探偵の予想に同調する
            if (detectivePick >= 0 && detectivePick != p.id) {
                val dt = players[detectivePick]
                if (dt.alive && (p.role != Role.WEREWOLF || !dt.role.isWolf) &&
                    Random.nextInt(100) < 75) {
                    talks.add(Talk(p.id, "名探偵が言うなら、${dt.pname} に投票するよ！", dt.id, true))
                    continue
                }
            }

            val black = suspects.filter { publicBlack.contains(it.id) }
            val whites = suspects.filter { publicWhite.contains(it.id) }
            if (black.isNotEmpty() && Random.nextInt(100) < 70) {
                val t = black.random()
                talks.add(Talk(p.id,
                    "${t.pname} は人狼と占われてる！今日は ${t.pname} に投票しよう！",
                    t.id, true))
            } else if (whites.isNotEmpty() && Random.nextInt(100) < 30) {
                val t = whites.random()
                talks.add(Talk(p.id, trustTpl.random().format(t.pname), t.id, false))
            } else {
                val notWhite = suspects.filter { !publicWhite.contains(it.id) }
                val pool = if (notWhite.isNotEmpty()) notWhite else suspects
                // 占い師フェーズで怪しいと言われた人は、みんなが疑いやすい
                val boosted = pool.filter { suspicionBoost.contains(it.id) }
                val t = if (boosted.isNotEmpty() && Random.nextInt(100) < 65) boosted.random()
                        else pool.random()
                talks.add(Talk(p.id, suspectTpl.random().format(t.pname), t.id, true))
            }
        }
        return talks
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
            val pick = run {
                // 名探偵本人は自分の予想に投票
                if (v.id == detectiveId && detectivePick >= 0) {
                    val dt = players[detectivePick]
                    if (dt.alive && cands.contains(dt)) return@run dt
                }
                // あなたの説得を採用
                val pers = persuaded[v.id]?.let { players[it] }?.takeIf { it.alive && cands.contains(it) }
                if (pers != null && Random.nextInt(100) < 75) return@run pers
                // 名探偵の予想に同調
                if (detectivePick >= 0 && v.id != detectiveId) {
                    val dt = players[detectivePick]
                    if (dt.alive && cands.contains(dt) && Random.nextInt(100) < 85) return@run dt
                }
                val black = cands.filter { publicBlack.contains(it.id) }
                if (black.isNotEmpty()) return@run black.random()
                val notWhite = cands.filter { !publicWhite.contains(it.id) && !seerClaimants.contains(it.id) }
                if (notWhite.isNotEmpty()) notWhite.random() else cands.random()
            }
            votes[v.id] = pick.id
        }
        lastVotes = votes

        // 「予想の段階」で人狼を当てたかを記録（投票結果に負けてもよい）
        newDetectiveJustNow = false
        for ((voterId, targetId) in votes) {
            if (voterId == humanId) continue
            if (players[voterId].role.isWolf) continue
            if (players[targetId].role.isWolf) {
                val st = (voteStreak[voterId] ?: 0) + 1
                voteStreak[voterId] = st
                if (st >= 2 && detectiveId < 0) {
                    detectiveId = voterId
                    newDetectiveJustNow = true
                }
            } else {
                voteStreak[voterId] = 0
            }
        }

        val tally = votes.values.groupingBy { it }.eachCount()
        val max = tally.values.maxOrNull() ?: 0
        val top = tally.filter { it.value == max }.keys.toList()
        val executed = players[top.random()]
        executed.alive = false
        lastExecuted = executed
        return executed
    }

    fun publishHumanMedium(): List<Talk> {
        val msgs = ArrayList<Talk>()
        for ((id, isWolf) in humanMediumResults) {
            val nm = players[id].pname
            val resText = if (isWolf) "人狼だった" else "人狼ではなかった"
            msgs.add(Talk(humanId, "霊能CO：$nm は $resText", id, isWolf))
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
        Animal.KOALA -> Color.parseColor("#A8B0BC")
        Animal.PENGUIN -> Color.parseColor("#4A5A70")
    }

    private fun irisColor(a: Animal) = when (a) {
        Animal.RABBIT -> Color.parseColor("#D95A73")
        Animal.FOX -> Color.parseColor("#7A4A20")
        Animal.CAT -> Color.parseColor("#3E9E60")
        Animal.DOG -> Color.parseColor("#5B4038")
        Animal.BEAR -> Color.parseColor("#4A342A")
        Animal.OWL -> Color.parseColor("#E8A020")
        Animal.SQUIRREL -> Color.parseColor("#6B4A2A")
        Animal.KOALA -> Color.parseColor("#4A3A32")
        Animal.PENGUIN -> Color.parseColor("#4A78B0")
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

        // ペンギンの白い顔まわり
        if (a == Animal.PENGUIN) {
            p.color = Color.parseColor("#F6F6F2")
            c.drawOval(RectF(cx - hr * 0.72f, hy - hr * 0.45f, cx + hr * 0.72f, hy + hr * 0.85f), p)
        }

        // コアラの明るいお腹まわり
        if (a == Animal.KOALA) {
            p.color = lighten(col)
            c.drawOval(RectF(cx - hr * 0.5f, hy + hr * 0.05f, cx + hr * 0.5f, hy + hr * 0.8f), p)
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
        if (a == Animal.KOALA) {
            // コアラの大きな鼻
            p.color = Color.parseColor("#4A4A52")
            c.drawOval(RectF(cx - hr * 0.2f, hy + hr * 0.02f, cx + hr * 0.2f, hy + hr * 0.52f), p)
            p.color = Color.argb(90, 255, 255, 255)
            c.drawOval(RectF(cx - hr * 0.13f, hy + hr * 0.08f, cx - hr * 0.02f, hy + hr * 0.24f), p)
            stroke.color = Color.parseColor("#4A4A52")
            stroke.strokeWidth = hr * 0.05f
            val m0 = RectF(cx - hr * 0.22f, hy + hr * 0.42f, cx + hr * 0.22f, hy + hr * 0.68f)
            c.drawArc(m0, 20f, 140f, false, stroke)
        } else if (a == Animal.OWL || a == Animal.PENGUIN) {
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
            Animal.KOALA -> {
                for (sgn in intArrayOf(-1, 1)) {
                    p.color = col
                    c.drawCircle(cx + sgn * hr * 0.82f, hy - hr * 0.55f, hr * 0.44f, p)
                    p.color = Color.parseColor("#E8B0C4")
                    c.drawCircle(cx + sgn * hr * 0.82f, hy - hr * 0.55f, hr * 0.24f, p)
                }
            }
            Animal.PENGUIN -> {
                // 耳なし。かわりに頭頂の小さな羽
                p.color = dark
                c.drawOval(RectF(cx - hr * 0.12f, hy - hr * 1.25f, cx + hr * 0.12f, hy - hr * 0.85f), p)
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
// まとめ相関図（キャラを円形配置し、疑いの矢印を🐺付きで描く）
// =====================================================

class SummaryView(context: Context, private val engine: GameEngine,
                  private val suspects: List<Talk>) : View(context) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val av = engine.alive()
        if (av.isEmpty()) return

        val cx = w / 2f
        val cy = h / 2f
        val charSize = w * 0.15f
        val radius = minOf(w, h) / 2f - charSize * 0.75f - w * 0.03f

        // 各キャラの座標（生存者のみを円形配置）
        val pos = HashMap<Int, FloatArray>()
        av.forEachIndexed { i, pl ->
            val ang = (-Math.PI / 2 + 2 * Math.PI * i / av.size)
            val x = cx + (radius * Math.cos(ang)).toFloat()
            val y = cy + (radius * Math.sin(ang)).toFloat()
            pos[pl.id] = floatArrayOf(x, y)
        }

        // 矢印（キャラの下に描く）
        for (t in suspects) {
            val a = pos[t.speakerId] ?: continue
            val b = pos[t.targetId] ?: continue
            drawArrow(c, a[0], a[1], b[0], b[1], charSize * 0.62f)
        }

        // キャラと名前
        tp.textSize = w * 0.042f
        for (pl in av) {
            val q = pos[pl.id] ?: continue
            CharacterArt.draw(c, pl.animal, q[0], q[1], charSize, pl.alive)
            tp.color = Color.WHITE
            tp.setShadowLayer(4f, 0f, 2f, Color.BLACK)
            val nm = if (pl.id == engine.humanId) "${pl.pname}★" else pl.pname
            c.drawText(nm, q[0], q[1] + charSize * 0.78f, tp)
            tp.clearShadowLayer()
        }
    }

    private fun drawArrow(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, margin: Float) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < margin * 2.2f) return
        val ux = dx / len
        val uy = dy / len
        val sx = x1 + ux * margin
        val sy = y1 + uy * margin
        val ex = x2 - ux * margin
        val ey = y2 - uy * margin

        p.style = Paint.Style.STROKE
        p.strokeWidth = width * 0.008f
        p.color = Color.parseColor("#FF7B7B")
        c.drawLine(sx, sy, ex, ey, p)

        // 矢じり
        p.style = Paint.Style.FILL
        val ah = width * 0.035f
        val px = -uy
        val py = ux
        val head = Path()
        head.moveTo(ex, ey)
        head.lineTo(ex - ux * ah + px * ah * 0.55f, ey - uy * ah + py * ah * 0.55f)
        head.lineTo(ex - ux * ah - px * ah * 0.55f, ey - uy * ah - py * ah * 0.55f)
        head.close()
        c.drawPath(head, p)

        // 疑い＝矢印の途中に狼マーク
        val mx = (sx + ex) / 2f
        val my = (sy + ey) / 2f
        tp.textSize = width * 0.055f
        tp.color = Color.WHITE
        c.drawText("🐺", mx, my + tp.textSize * 0.35f, tp)
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
    private var currentTalks = ArrayList<Talk>()
    private var predictedWolves = LinkedHashSet<Int>()   // 観戦前の人狼予想（2匹）
    private var predictionActive = false

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
        if (pl.id == engine.detectiveId) {
            val det = tv("🎩名探偵", 10f, true, Color.parseColor("#A8D8FF"))
            det.gravity = Gravity.CENTER
            cell.addView(det)
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

    // 9体を初期配置（id順・3×3）で固定表示。enabledのidだけタップ可能、他は薄く表示
    private fun charGridFixed(enabled: Set<Int>, sizeDp: Int,
                              onClick: ((Player) -> Unit)?): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        var row = LinearLayout(this)
        engine.players.forEachIndexed { i, pl ->
            if (i % 3 == 0) {
                row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER
                col.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            val cell = charCell(pl, sizeDp, null)
            if (onClick != null && enabled.contains(pl.id)) {
                cell.setOnClickListener { onClick(pl) }
            } else if (onClick != null) {
                cell.alpha = 0.35f
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(dp(3), dp(4), dp(3), dp(4))
            row.addView(cell, lp)
        }
        return col
    }

    // 村の状況カード（固定配置の全体表示＋現在のステータス）
    private fun statusCard(): LinearLayout {
        val e = engine
        val cd = card()
        cd.addView(tv("🏘️ 村の状況（配置は固定）", 14f, true, Color.parseColor("#FFE28A")))
        cd.addView(space(dp(4)))
        cd.addView(charGridFixed(emptySet(), 54, null))
        cd.addView(space(dp(6)))
        cd.addView(tv("生存: ${e.alive().size}匹 / ${GameEngine.N}匹　（${e.dayCount}日目）", 13f, true))
        val dead = e.players.filter { !it.alive }
        if (dead.isNotEmpty()) {
            cd.addView(tv("【脱落】" + dead.joinToString("、") { it.pname }, 13f, false,
                Color.parseColor("#9AA0B5")))
        }
        if (e.seerClaimants.isNotEmpty()) {
            cd.addView(tv("【占い師CO中】" +
                e.seerClaimants.joinToString("、") { e.players[it].pname }, 13f, false,
                Color.parseColor("#C9B6FF")))
        }
        if (e.publicBlack.isNotEmpty()) {
            cd.addView(tv("【黒判定】" + e.publicBlack.joinToString("、") { e.players[it].pname },
                13f, false, Color.parseColor("#FF9B9B")))
        }
        if (e.publicWhite.isNotEmpty()) {
            cd.addView(tv("【白判定】" + e.publicWhite.joinToString("、") { e.players[it].pname },
                13f, false, Color.parseColor("#A8E6A1")))
        }
        return cd
    }

    // ---------- やられた画面（観戦/予想/終了の選択） ----------

    private fun showHumanDead(cause: String, onContinue: () -> Unit) {
        val h = engine.human()
        val pn = panel()
        val cd = card()
        cd.addView(tv("💀 あなたはやられてしまった…", 20f, true, Color.parseColor("#FF9B9B")))
        cd.addView(centerChar(h, 100))
        val ct = tv(cause, 15f, true)
        ct.gravity = Gravity.CENTER
        cd.addView(ct)
        cd.addView(space(dp(12)))
        cd.addView(tv("このあとどうしますか？", 14f))
        cd.addView(space(dp(8)))
        cd.addView(btn("👀 観戦を続ける") { onContinue() })
        cd.addView(space(dp(8)))
        cd.addView(btn("🐺 人狼を予想して観戦", Color.parseColor("#7A4FD8")) {
            showWolfPredict(onContinue)
        })
        cd.addView(space(dp(8)))
        cd.addView(btn("🏁 終了（最終結果を見る）", Color.parseColor("#D8703D")) {
            fastForward()
        })
        pn.addView(cd)
        pn.addView(space(dp(14)))
        pn.addView(statusCard())   // 下の空きスペースに全体表示＋ステータス
        setScreen(pn)
    }

    // 残りをCPUだけで一気に進めて最終結果へ
    // nightNextAfterDeath: 処刑死なら次は夜(true)、襲撃死なら次は昼(false)
    private var nightNextAfterDeath = false

    private fun fastForward() {
        val e = engine
        var atNight = nightNextAfterDeath
        var guard = 0
        while (e.winner() == 0 && guard++ < 60) {
            if (atNight) {
                e.resolveNight(null, null, null)
                atNight = false
            } else {
                e.ensureSeerPhase(false)
                e.seerPhaseTalks()
                if (e.winner() != 0) break
                e.discussionTalks()
                e.runVote(null)
                atNight = true
            }
        }
        showGameOver(e.winner())
    }

    // ---------- 人狼予想（2匹選んで観戦） ----------

    private fun showWolfPredict(onContinue: () -> Unit) {
        val e = engine
        val pn = panel()
        val cd = card()
        cd.addView(tv("🐺 人狼はどの2匹？", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(space(dp(6)))
        cd.addView(tv("人狼だと思うキャラを2匹タップして選んでください。\n（すでに脱落したキャラも選べます）", 13f))
        cd.addView(space(dp(8)))

        val sel = tv(
            if (predictedWolves.isEmpty()) "選択中: なし"
            else "選択中: " + predictedWolves.joinToString("、") { e.players[it].pname },
            14f, true, Color.parseColor("#FFC9C9"))
        cd.addView(sel)
        cd.addView(space(dp(4)))

        val enabled = e.players.filter { it.id != e.humanId }.map { it.id }.toSet()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        var row = LinearLayout(this)
        e.players.forEachIndexed { i, pl ->
            if (i % 3 == 0) {
                row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER
                col.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            val cell = charCell(pl, 64, null)
            if (predictedWolves.contains(pl.id)) {
                val bg = GradientDrawable()
                bg.setColor(Color.argb(70, 255, 100, 100))
                bg.cornerRadius = dp(10).toFloat()
                bg.setStroke(dp(3), Color.parseColor("#FF6B6B"))
                cell.background = bg
            }
            if (enabled.contains(pl.id)) {
                cell.setOnClickListener {
                    if (predictedWolves.contains(pl.id)) predictedWolves.remove(pl.id)
                    else if (predictedWolves.size < 2) predictedWolves.add(pl.id)
                    showWolfPredict(onContinue)
                }
            } else {
                cell.alpha = 0.35f
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(dp(3), dp(4), dp(3), dp(4))
            row.addView(cell, lp)
        }
        cd.addView(col)
        cd.addView(space(dp(10)))
        if (predictedWolves.size == 2) {
            cd.addView(btn("この2匹で予想して観戦する", Color.parseColor("#D8703D")) {
                predictionActive = true
                onContinue()
            })
        } else {
            cd.addView(tv("あと ${2 - predictedWolves.size} 匹選んでください", 13f, false,
                Color.parseColor("#BFD0FF")))
        }
        cd.addView(space(dp(6)))
        cd.addView(btn("やっぱり戻る") {
            predictedWolves.clear()
            showHumanDead("……", onContinue)
        })
        pn.addView(cd)
        setScreen(pn)
    }

    // 答え合わせ画面
    private fun showPredictionResult() {
        val e = engine
        val actual = e.players.filter { it.role.isWolf }.map { it.id }.toSet()
        val correct = predictedWolves.count { actual.contains(it) }
        val pn = panel()
        val cd = card()
        cd.addView(tv("🔍 予想の答え合わせ", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(space(dp(8)))
        val big = tv("2匹中 $correct 匹 正解！", 26f, true,
            when (correct) {
                2 -> Color.parseColor("#A8E6A1")
                1 -> Color.parseColor("#FFE28A")
                else -> Color.parseColor("#FF9B9B")
            })
        big.gravity = Gravity.CENTER
        cd.addView(big)
        cd.addView(space(dp(12)))
        cd.addView(tv("【あなたの予想】", 14f, true))
        for (id in predictedWolves) {
            val pl = e.players[id]
            val hit = actual.contains(id)
            val rowL = LinearLayout(this)
            rowL.orientation = LinearLayout.HORIZONTAL
            rowL.gravity = Gravity.CENTER_VERTICAL
            rowL.addView(CharacterView(this, pl.animal, true),
                LinearLayout.LayoutParams(dp(48), dp(48)))
            rowL.addView(tv("  ${pl.pname}：" + (if (hit) "⭕ 人狼だった！" else "❌ 人狼ではなかった"),
                15f, true, if (hit) Color.parseColor("#A8E6A1") else Color.parseColor("#FF9B9B")))
            cd.addView(rowL)
            cd.addView(space(dp(4)))
        }
        cd.addView(space(dp(8)))
        cd.addView(tv("【本当の人狼】" + actual.joinToString("、") { e.players[it].pname },
            14f, true, Color.parseColor("#FF9B9B")))
        cd.addView(space(dp(14)))
        cd.addView(btn("タイトルへ", Color.parseColor("#D8703D")) { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- 自由会話（疑い話し合いの前） ----------

    private var freeTalksToday = ArrayList<Talk>()

    private fun startFreeTalk() {
        freeTalksToday = ArrayList(engine.freeTalks())
        showFreeTalk()
    }

    private fun showFreeTalk() {
        val e = engine
        val h = e.human()
        val canPersuade = h.alive && !e.persuadedToday
        val pn = panel()
        val cd = card()
        cd.addView(tv("☕ ${e.dayCount}日目 - 自由会話", 19f, true, Color.parseColor("#A8E6A1")))
        cd.addView(space(dp(4)))
        if (canPersuade) {
            cd.addView(tv("💡 キャラや吹き出しをタップすると、その相手に「人狼だと思うキャラ」をこっそり伝えられます（1日1回）。\n" +
                "うまくいけば投票で味方に。ただし相手が人狼だったら…？", 12f, false,
                Color.parseColor("#BFD0FF")))
        } else if (h.alive) {
            cd.addView(tv("（今日はもう説得しました）", 12f, false, Color.parseColor("#BFD0FF")))
        }
        cd.addView(space(dp(8)))

        if (freeTalksToday.isEmpty()) {
            cd.addView(tv("……今日はみんな静かだ。", 14f))
        }
        for (t in freeTalksToday) {
            val sp2 = e.players[t.speakerId]
            val tap: (() -> Unit)? =
                if (canPersuade && sp2.alive && sp2.id != e.humanId) {
                    { showPersuadeTarget(sp2) }
                } else null
            cd.addView(talkBubble(t, tap))
            cd.addView(space(dp(8)))
        }

        // 発言していない生存キャラもタップで説得できるように下に並べる
        if (canPersuade) {
            val spoke = freeTalksToday.map { it.speakerId }.toSet()
            val silent = e.alive().filter { it.id != e.humanId && !spoke.contains(it.id) }
            if (silent.isNotEmpty()) {
                cd.addView(tv("【無言のキャラ（タップで話しかける）】", 12f, true,
                    Color.parseColor("#BFD0FF")))
                cd.addView(charGrid(silent, 56, 4) { t -> showPersuadeTarget(t) })
            }
        }

        cd.addView(space(dp(14)))
        cd.addView(btn("疑いの話し合いへ", Color.parseColor("#D8703D")) {
            currentTalks.addAll(freeTalksToday)
            currentTalks.addAll(engine.discussionTalks())
            showDay()
        })
        pn.addView(cd)
        setScreen(pn)
    }

    private fun showPersuadeTarget(listener: Player) {
        val e = engine
        val pn = panel()
        val cd = card()
        cd.addView(tv("🤫 ${listener.pname} にこっそり伝える", 18f, true, Color.parseColor("#FFE28A")))
        cd.addView(centerChar(listener, 80))
        cd.addView(space(dp(6)))
        cd.addView(tv("「人狼だと思うキャラ」を選んでください", 14f))
        cd.addView(space(dp(8)))
        val cands = e.alive().filter { it.id != e.humanId && it.id != listener.id }
        cd.addView(charGridFixed(cands.map { it.id }.toSet(), 64) { target ->
            val reaction = e.persuade(listener, target)
            freeTalksToday.add(Talk(e.humanId,
                "（${listener.pname} に「${target.pname} が人狼だと思う」と伝えた）", target.id, false))
            freeTalksToday.add(reaction)
            showFreeTalk()
        })
        cd.addView(space(dp(10)))
        cd.addView(btn("やめておく") { showFreeTalk() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- 昼の会話：吹き出し ----------

    private fun talkBubble(t: Talk, onTap: (() -> Unit)? = null): LinearLayout {
        val sp = engine.players[t.speakerId]
        val isYou = t.speakerId == engine.humanId

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.TOP

        // 左：キャラクター
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.gravity = Gravity.CENTER_HORIZONTAL
        left.addView(CharacterView(this, sp.animal, sp.alive),
            LinearLayout.LayoutParams(dp(52), dp(52)))
        row.addView(left)

        // 右：吹き出し
        val bubble = LinearLayout(this)
        bubble.orientation = LinearLayout.VERTICAL
        bubble.setPadding(dp(12), dp(8), dp(12), dp(10))
        val bg = GradientDrawable()
        bg.setColor(if (isYou) Color.parseColor("#F0FFE8") else Color.WHITE)
        bg.cornerRadius = dp(12).toFloat()
        bg.setStroke(dp(2), if (isYou) Color.parseColor("#3D9E6B") else Color.parseColor("#B9C2D8"))
        bubble.background = bg

        // 名前だけ強調
        val nameText = if (isYou) "${sp.pname}（あなた）" else sp.pname
        val nm = tv(nameText, 15f, true,
            if (isYou) Color.parseColor("#2E7A4E") else Color.parseColor("#B05A2A"))
        bubble.addView(nm)
        bubble.addView(tv(t.text, 14f, false, Color.parseColor("#22283C")))

        val lp = LinearLayout.LayoutParams(0, -2, 1f)
        lp.setMargins(dp(8), 0, 0, 0)
        row.addView(bubble, lp)
        if (onTap != null) {
            row.setOnClickListener { onTap() }
            bubble.setOnClickListener { onTap() }
            left.setOnClickListener { onTap() }
        }
        return row
    }

    // ---------- まとめ（相関図ポップアップ） ----------

    private fun showSummaryDialog() {
        val e = engine
        val talks = currentTalks

        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val outer = card()
        val sc = ScrollView(this)
        sc.addView(outer)

        outer.addView(tv("📋 ${e.dayCount}日目のまとめ", 19f, true, Color.parseColor("#FFE28A")))
        outer.addView(space(dp(8)))
        outer.addView(tv("🐺付きの矢印 = 疑っている相手", 12f, false, Color.parseColor("#BFD0FF")))
        outer.addView(space(dp(6)))

        // 相関図（疑いの矢印のみ描画）
        val suspectTalks = talks.filter { it.suspect }
        val dm = resources.displayMetrics
        val side = (dm.widthPixels * 0.82f).toInt()
        outer.addView(SummaryView(this, e, suspectTalks),
            LinearLayout.LayoutParams(side, side))
        outer.addView(space(dp(10)))

        // その他は文字で簡単に
        val trust = talks.filter { !it.suspect }
        if (trust.isNotEmpty()) {
            outer.addView(tv("【信頼・白の発言】", 13f, true, Color.parseColor("#A8E6A1")))
            for (t in trust) {
                outer.addView(tv("・${e.players[t.speakerId].pname} → ${e.players[t.targetId].pname}（信頼）",
                    13f, false, Color.parseColor("#D8F5D2")))
            }
            outer.addView(space(dp(6)))
        }
        if (e.publicBlack.isNotEmpty()) {
            outer.addView(tv("【黒判定（占い）】" +
                e.publicBlack.joinToString("、") { e.players[it].pname },
                13f, true, Color.parseColor("#FF9B9B")))
        }
        if (e.publicWhite.isNotEmpty()) {
            outer.addView(tv("【白判定（占い）】" +
                e.publicWhite.joinToString("、") { e.players[it].pname },
                13f, true, Color.parseColor("#A8E6A1")))
        }
        if (e.seerClaimants.isNotEmpty()) {
            outer.addView(tv("【占い師CO中】" +
                e.seerClaimants.joinToString("、") { e.players[it].pname },
                13f, false, Color.parseColor("#C9B6FF")))
        }
        val dead = e.players.filter { !it.alive }
        if (dead.isNotEmpty()) {
            outer.addView(tv("【脱落】" + dead.joinToString("、") { it.pname },
                13f, false, Color.parseColor("#9AA0B5")))
        }

        outer.addView(space(dp(12)))
        outer.addView(btn("とじる") { d.dismiss() })

        d.setContentView(sc)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((dm.widthPixels * 0.94f).toInt(), -2)
        d.show()
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
        cd0.addView(charGrid(preview, 58, 3, null))
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
            "☀️ 1日目の昼\n" +
            "・まだ手がかりがないので、みんなの会話は勘だより。\n" +
            "・投票を行い、最も票が多かった人が処刑されます。", 14f))
        cd.addView(space(dp(8)))
        cd.addView(tv(
            "🌙 夜\n" +
            "・人狼が襲撃する相手を決めます。\n" +
            "・占い師が1人を占います。\n" +
            "・狩人が1人を護衛します。\n" +
            "・朝になると、襲撃された人（護衛成功なら0人）が判明します。", 14f))
        cd.addView(space(dp(8)))
        cd.addView(tv(
            "🔮 占い師フェーズ（2日目の朝から）\n" +
            "・占い師と主張する人が結果を発表します。\n" +
            "・人狼のうち1人が偽占い師として名乗り出ることも！\n" +
            "・偽物は人狼以外を「人狼」と言ったり、本物のマネをしたりします。\n" +
            "・名乗り出るのは最初だけ。途中から名乗り出ることはできません。\n" +
            "・占い師が2人いるとき、狩人はどちらかを必ず護衛します。", 14f))
        cd.addView(space(dp(8)))
        cd.addView(tv(
            "☕ 自由会話（話し合いの前）\n" +
            "・みんなが自由に発言します（無言のキャラも）。\n" +
            "・キャラをタップすると「人狼だと思う相手」をこっそり伝えられます（1日1回）。\n" +
            "・成功するとそのキャラが投票で同調してくれることも。\n" +
            "・ただし相手が人狼で、相方の人狼を伝えてしまうと…夜に狙われます！\n" +
            "・予想が公開の場で外れると信用を失い、説得しても無視されます。", 14f))
        cd.addView(space(dp(8)))
        cd.addView(tv(
            "🎩 名探偵\n" +
            "・投票で2回連続人狼を当てたキャラは「名探偵」に！（開票に負けてもOK）\n" +
            "・それ以降、みんなが名探偵の予想に同調して投票します。\n" +
            "・名探偵だけを説得できれば、村全体の票を動かせます。\n" +
            "・ただし人狼が名探偵を説得しようとすると、2回に1回バレます！", 14f))
        cd.addView(space(dp(8)))
        cd.addView(tv(
            "💬 昼\n" +
            "・生き残った全員で話し合います。\n" +
            "・占い師フェーズで怪しいと言われた人は疑われやすくなります。\n" +
            "・投票 → 処刑 → また夜になります。", 14f))
        cd.addView(space(dp(10)))
        cd.addView(tv("【勝利条件】", 15f, true))
        cd.addView(tv(
            "・村人チーム：すべての人狼を処刑すれば勝利。\n" +
            "・人狼チーム：人狼の人数が村人側の人数と同じになれば勝利。", 14f))
        cd.addView(space(dp(10)))
        cd.addView(tv("【構成（総数9人）】", 15f, true))
        cd.addView(tv("村人 ×4 / 占い師 ×1 / 霊能者 ×1 / 狩人 ×1 / 人狼 ×2", 14f))
        cd.addView(space(dp(14)))
        cd.addView(btn("タイトルへ戻る") { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- ゲーム開始 / 役職確認 ----------

    private fun startGame() {
        engine = GameEngine()
        engine.setup()
        currentTalks = ArrayList()
        predictedWolves = LinkedHashSet()
        predictionActive = false
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
        cd.addView(btn("1日目の昼へ", Color.parseColor("#D8703D")) {
            currentTalks = ArrayList()
            startFreeTalk()
        })
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

    // 夜画面の下の空きスペースに、人狼にやられたキャラを表示
    private fun addNightVictims(pn: LinearLayout) {
        val ids = engine.wolfVictimIds
        if (ids.isEmpty()) return
        pn.addView(space(dp(14)))
        val cd = card()
        cd.addView(tv("🐺 これまでに襲撃されたどうぶつ", 13f, true, Color.parseColor("#FFC9C9")))
        cd.addView(space(dp(4)))
        val victims = ids.map { engine.players[it] }
        cd.addView(charGrid(victims, 56, 4, null))
        pn.addView(cd)
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
        addNightVictims(pn)
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
        cd.addView(charGridFixed(cands.map { it.id }.toSet(), 72) { t -> finishNight(t, null, null) })
        pn.addView(cd)
        addNightVictims(pn)
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
        cd.addView(charGridFixed(cands.map { it.id }.toSet(), 72) { t -> finishNight(null, t, null) })
        pn.addView(cd)
        addNightVictims(pn)
        setScreen(pn)
    }

    private fun showHunterChoose() {
        val pn = panel()
        val cd = card()
        cd.addView(tv("🛡️ 夜 - 護衛", 20f, true, Color.parseColor("#A8D8FF")))
        cd.addView(space(dp(8)))
        val claimAlive = engine.seerClaimants.map { engine.players[it] }
            .filter { it.alive && it.id != engine.humanId }
        val cands: List<Player>
        if (claimAlive.size >= 2) {
            cd.addView(tv("占い師が2人名乗り出ています。どちらかを必ず護衛してください。", 14f, true,
                Color.parseColor("#FFE28A")))
            cands = claimAlive
        } else {
            cd.addView(tv("人狼の襲撃から守る相手を選んでください（自分以外）"))
            cands = engine.alive().filter { it.id != engine.humanId }
        }
        cd.addView(space(dp(10)))
        cd.addView(charGridFixed(cands.map { it.id }.toSet(), 72) { t -> finishNight(null, null, t) })
        pn.addView(cd)
        addNightVictims(pn)
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
        } else if (v != null && v.id == e.humanId) {
            // あなたが襲撃された → やられた画面へ
            cd.addView(btn("次へ", Color.parseColor("#5A4FD8")) {
                nightNextAfterDeath = false   // 次は昼フェーズから
                showHumanDead("昨夜、人狼に襲撃されてしまった…") { showSeerPhase() }
            })
        } else {
            cd.addView(btn("🔮 占い師フェーズへ") { showSeerPhase() })
        }
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- 占い師フェーズ（2日目の朝から・昼の前） ----------

    private fun showSeerPhase() {
        val e = engine
        val h = e.human()

        // 最初のフェーズで、あなたが生きている占い師なら「名乗り出るか」選べる
        if (!e.seerPhaseStarted && h.alive && h.role == Role.SEER) {
            val pn = panel()
            val cd = card()
            cd.addView(tv("🔮 占い師フェーズ", 20f, true, Color.parseColor("#C9B6FF")))
            cd.addView(space(dp(8)))
            cd.addView(tv("あなたは占い師です。名乗り出ますか？\n" +
                "・名乗り出ると結果を発表できますが、人狼に狙われやすくなります。\n" +
                "・ここで名乗り出ないと、以後名乗り出ることはできません。", 14f))
            cd.addView(space(dp(14)))
            cd.addView(btn("🔮 名乗り出る（CO）", Color.parseColor("#7A4FD8")) {
                e.ensureSeerPhase(true)
                renderSeerPhase()
            })
            cd.addView(space(dp(8)))
            cd.addView(btn("🤫 名乗り出ない（隠れる）") {
                e.ensureSeerPhase(false)
                renderSeerPhase()
            })
            pn.addView(cd)
            setScreen(pn)
            return
        }

        e.ensureSeerPhase(false)
        renderSeerPhase()
    }

    private fun renderSeerPhase() {
        val e = engine
        val talks = e.seerPhaseTalks()
        // フェーズの発言は昼の会話とまとめにも引き継ぐ
        currentTalks = ArrayList(talks)

        val pn = panel()
        val cd = card()
        cd.addView(tv("🔮 占い師フェーズ", 20f, true, Color.parseColor("#C9B6FF")))
        cd.addView(space(dp(6)))

        val claimAlive = e.seerClaimants.map { e.players[it] }.filter { it.alive }
        if (claimAlive.isEmpty()) {
            cd.addView(tv("……誰も占い師と名乗り出なかった。", 15f))
        } else {
            cd.addView(tv("占い師と主張しているのは ${claimAlive.size}人：", 14f))
            cd.addView(charGrid(claimAlive, 72, 3, null))
            cd.addView(space(dp(8)))
            if (talks.isEmpty()) {
                cd.addView(tv("今日は新しい発表はなかった。", 14f))
            } else {
                for (t in talks) {
                    cd.addView(talkBubble(t))
                    cd.addView(space(dp(8)))
                }
            }
            if (claimAlive.size >= 2) {
                cd.addView(tv("⚠️ 占い師が2人…どちらかは偽物（人狼）だ！", 13f, true,
                    Color.parseColor("#FF9B9B")))
            }
        }

        cd.addView(space(dp(14)))
        cd.addView(btn("自由会話へ") { startFreeTalk() })
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
        cd.addView(btn("📋 まとめを見る", Color.parseColor("#3D9E6B")) { showSummaryDialog() })
        cd.addView(space(dp(10)))

        // 会話：キャラを1列に並べて吹き出しで表示（画面ごと下にスクロール可能）
        for (t in currentTalks) {
            cd.addView(talkBubble(t))
            cd.addView(space(dp(8)))
        }

        // 占い師（人間）の手元の結果（COは朝の占い師フェーズでのみ可能）
        if (h.alive && h.role == Role.SEER && e.humanSeerResults.isNotEmpty()) {
            cd.addView(space(dp(6)))
            cd.addView(tv("【あなたの占い結果（非公開分含む）】", 13f, true, Color.parseColor("#C9B6FF")))
            for ((id, isw) in e.humanSeerResults) {
                val mark = if (e.publishedSeer.contains(id)) "（公開済）" else ""
                cd.addView(tv("・${e.players[id].pname}: " +
                    (if (isw) "人狼" else "人狼ではない") + mark, 13f, false,
                    Color.parseColor("#C9B6FF")))
            }
        }

        // 霊能者（人間）のCO
        if (h.alive && h.role == Role.MEDIUM && e.humanMediumResults.isNotEmpty()) {
            cd.addView(space(dp(6)))
            cd.addView(btn("👻 霊能結果を公開する（CO）", Color.parseColor("#4F7AD8")) {
                currentTalks.addAll(e.publishHumanMedium())
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
        cd.addView(charGridFixed(cands.map { it.id }.toSet(), 72) { t ->
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
        if (e.newDetectiveJustNow && e.detectiveId >= 0) {
            cd.addView(space(dp(8)))
            cd.addView(tv("🎩 ${e.players[e.detectiveId].pname} は2回連続で人狼を見抜いた！\n「名探偵」の称号を手に入れた！みんなが予想に同調するようになる。",
                14f, true, Color.parseColor("#A8D8FF")))
        }
        cd.addView(space(dp(16)))
        val w = e.winner()
        if (w != 0) {
            cd.addView(btn("結果を見る", Color.parseColor("#D8703D")) { showGameOver(w) })
        } else if (ex.id == e.humanId) {
            cd.addView(btn("次へ", Color.parseColor("#5A4FD8")) {
                nightNextAfterDeath = true   // 次は夜から
                showHumanDead("投票で処刑されてしまった…") { beginNight() }
            })
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
        if (predictionActive && predictedWolves.size == 2) {
            cd.addView(btn("🔍 人狼予想の答え合わせ", Color.parseColor("#7A4FD8")) {
                showPredictionResult()
            })
            cd.addView(space(dp(8)))
        }
        cd.addView(btn("もう一度あそぶ", Color.parseColor("#D8703D")) { startGame() })
        cd.addView(space(dp(8)))
        cd.addView(btn("タイトルへ") { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }
}

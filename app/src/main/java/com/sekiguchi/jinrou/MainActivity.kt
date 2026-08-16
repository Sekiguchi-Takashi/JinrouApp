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
import android.net.Uri
import android.widget.VideoView
import kotlin.random.Random

// =====================================================
// データ定義
// =====================================================

enum class Role(val jp: String, val desc: String, val isWolf: Boolean, val wolfSide: Boolean) {
    VILLAGER("村人", "特殊能力はありません。推理と投票で村を守りましょう。", false, false),
    MASON("共有者", "もう1人の共有者が誰かを知っています。お互いに人狼ではないと確認できます。", false, false),
    SEER("占い師", "毎晩1人を占い、人狼かどうかを知ることができます。", false, false),
    MEDIUM("霊能者", "処刑された人が人狼だったかどうかを知ることができます。", false, false),
    HUNTER("狩人", "毎晩1人を護衛し、人狼の襲撃から守ります。", false, false),
    WEREWOLF("人狼", "毎晩1人を襲撃します。仲間の人狼が誰か分かります。", true, true),
    MADMAN("狂人", "人間ですが人狼陣営です。占いでは「人狼ではない」と出ます。人狼を勝たせましょう。", false, true),
    FOX_SPIRIT("妖狐", "第三陣営。占われると死にますが、人狼の襲撃では死にません。最後まで生き残れば、あなただけの勝利です。", false, false)
}

enum class Animal(val jp: String, val persona: String) {
    RABBIT("うさぎ", "しっかり"), FOX("きつね", "クール"), CAT("ねこ", "きまぐれ"), DOG("いぬ", "げんき"),
    BEAR("くま", "おっとり"), OWL("ふくろう", "ものしり"), SQUIRREL("りす", "しんぱいしょう"),
    KOALA("こあら", "マイペース"), PENGUIN("ぺんぎん", "れいせい")
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
        // 人モードの名前（動物と同じ並び順で対応）
        val HUMAN_NAMES = listOf("篤史", "茜", "敏行", "真由美", "杏奈", "健一", "慎吾", "千鶴", "透")
        const val N = 9
    }

    val players = ArrayList<Player>()
    var humanId = -1
    var dayCount = 0

    // 難易度（0=やさしい / 1=ふつう / 2=むずかしい）。CPUの賢さに影響
    var difficulty = 1
    // 各キャラ（animal index）の好感度0-100。Activity側でSharedPreferencesから注入する
    val favByAnimal = HashMap<Int, Int>()
    fun favOf(pl: Player): Int = favByAnimal[pl.animal.ordinal] ?: 0
    val wolfSeerTargetRate get() = when (difficulty) { 0 -> 50; 1 -> 70; else -> 88 }   // 人狼が本物占い師を狙う率
    val fakeSeerRate get() = when (difficulty) { 0 -> 40; 1 -> 55; else -> 72 }         // 偽占い師が名乗り出る率
    val detectiveFollowRate get() = when (difficulty) { 0 -> 68; 1 -> 85; else -> 94 }  // 名探偵の予想に投票同調する率

    // プレイヤー（人間）だけが知っている情報
    val humanSeerResults = LinkedHashMap<Int, Boolean>()   // id -> 人狼か
    val humanMediumResults = LinkedHashMap<Int, Boolean>()
    var humanMediumNew: String? = null

    // 公開情報
    val publishedSeer = HashSet<Int>()
    val publicBlack = HashSet<Int>()
    // 霊能結果などで「人狼だった」と公に判明した死亡者（まとめ画面の狼マーク用）
    val revealedWolfDead = HashSet<Int>()
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
    val flags = LinkedHashSet<Int>()      // あなたが旗を立てた相手（怪しいと思う印）
    var mostSuspectedIds = ArrayList<Int>()  // 今ターン最も疑われている2キャラ

    // 生存人狼の数に応じた旗の最大数（人狼2匹→2本 / 1匹以下→1本）
    fun maxFlags(): Int = alive().count { it.role.isWolf }.coerceIn(1, 2)

    // 会話（疑い先）を集計して最も疑われている上位2キャラを求める
    fun computeMostSuspected(talks: List<Talk>) {
        val count = HashMap<Int, Int>()
        for (t in talks) {
            if (!t.suspect) continue
            if (t.targetId == t.speakerId) continue
            if (!players[t.targetId].alive) continue
            count[t.targetId] = (count[t.targetId] ?: 0) + 1
        }
        mostSuspectedIds = ArrayList(
            count.entries.sortedByDescending { it.value }.take(2)
                .filter { it.value > 0 }.map { it.key })
    }

    // AIアシスタント：見えている情報から推理のヒントを組み立てる
    // AI推論：いまの局面で人狼陣営がどれくらい有利か（0-100%）をざっくり推定
    fun wolfAdvantage(): Int {
        val living = alive()
        val wolves = living.count { it.role.isWolf }
        if (wolves == 0) return 0
        val villagerSide = living.count { !it.role.wolfSide && it.role != Role.FOX_SPIRIT }
        if (villagerSide <= 0) return 100
        var adv = wolves * 100 / (wolves + villagerSide)
        adv -= publicWhite.count { players[it].alive } * 4
        adv -= publicBlack.count { players[it].alive } * 3
        if (wolves >= villagerSide) adv += 20
        return adv.coerceIn(3, 97)
    }

    fun buildHints(): List<String> {
        val hints = ArrayList<String>()

        // 自己紹介による絞り込み（人狼は各グループに1人ずつ）
        if (introDone) {
            val saidAlive = players.filter { introDenied.contains(it.id) && it.alive }
            val silentAlive = players.filter { !introDenied.contains(it.id) && it.alive }
            hints.add("🗣️ 自己紹介では、人狼は「人狼ではないと言った人」に1人、「言わなかった人」に1人ひそんでいます。" +
                "いま生き残っているのは、言った人が${saidAlive.size}人、言わなかった人が${silentAlive.size}人。")
            if (saidAlive.size == 1 && saidAlive[0].alive) {
                hints.add("🎯 「人狼ではない」と言ったグループで生き残っているのは ${saidAlive[0].pname} だけ。" +
                    "そのグループの人狼がまだ生きているなら、この人が該当します。")
            }
            if (silentAlive.size == 1 && silentAlive[0].alive) {
                hints.add("🎯 「何も言わなかった」グループで生き残っているのは ${silentAlive[0].pname} だけ。" +
                    "そのグループの人狼がまだ生きているなら、この人が該当します。")
            }
        }
        val claimAlive = seerClaimants.map { players[it] }.filter { it.alive }

        // 占い師CO状況
        when {
            claimAlive.size >= 2 -> {
                hints.add("⚠️ 占い師が${claimAlive.size}人います（${claimAlive.joinToString("、") { it.pname }}）。" +
                    "どちらかは必ず偽物（人狼か狂人）です。両者の主張の食い違いに注目しましょう。")
                // 2人が同じ相手を白黒逆に言っていれば矛盾を指摘
                if (publicBlack.isNotEmpty() && publicWhite.isNotEmpty()) {
                    val both = publicBlack.intersect(publicWhite)
                    if (both.isNotEmpty()) {
                        hints.add("🔎 ${both.joinToString("、") { players[it].pname }} は「人狼」と「人狼ではない」の両方の判定が出ています。占い師のどちらかが嘘をついています。")
                    }
                }
            }
            claimAlive.size == 1 -> {
                hints.add("🔮 占い師を名乗っているのは ${claimAlive[0].pname} だけです。本物なら判定は信頼でき、偽物（人狼陣営）なら要注意。護衛や投票の判断材料にしましょう。")
            }
            else -> {
                hints.add("🤔 まだ誰も占い師と名乗り出ていません。占い師が隠れているか、初日に襲撃・処刑された可能性があります。")
            }
        }

        // 黒判定
        if (publicBlack.isNotEmpty()) {
            val names = publicBlack.filter { players[it].alive }.map { players[it].pname }
            if (names.isNotEmpty())
                hints.add("⚫ 「人狼」と占われているのは ${names.joinToString("、")}。ただし偽占い師による濡れ衣の可能性もあります。")
        }
        // 白判定
        if (publicWhite.isNotEmpty()) {
            val names = publicWhite.filter { players[it].alive }.map { players[it].pname }
            if (names.isNotEmpty())
                hints.add("⚪ 「人狼ではない」と占われているのは ${names.joinToString("、")}。本物の占い師の白なら信頼できます。")
        }

        // 最多疑われ
        if (mostSuspectedIds.isNotEmpty()) {
            val names = mostSuspectedIds.map { players[it].pname }
            hints.add("👀 今もっとも疑われているのは ${names.joinToString("、")}。流れに乗るか、別の視点で守るかを考えましょう。")
        }

        // 名探偵
        if (detectiveId >= 0 && players[detectiveId].alive) {
            hints.add("🎩 ${players[detectiveId].pname} は名探偵です。多くの村人が予想に同調します。違うと思うなら名探偵を説得すると流れを変えられます。")
        }

        // 残り人数の緊張度
        if (alive().size <= 5) {
            hints.add("⏳ 残り${alive().size}人。もう吊り間違いは許されません。確定情報を最優先に。")
        }

        if (hints.isEmpty()) hints.add("まだ手がかりが少ないです。まずは自由会話で情報を集めましょう。")
        return hints
    }

    val morningLog = ArrayList<String>()
    var lastVictim: Player? = null
    var lastExecuted: Player? = null
    var lastVotes: Map<Int, Int> = emptyMap()
    val wolfVictimIds = ArrayList<Int>()   // 人狼に襲撃されたキャラ（夜画面の下に表示）

    // 推理ノート用の履歴
    val noteTalks = ArrayList<Pair<Int, Talk>>()      // (dayCount, 発言) 疑い/信頼の記録
    val noteVotes = ArrayList<Triple<Int, Int, Int>>() // (dayCount, voterId, targetId) 投票履歴
    val noteAbilities = ArrayList<String>()            // 占い/霊媒/襲撃/処刑などの結果ログ

    fun logTalks(day: Int, talks: List<Talk>) {
        for (t in talks) {
            if (t.speakerId == t.targetId) continue
            noteTalks.add(day to t)
        }
    }

    fun human() = players[humanId]
    fun alive() = players.filter { it.alive }

    var masonRule = false   // Activity側から注入。ONなら村人2枠が共有者2になる
    var loversRule = false  // Activity側から注入。ONならランダム2人が恋人になる
    var humanCast = false   // Activity側から注入。ONなら登場キャラを人間の名前にする
    val loverIds = ArrayList<Int>()   // 恋人2人のid（loversRule時）

    fun isLover(p: Player) = loverIds.contains(p.id)
    fun loverPartner(p: Player): Player? =
        if (isLover(p)) players.firstOrNull { loverIds.contains(it.id) && it.id != p.id } else null

    // 恋人の後追い：片方が死んだらもう片方も死ぬ。死んだ相方の名前を返す（ログ用）
    fun applyHeartbreak(): Player? {
        if (!loversRule || loverIds.size < 2) return null
        val a = players[loverIds[0]]
        val b = players[loverIds[1]]
        if (a.alive != b.alive) {
            val survivor = if (a.alive) a else b
            survivor.alive = false
            return survivor
        }
        return null
    }

    // 共有者の相方（自分以外の共有者）
    fun masonPartner(p: Player): Player? =
        if (p.role == Role.MASON) players.firstOrNull { it.role == Role.MASON && it.id != p.id } else null

    fun setup() {
        players.clear()
        val animals = Animal.values()
        for (i in 0 until N) {
            val nm = if (humanCast) HUMAN_NAMES[i] else NAMES[i]
            players.add(Player(i, nm, animals[i]))
        }
        val villagerRole = if (masonRule) Role.MASON else Role.VILLAGER
        val roles = mutableListOf(
            villagerRole, villagerRole,
            Role.SEER, Role.MEDIUM, Role.HUNTER,
            Role.WEREWOLF, Role.WEREWOLF, Role.MADMAN,
            Role.FOX_SPIRIT
        )
        roles.shuffle()
        for (i in 0 until N) players[i].role = roles[i]
        humanId = Random.nextInt(N)
        dayCount = 1   // 1日目は昼の話し合いから始まる

        loverIds.clear()
        if (loversRule) {
            val ids = (0 until N).shuffled().take(2)
            loverIds.addAll(ids)
        }
    }

    // 0=続行 1=村人チーム勝利 2=人狼チーム勝利 3=妖狐勝利 4=恋人勝利
    fun winner(): Int {
        val living = alive()
        // 恋人勝利：生き残っているのが恋人2人だけになったら恋人の勝ち
        if (loversRule && loverIds.size == 2 && living.size == 2 &&
            living.all { loverIds.contains(it.id) }) {
            return 4
        }
        val foxAlive = living.any { it.role == Role.FOX_SPIRIT }
        val wolves = living.count { it.role.isWolf }
        // 妖狐は「人狼陣営でも村人陣営でもない」ので、生存者数の勝敗カウントから除外する
        val nonFox = living.filter { it.role != Role.FOX_SPIRIT }
        val villagerSide = nonFox.count { !it.role.wolfSide }
        // 決着条件
        if (wolves == 0) {
            // 人狼全滅：妖狐が生きていれば妖狐の勝ち、いなければ村人の勝ち
            return if (foxAlive) 3 else 1
        }
        if (wolves >= villagerSide) {
            // 人狼が村人側を制圧：妖狐が生きていれば妖狐の勝ち、いなければ人狼の勝ち
            return if (foxAlive) 3 else 2
        }
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
            seerClaimants.contains(realSeer.id) && Random.nextInt(100) < wolfSeerTargetRate) {
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
        flags.clear()
        mostSuspectedIds = ArrayList()

        val seer = players.firstOrNull { it.role == Role.SEER && it.alive }
        val hunter = players.firstOrNull { it.role == Role.HUNTER && it.alive }
        val wolvesAlive = players.filter { it.role.isWolf && it.alive }

        // 占い（妖狐を占うと呪殺＝死亡。結果は「人狼ではない」＝白）
        var foxCursed: Player? = null
        if (seer != null) {
            if (seer.id == humanId) {
                if (humanSeerTarget != null) {
                    humanSeerResults[humanSeerTarget.id] = humanSeerTarget.role.isWolf
                    noteAbilities.add("${dayCount}日目 🔮 あなたは ${humanSeerTarget.pname} を占った → " +
                        if (humanSeerTarget.role.isWolf) "人狼！" else "人狼ではない")
                    if (humanSeerTarget.role == Role.FOX_SPIRIT) foxCursed = humanSeerTarget
                }
            } else {
                val t = cpuSeerTarget(seer)
                if (t != null) {
                    cpuSeerResults[t.id] = t.role.isWolf
                    if (t.role == Role.FOX_SPIRIT) foxCursed = t
                }
            }
        }
        if (foxCursed != null) {
            foxCursed.alive = false
            noteAbilities.add("${dayCount}日目 🦊 ${foxCursed.pname} が占われて息絶えた（妖狐だった）")
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
                noteAbilities.add("${dayCount}日目 🛡️ 護衛成功。今夜の犠牲者はいなかった")
            } else if (target.role == Role.FOX_SPIRIT) {
                // 妖狐は人狼の襲撃では死なない
                noteAbilities.add("${dayCount}日目 🌫️ 人狼は襲撃したが、なぜか誰も倒れなかった…")
            } else {
                target.alive = false
                lastVictim = target
                wolfVictimIds.add(target.id)
                noteAbilities.add("${dayCount}日目 🐺 ${target.pname} が人狼に襲撃された")
            }
        } else {
            noteAbilities.add("${dayCount}日目 ☀️ 平和な朝を迎えた")
        }

        // 霊能結果（前日の処刑者）
        val medium = players.firstOrNull { it.role == Role.MEDIUM && it.alive }
        val exd = lastExecuted
        if (medium != null && exd != null) {
            val res = exd.role.isWolf
            val resText = if (res) "人狼だった！" else "人狼ではなかった"
            if (medium.id == humanId) {
                humanMediumResults[exd.id] = res
                if (res) revealedWolfDead.add(exd.id)
                humanMediumNew = "霊能結果：昨日処刑された ${exd.pname} は $resText"
            } else {
                if (res) revealedWolfDead.add(exd.id)   // 公開の場で人狼と判明
                morningLog.add("${medium.pname}「霊能結果：昨日処刑された ${exd.pname} は $resText」")
                // あなたが人狼だと主張した相手が、公開の場でシロと判明 → 信用を失う
                if (!res && humanTrust && humanClaims.contains(exd.id)) {
                    humanTrust = false
                    morningLog.add("${players[humanId].pname} の予想（${exd.pname} が人狼）は外れだった…みんなの信用を失ってしまった。")
                }
            }
        }
        // 恋人の後追い（襲撃・呪殺で片方が死んでいたら、もう片方も後を追う）
        val heartbroken = applyHeartbreak()
        if (heartbroken != null) {
            morningLog.add("💔 ${heartbroken.pname} は最愛の人を失い、後を追ってしまった…")
            noteAbilities.add("${dayCount}日目 💔 ${heartbroken.pname} が後追いで亡くなった（恋人）")
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
        // 人狼または狂人（人狼陣営のCPU）が1人、偽占い師として名乗り出ることがある
        val cpuFakers = players.filter {
            (it.role == Role.WEREWOLF || it.role == Role.MADMAN) && it.alive && it.id != humanId
        }
        if (cpuFakers.isNotEmpty() && Random.nextInt(100) < fakeSeerRate) {
            fakeSeerId = cpuFakers.random().id
            seerClaimants.add(fakeSeerId)
        }
        seerClaimants.shuffle()
    }

    // 今朝の占い師フェーズの発言（名乗り出た者だけが話す）
    // ---------- 自己紹介フェーズ（1日目のみ） ----------
    // 各キャラが番号順に名乗る。「自分は人狼ではない」と言う組と言わない組に分かれる。
    // 人狼2匹は必ず片方ずつ（言う組・言わない組）に分かれて紛れ込む。
    val introDenied = LinkedHashSet<Int>()   // 「人狼ではない」と明言したキャラのid
    var introDone = false

    // あなたの視点で「人狼だと確定している」キャラか？
    // （自分が人狼なら仲間、自分の占い結果で黒、霊能で人狼と判明、ゲーム終了後は全員）
    // あなたの視点で「人狼だと確定して“公然と”分かっている」キャラか？
    // マークは推理の答えを見せてしまうため、条件を厳しくする：
    //   ・決着後は全員公開
    //   ・処刑/襲撃で死亡し、霊能結果や答え合わせで人狼と判明した者
    //   ・あなた自身が占って人狼と出た者（自分だけが知る情報なので表示する）
    // 生存中の仲間（あなたが人狼のとき）は、役職確認画面で分かるためマークは付けない。
    fun isWolfConfirmed(p: Player, revealAll: Boolean): Boolean {
        if (revealAll) return p.role.isWolf
        // 霊能結果で「人狼だった」と判明した死亡者
        if (revealedWolfDead.contains(p.id)) return true
        // あなた自身の占いで黒と出た相手（自分だけが知る情報）
        if (humanSeerResults[p.id] == true) return true
        return false
    }

    fun buildIntroTalks(): List<Talk> {
        introDenied.clear()
        val all = players.toList()
        val wolves = all.filter { it.role.isWolf }
        // 「言う」人数は4人または5人
        val sayCount = if (Random.nextBoolean()) 4 else 5
        val sayers = LinkedHashSet<Int>()
        // 人狼を1匹だけ「言う組」に入れる（もう1匹は自動的に言わない組）
        if (wolves.isNotEmpty()) sayers.add(wolves.random().id)
        // 残りの枠を人狼以外からランダムに埋める
        val others = all.filter { !it.role.isWolf }.shuffled()
        for (p in others) {
            if (sayers.size >= sayCount) break
            sayers.add(p.id)
        }
        introDenied.addAll(sayers)

        val talks = ArrayList<Talk>()
        for (p in all) {   // id順（＝番号順）に自己紹介
            val says = sayers.contains(p.id)
            val text = if (says) {
                when (p.animal.persona) {
                    "げんき" -> "${p.pname}だよ！ぼくは人狼じゃない、ぜったいに！"
                    "クール" -> "${p.pname}だ。……先に言っておく。私は人狼ではない。"
                    "しんぱいしょう" -> "${p.pname}です…ぼ、ぼくは人狼じゃないです…信じてください…"
                    "れいせい" -> "${p.pname}です。断言します。私は人狼ではありません。"
                    "ものしり" -> "${p.pname}だ。結論から言おう、私は人狼ではない。"
                    else -> "${p.pname}です。わたしは人狼じゃありません！"
                }
            } else {
                when (p.animal.persona) {
                    "きまぐれ" -> "${p.pname}だニャ。ま、名前だけ言っておくね。"
                    "マイペース" -> "${p.pname}です…ん〜、あとはまだ言わないでおこうかな。"
                    "おっとり" -> "${p.pname}だよ。よろしくねぇ。"
                    "しっかり" -> "${p.pname}よ。今はまだ、様子を見させてもらうわ。"
                    else -> "${p.pname}です。まずはよろしく。"
                }
            }
            talks.add(Talk(p.id, text, p.id, false))
        }
        introDone = true
        return talks
    }

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
            val persona = p.animal.persona
            val r = Random.nextInt(100)
            when {
                r < 18 -> { /* あえて無言 */ }
                r < 40 -> talks.add(Talk(p.id, when (persona) {
                    "げんき" -> "ぼくは絶対に人狼じゃないよ！信じて！"
                    "クール" -> "……言っておくが、私は人狼ではない。"
                    "しんぱいしょう" -> "ぼ、ぼくは人狼じゃないよぉ…疑わないで…"
                    "れいせい" -> "私は人狼ではありません。冷静に考えてください。"
                    else -> "ぼくは人狼じゃないよ！ほんとだよ！"
                }, p.id, false))
                r < 58 -> {
                    val t = others.random()
                    talks.add(Talk(p.id, when (persona) {
                        "ものしり" -> "${t.pname} の発言には筋が通っている。信用できるね。"
                        "おっとり" -> "${t.pname} は…なんだか、いい人そうだねぇ。"
                        else -> "${t.pname} は信用できると思うんだ。"
                    }, t.id, false))
                }
                r < 76 -> {
                    val t = others.random()
                    talks.add(Talk(p.id, when (persona) {
                        "ものしり" -> "推理するに、${t.pname} が占い師の可能性が高い。"
                        "きまぐれ" -> "なんとなくだけど、${t.pname} が占い師な気がするニャ。"
                        else -> "${t.pname} が占い師なんじゃないかな？"
                    }, t.id, false))
                }
                else -> {
                    val t = others.random()
                    talks.add(Talk(p.id, when (persona) {
                        "しっかり" -> "狩人は ${t.pname} だと見ているわ。"
                        "マイペース" -> "狩人…？ん〜、${t.pname} かなぁ…"
                        else -> "狩人は ${t.pname} っぽい気がする。"
                    }, t.id, false))
                }
            }
        }
        if (detectiveId >= 0 && players[detectiveId].alive) {
            talks.add(0, Talk(detectiveId, "ふっふっふ…名探偵のぼくに任せたまえ！", detectiveId, false))
        }
        // 共有者CPUは相方が生きていれば「白確」と表明することがある
        for (p in av) {
            if (p.id == humanId || p.role != Role.MASON) continue
            val mate = masonPartner(p)
            if (mate != null && mate.alive && Random.nextInt(100) < 45) {
                talks.add(Talk(p.id, "ぼくと ${mate.pname} は共有者だよ。${mate.pname} は絶対に人狼じゃない！",
                    mate.id, false))
            }
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
        // 好感度が高いほど、あなたの説得を受け入れやすい（+0〜+25%）
        val favBonus = favOf(listener) / 4
        return if (Random.nextInt(100) < 70 + favBonus) {
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
                it.id != p.id && (p.role != Role.WEREWOLF || !it.role.isWolf) &&
                    (p.role != Role.MASON || it.role != Role.MASON)   // 共有者は相方を疑わない
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
            // 人狼・狂人（人狼陣営）は人狼に投票しない
            val candsW0 = if (v.role.wolfSide) cands0.filter { !it.role.isWolf } else cands0
            // 共有者は相方に投票しない
            val candsW = if (v.role == Role.MASON)
                candsW0.filter { it.role != Role.MASON } else candsW0
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
                // あなたが旗を立てた相手に、信用がある場合は弱く同調（40%）
                if (humanTrust && !v.role.isWolf) {
                    val flagged = flags.mapNotNull { players[it] }
                        .filter { it.alive && cands.contains(it) }
                    if (flagged.isNotEmpty() && Random.nextInt(100) < 40) return@run flagged.random()
                }
                // 名探偵の予想に同調
                if (detectivePick >= 0 && v.id != detectiveId) {
                    val dt = players[detectivePick]
                    if (dt.alive && cands.contains(dt) && Random.nextInt(100) < detectiveFollowRate) return@run dt
                }
                val black = cands.filter { publicBlack.contains(it.id) }
                if (black.isNotEmpty()) return@run black.random()
                val notWhite = cands.filter { !publicWhite.contains(it.id) && !seerClaimants.contains(it.id) }
                if (notWhite.isNotEmpty()) notWhite.random() else cands.random()
            }
            // 好感度が高いキャラは、あなたに入れそうになっても別の相手に振り替える（かばう）
            var finalPick = pick
            if (finalPick.id == humanId && !v.role.wolfSide) {
                val favor = favOf(v)
                if (favor > 0 && Random.nextInt(100) < favor / 2) {   // 好感度100で最大50%かばう
                    val alt = cands.filter { it.id != humanId }
                        .filter { !publicWhite.contains(it.id) && !seerClaimants.contains(it.id) }
                    val altPick = alt.randomOrNull() ?: cands.filter { it.id != humanId }.randomOrNull()
                    if (altPick != null) finalPick = altPick
                }
            }
            votes[v.id] = finalPick.id
        }
        lastVotes = votes

        // 「予想の段階」で人狼を当てたかを記録（投票結果に負けてもよい）
        newDetectiveJustNow = false
        for ((voterId, targetId) in votes) {
            if (voterId == humanId) continue
            if (players[voterId].role.wolfSide) continue   // 人狼・狂人は名探偵になれない
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
        // 推理ノートに記録
        for ((voterId, targetId) in votes) noteVotes.add(Triple(dayCount, voterId, targetId))
        noteAbilities.add("${dayCount}日目 ⚖️ 投票の結果、${executed.pname} が処刑された")
        // 恋人の後追い（処刑で片方が死んだら、もう片方も後を追う）
        val heartbroken = applyHeartbreak()
        if (heartbroken != null) {
            noteAbilities.add("${dayCount}日目 💔 ${heartbroken.pname} が後追いで亡くなった（恋人）")
        }
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
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

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

    // emotion: 0=通常 1=喜び 2=悲しみ / t: アニメ位相(0..1) / hop: 上下弾み / lean: 左右傾き
    fun draw(c: Canvas, a: Animal, cx0: Float, cy0: Float, size: Float, alive: Boolean,
             emotion: Int = 0, t: Float = 0f, hop: Float = 0f, lean: Float = 0f) {
        val cx = cx0
        val cy = cy0 - hop
        val hr = size * 0.27f
        val hy = cy - size * 0.06f
        val col = bodyColor(a)
        val dark = darken(col)
        p.style = Paint.Style.FILL

        c.save()
        // 立体的な動き：傾き＋弾みに合わせた横方向のスカッシュ＆ストレッチ
        c.rotate(lean, cx, cy)
        val sy = 1f + hop / (size * 0.9f) * 0.6f    // 上昇時は縦に伸び
        val sx = 2f - sy                             // 横に縮む（体積保存風）
        c.scale(sx.coerceIn(0.85f, 1.15f), sy.coerceIn(0.85f, 1.2f), cx, hy + hr)

        // リスのしっぽ（喜ぶと揺れる）
        if (a == Animal.SQUIRREL) {
            c.save()
            if (emotion == 1) c.rotate(kotlin.math.sin(t * 3.14f) * 12f, cx + hr, hy + hr)
            p.color = Color.parseColor("#C1683A")
            val tail = RectF(cx + hr * 0.5f, hy - hr * 0.4f, cx + hr * 1.7f, hy + hr * 1.8f)
            c.drawOval(tail, p)
            p.color = Color.parseColor("#E08A55")
            c.drawOval(
                RectF(tail.left + hr * 0.25f, tail.top + hr * 0.3f,
                      tail.right - hr * 0.15f, tail.bottom - hr * 0.3f), p)
            c.restore()
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

        // アニメ風の大きな目（感情で形が変わる）
        val eyeY = hy - hr * 0.05f
        val eyeDX = hr * 0.42f
        val ew = hr * 0.30f
        val eh = hr * 0.42f
        for (sgn in intArrayOf(-1, 1)) {
            val ex = cx + sgn * eyeDX
            if (!alive) {
                stroke.color = Color.DKGRAY
                stroke.strokeWidth = hr * 0.09f
                c.drawLine(ex - ew, eyeY - eh * 0.6f, ex + ew, eyeY + eh * 0.6f, stroke)
                c.drawLine(ex + ew, eyeY - eh * 0.6f, ex - ew, eyeY + eh * 0.6f, stroke)
            } else if (emotion == 1) {
                // 喜び：^ 形のにっこり目
                stroke.color = Color.parseColor("#3A2A28")
                stroke.strokeWidth = hr * 0.11f
                stroke.strokeCap = Paint.Cap.ROUND
                val arc = RectF(ex - ew, eyeY - eh * 0.2f, ex + ew, eyeY + eh * 0.8f)
                c.drawArc(arc, 200f, 140f, false, stroke)
            } else if (emotion == 2) {
                // 悲しみ：小さめの目＋下がり眉＋涙
                p.color = Color.WHITE
                c.drawOval(RectF(ex - ew, eyeY - eh * 0.7f, ex + ew, eyeY + eh * 0.9f), p)
                p.color = irisColor(a)
                c.drawOval(RectF(ex - ew * 0.8f, eyeY - eh * 0.2f, ex + ew * 0.8f, eyeY + eh * 0.85f), p)
                p.color = Color.BLACK
                c.drawOval(RectF(ex - ew * 0.42f, eyeY + eh * 0.1f, ex + ew * 0.42f, eyeY + eh * 0.7f), p)
                stroke.color = Color.parseColor("#3A2A28")
                stroke.strokeWidth = hr * 0.07f
                stroke.strokeCap = Paint.Cap.ROUND
                c.drawLine(ex - ew * 1.1f, eyeY - eh * 0.7f, ex + ew * 0.2f, eyeY - eh * 1.05f, stroke)
                // 涙（位相で落ちる）
                p.color = Color.parseColor("#8FD0FF")
                val tearY = eyeY + eh * 0.9f + (t % 1f) * hr * 0.7f
                c.drawCircle(ex + sgn * ew * 0.3f, tearY, ew * 0.28f, p)
            } else if (emotion == 3) {
                // 怒り：つり上がった目＋怒り眉
                p.color = Color.WHITE
                c.drawOval(RectF(ex - ew, eyeY - eh * 0.7f, ex + ew, eyeY + eh * 0.85f), p)
                p.color = irisColor(a)
                c.drawOval(RectF(ex - ew * 0.8f, eyeY - eh * 0.5f, ex + ew * 0.8f, eyeY + eh * 0.85f), p)
                p.color = Color.BLACK
                c.drawOval(RectF(ex - ew * 0.45f, eyeY - eh * 0.15f, ex + ew * 0.45f, eyeY + eh * 0.6f), p)
                stroke.color = Color.parseColor("#3A2A28")
                stroke.strokeWidth = hr * 0.09f
                stroke.strokeCap = Paint.Cap.ROUND
                // 内側が下がった怒り眉
                c.drawLine(ex - ew * 1.0f, eyeY - eh * 1.15f, ex + ew * 0.5f, eyeY - eh * 0.7f, stroke)
            } else if (emotion == 4) {
                // 恐怖：見開いた目＋小さく震える瞳
                p.color = Color.WHITE
                c.drawOval(RectF(ex - ew * 1.1f, eyeY - eh * 1.1f, ex + ew * 1.1f, eyeY + eh * 1.1f), p)
                p.color = irisColor(a)
                val shake = kotlin.math.sin(t * 40f) * ew * 0.12f
                c.drawOval(RectF(ex - ew * 0.5f + shake, eyeY - eh * 0.5f,
                                 ex + ew * 0.5f + shake, eyeY + eh * 0.5f), p)
                p.color = Color.BLACK
                c.drawOval(RectF(ex - ew * 0.28f + shake, eyeY - eh * 0.28f,
                                 ex + ew * 0.28f + shake, eyeY + eh * 0.28f), p)
            } else if (emotion == 5) {
                // 焦り：ぐるぐる（うずまき目）
                stroke.color = Color.parseColor("#3A2A28")
                stroke.strokeWidth = hr * 0.07f
                stroke.style = Paint.Style.STROKE
                for (k in 1..3) {
                    val rr = ew * (0.3f + k * 0.22f)
                    c.drawArc(RectF(ex - rr, eyeY - rr, ex + rr, eyeY + rr),
                        k * 90f + t * 120f, 260f, false, stroke)
                }
                stroke.style = Paint.Style.STROKE
                p.style = Paint.Style.FILL
            } else if (emotion == 6) {
                // 安心：ゆるやかに閉じた目（下弧）
                stroke.color = Color.parseColor("#3A2A28")
                stroke.strokeWidth = hr * 0.10f
                stroke.strokeCap = Paint.Cap.ROUND
                val arc = RectF(ex - ew, eyeY - eh * 0.5f, ex + ew, eyeY + eh * 0.5f)
                c.drawArc(arc, 200f, 140f, false, stroke)
            } else {
                p.color = Color.WHITE
                c.drawOval(RectF(ex - ew, eyeY - eh, ex + ew, eyeY + eh), p)
                p.color = irisColor(a)
                c.drawOval(RectF(ex - ew * 0.8f, eyeY - eh * 0.75f, ex + ew * 0.8f, eyeY + eh * 0.9f), p)
                p.color = Color.BLACK
                c.drawOval(RectF(ex - ew * 0.45f, eyeY - eh * 0.4f, ex + ew * 0.45f, eyeY + eh * 0.6f), p)
                p.color = Color.WHITE
                c.drawCircle(ex - ew * 0.25f, eyeY - eh * 0.3f, ew * 0.24f, p)
                c.drawCircle(ex + ew * 0.3f, eyeY + eh * 0.25f, ew * 0.12f, p)
            }
        }
        stroke.strokeCap = Paint.Cap.BUTT

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
            when (emotion) {
                1 -> {
                    // 喜び：大きく開いた口
                    p.color = Color.parseColor("#B5473F")
                    c.drawArc(RectF(cx - hr * 0.3f, hy + hr * 0.36f, cx + hr * 0.3f, hy + hr * 0.78f),
                        0f, 180f, true, p)
                }
                2 -> {
                    // 悲しみ：への字
                    stroke.strokeWidth = hr * 0.06f
                    c.drawArc(RectF(cx - hr * 0.22f, hy + hr * 0.52f, cx + hr * 0.22f, hy + hr * 0.82f),
                        200f, 140f, false, stroke)
                }
                3 -> {
                    // 怒り：食いしばった口（ギザ）
                    stroke.strokeWidth = hr * 0.06f
                    val my = hy + hr * 0.58f
                    var x0 = cx - hr * 0.28f
                    val step = hr * 0.14f
                    var up = true
                    while (x0 < cx + hr * 0.28f) {
                        c.drawLine(x0, my + (if (up) -hr * 0.06f else hr * 0.06f),
                                   x0 + step, my + (if (up) hr * 0.06f else -hr * 0.06f), stroke)
                        x0 += step; up = !up
                    }
                }
                4 -> {
                    // 恐怖：小さく開いた口（わなわな）
                    p.color = Color.parseColor("#7A3038")
                    val q = kotlin.math.abs(kotlin.math.sin(t * 20f))
                    c.drawOval(RectF(cx - hr * 0.12f, hy + hr * 0.5f,
                                     cx + hr * 0.12f, hy + hr * 0.5f + hr * (0.12f + q * 0.1f)), p)
                }
                5 -> {
                    // 焦り：波打つ口
                    stroke.strokeWidth = hr * 0.05f
                    val my = hy + hr * 0.56f
                    val path = Path()
                    path.moveTo(cx - hr * 0.26f, my)
                    path.cubicTo(cx - hr * 0.1f, my - hr * 0.12f,
                                 cx + hr * 0.1f, my + hr * 0.12f, cx + hr * 0.26f, my)
                    c.drawPath(path, stroke)
                }
                6 -> {
                    // 安心：おだやかな微笑み
                    stroke.strokeWidth = hr * 0.05f
                    c.drawArc(RectF(cx - hr * 0.2f, hy + hr * 0.34f, cx + hr * 0.2f, hy + hr * 0.6f),
                        15f, 150f, false, stroke)
                }
                else -> {
                    val m = RectF(cx - hr * 0.22f, hy + hr * 0.32f, cx + hr * 0.22f, hy + hr * 0.62f)
                    c.drawArc(m, 20f, 140f, false, stroke)
                }
            }
        }

        // ほっぺ（喜ぶと赤く大きく）
        p.color = if (emotion == 1) Color.argb(150, 255, 130, 150) else Color.argb(90, 255, 120, 140)
        val chW = if (emotion == 1) hr * 0.5f else hr * 0.4f
        c.drawOval(RectF(cx - hr * 0.9f, hy + hr * 0.12f, cx - hr * 0.9f + chW, hy + hr * 0.40f), p)
        c.drawOval(RectF(cx + hr * 0.9f - chW, hy + hr * 0.12f, cx + hr * 0.9f, hy + hr * 0.40f), p)

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

        // 感情エフェクト
        if (alive) {
            when (emotion) {
                1 -> {
                    p.color = Color.parseColor("#FFD450")
                    tp.textSize = hr * 0.7f
                    c.drawText("♪", cx + hr * 1.2f, hy - hr * 0.6f + kotlin.math.sin(t * 6.2832f) * hr * 0.2f, tp)
                }
                3 -> {
                    // 怒りマーク（青筋）
                    tp.textSize = hr * 0.6f
                    c.drawText("💢", cx + hr * 0.95f, hy - hr * 0.7f, tp)
                }
                4 -> {
                    // 恐怖：青ざめ＋汗
                    p.color = Color.argb(120, 150, 200, 255)
                    c.drawCircle(cx - hr * 1.0f, hy + hr * 0.1f, hr * 0.18f, p)
                    p.color = Color.parseColor("#8FD0FF")
                    val sy = hy - hr * 0.4f + (t % 1f) * hr * 0.8f
                    c.drawCircle(cx + hr * 1.0f, sy, hr * 0.13f, p)
                }
                5 -> {
                    // 焦り：飛び散る汗
                    p.color = Color.parseColor("#8FD0FF")
                    for (k in 0..2) {
                        val ang = t * 6.2832f + k * 2.1f
                        c.drawCircle(cx + hr * (1.0f + 0.1f * k) * kotlin.math.cos(ang),
                                     hy - hr * 0.7f + hr * 0.15f * kotlin.math.sin(ang), hr * 0.09f, p)
                    }
                }
                6 -> {
                    // 安心：ふわっと音符
                    p.color = Color.parseColor("#A8E6A1")
                    tp.textSize = hr * 0.5f
                    c.drawText("♬", cx + hr * 1.1f, hy - hr * 0.5f + kotlin.math.sin(t * 3.14f) * hr * 0.15f, tp)
                }
            }
        }

        c.restore()

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

class CharacterView(context: Context, private val animal: Animal, private var aliveFlag: Boolean)
    : View(context) {

    // 人モード：trueなら動物の手描きではなく人間の立ち絵を表示する
    var humanMode: Boolean = false
        set(v) { field = v; invalidate() }

    private var humanBitmap: android.graphics.Bitmap? = null
    private var loadedKey: String? = null
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // 表情に対応する画像を読み込む（無い表情はnormalにフォールバック）
    private fun loadHuman() {
        val idx = animal.ordinal + 1
        val emoName = when (emotion) {
            1 -> "happy"; 2 -> "sad"; 3 -> "angry"
            4 -> "scared"; 5 -> "panic"; 6 -> "relieved"
            else -> "normal"
        }
        val key = "human${idx}_$emoName"
        if (loadedKey == key && humanBitmap != null) return
        var resId = resources.getIdentifier(key, "drawable", context.packageName)
        if (resId == 0) {
            // その表情が無ければ通常表情で代用
            resId = resources.getIdentifier("human${idx}_normal", "drawable", context.packageName)
        }
        if (resId != 0) {
            humanBitmap = android.graphics.BitmapFactory.decodeResource(resources, resId)
            loadedKey = key
        }
    }

    // emotion: 0=通常(アイドル) 1=喜び 2=悲しみ 3=怒り 4=恐怖 5=焦り 6=安心
    var emotion: Int = 0
        set(v) { field = v; loadedKey = null; if (v != 0) startAnim() }

    private var phase = 0f
    private var running = false
    private val frame = object : Runnable {
        override fun run() {
            phase += 0.02f
            if (phase > 1000f) phase = 0f
            invalidate()
            if (running) postOnAnimation(this)
        }
    }

    private fun startAnim() {
        if (!running) { running = true; postOnAnimation(frame) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        postOnAnimation(frame)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height).toFloat()
        val two = (Math.PI * 2).toFloat()
        var hop = 0f
        var lean = 0f
        when (emotion) {
            1 -> {  // 喜び：ゆっくりぴょんぴょん跳ねる
                val j = kotlin.math.abs(kotlin.math.sin(phase * two * 0.8f))
                hop = j * s * 0.14f
                lean = kotlin.math.sin(phase * two * 0.8f) * 4f
            }
            2 -> {  // 悲しみ：うつむいてゆっくり揺れる
                hop = -s * 0.02f + kotlin.math.sin(phase * two * 0.4f) * s * 0.01f
                lean = kotlin.math.sin(phase * two * 0.35f) * 3f
            }
            3 -> {  // 怒り：小刻みにプルプル震える
                hop = kotlin.math.abs(kotlin.math.sin(phase * two * 3.0f)) * s * 0.02f
                lean = kotlin.math.sin(phase * two * 8.0f) * 2.5f
            }
            4 -> {  // 恐怖：後ずさるように細かく震える
                hop = kotlin.math.sin(phase * two * 12f) * s * 0.012f
                lean = kotlin.math.sin(phase * two * 10f) * 2f
            }
            5 -> {  // 焦り：せかせか左右に揺れる
                lean = kotlin.math.sin(phase * two * 4.0f) * 6f
                hop = kotlin.math.abs(kotlin.math.sin(phase * two * 2.0f)) * s * 0.03f
            }
            6 -> {  // 安心：ゆったり大きく呼吸
                hop = kotlin.math.sin(phase * two * 0.35f) * s * 0.04f
                lean = kotlin.math.sin(phase * two * 0.3f) * 1.5f
            }
            else -> {  // アイドル：呼吸のような小さな上下＋わずかな傾き
                hop = kotlin.math.sin(phase * two * 0.6f) * s * 0.03f
                lean = kotlin.math.sin(phase * two * 0.45f) * 2f
            }
        }
        if (humanMode) {
            loadHuman()
            val bmp = humanBitmap
            if (bmp != null) {
                canvas.save()
                // 跳ねても頭が切れないよう、上下に余白を残して少し小さめに描く
                val maxHop = s * 0.16f
                val draw = s - maxHop           // 立ち絵の描画サイズ
                val baseY = height / 2f + maxHop * 0.5f   // 下寄せして上に逃げしろを作る
                canvas.translate(width / 2f, baseY - hop)
                canvas.rotate(lean)
                val dst = RectF(-draw / 2f, -draw / 2f, draw / 2f, draw / 2f)
                // 死亡時は暗く半透明にする
                if (!aliveFlag) {
                    bmpPaint.alpha = 110
                    bmpPaint.colorFilter = android.graphics.ColorMatrixColorFilter(
                        android.graphics.ColorMatrix().apply { setSaturation(0f) })
                } else {
                    bmpPaint.alpha = 255
                    bmpPaint.colorFilter = null
                }
                canvas.drawBitmap(bmp, null, dst, bmpPaint)
                canvas.restore()
                return
            }
        }
        CharacterArt.draw(canvas, animal, width / 2f, height / 2f, s, aliveFlag,
            emotion, phase, hop, lean)
    }
}

// =====================================================
// まとめ相関図（キャラを円形配置し、疑いの矢印を🐺付きで描く）
// =====================================================

class SummaryView(context: Context, private val engine: GameEngine,
                  private val suspects: List<Talk>,
                  private val humanMode: Boolean = false,
                  private val revealAll: Boolean = false) : View(context) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val humanCache = HashMap<Int, android.graphics.Bitmap?>()

    private var wolfMark: android.graphics.Bitmap? = null
    private fun wolfBmp(): android.graphics.Bitmap? {
        if (wolfMark == null) {
            val id = resources.getIdentifier("wolf_mark", "drawable", context.packageName)
            if (id != 0) wolfMark = android.graphics.BitmapFactory.decodeResource(resources, id)
        }
        return wolfMark
    }

    // 人モードの立ち絵（通常表情）を取得
    private fun humanBmp(animalOrdinal: Int): android.graphics.Bitmap? {
        if (humanCache.containsKey(animalOrdinal)) return humanCache[animalOrdinal]
        val resId = resources.getIdentifier(
            "human${animalOrdinal + 1}_normal", "drawable", context.packageName)
        val bmp = if (resId != 0)
            android.graphics.BitmapFactory.decodeResource(resources, resId) else null
        humanCache[animalOrdinal] = bmp
        return bmp
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // 生存者に加えて「人狼と判明している死亡者」も残して表示する
        val deadWolves = engine.players.filter {
            !it.alive && engine.isWolfConfirmed(it, revealAll)
        }
        val av = engine.alive() + deadWolves
        if (av.isEmpty()) return

        val cx = w / 2f
        val cy = h / 2f
        val charSize = w * 0.15f
        val radius = minOf(w, h) / 2f - charSize * 0.75f - w * 0.03f

        // 各キャラの座標（円形配置）
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
            // あなた自身は赤い太線の丸で囲む
            if (pl.id == engine.humanId) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = charSize * 0.10f
                p.color = Color.parseColor("#FF3B30")
                c.drawCircle(q[0], q[1], charSize * 0.62f, p)
                p.style = Paint.Style.FILL
            }
            val bmp = if (humanMode) humanBmp(pl.animal.ordinal) else null
            if (bmp != null) {
                val half = charSize * 0.6f
                val dst = RectF(q[0] - half, q[1] - half, q[0] + half, q[1] + half)
                if (!pl.alive) {
                    bmpPaint.alpha = 110
                    bmpPaint.colorFilter = android.graphics.ColorMatrixColorFilter(
                        android.graphics.ColorMatrix().apply { setSaturation(0f) })
                } else {
                    bmpPaint.alpha = 255
                    bmpPaint.colorFilter = null
                }
                c.drawBitmap(bmp, null, dst, bmpPaint)
            } else {
                CharacterArt.draw(c, pl.animal, q[0], q[1], charSize, pl.alive)
            }
            // 人狼と判明しているキャラには狼マークを重ねる
            if (engine.isWolfConfirmed(pl, revealAll)) {
                val wb = wolfBmp()
                if (wb != null) {
                    val ws = charSize * 0.62f
                    val wdst = RectF(q[0] - ws / 2f, q[1] + charSize * 0.10f,
                                     q[0] + ws / 2f, q[1] + charSize * 0.10f + ws)
                    bmpPaint.alpha = 255
                    bmpPaint.colorFilter = null
                    c.drawBitmap(wb, null, wdst, bmpPaint)
                }
            }
            tp.color = if (!pl.alive) Color.parseColor("#FFB0B0") else Color.WHITE
            tp.setShadowLayer(4f, 0f, 2f, Color.BLACK)
            var nm = if (pl.id == engine.humanId) "${pl.pname}★" else pl.pname
            if (!pl.alive) nm += "†"
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

class TownView(context: Context, private val isNight: Boolean, private val theme: String = "normal") : View(context) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    // テーマ別の色（昼/夜それぞれ）
    private fun cSkyTop() = when (theme) {
        "dusk" -> if (isNight) "#3A1E4A" else "#F49B6B"
        "snow" -> if (isNight) "#1A2A4A" else "#BFE0F2"
        "sakura" -> if (isNight) "#2A1830" else "#F7C8DD"
        else -> if (isNight) "#0B1035" else "#7EC8F2"
    }
    private fun cSkyBot() = when (theme) {
        "dusk" -> if (isNight) "#5A2E5A" else "#FBD9A8"
        "snow" -> if (isNight) "#2A3A5A" else "#EAF6FC"
        "sakura" -> if (isNight) "#4A2A50" else "#FCE4EF"
        else -> if (isNight) "#25306B" else "#CDEDFB"
    }
    private fun cHill() = when (theme) {
        "dusk" -> if (isNight) "#3A2450" else "#C77A5A"
        "snow" -> if (isNight) "#2A3A5A" else "#DCEAF2"
        "sakura" -> if (isNight) "#3A2445" else "#D99CBE"
        else -> if (isNight) "#1B2450" else "#9CCB86"
    }
    private fun cGround() = when (theme) {
        "dusk" -> if (isNight) "#2E2438" else "#8A6A4A"
        "snow" -> if (isNight) "#3A4A5A" else "#E8F2F8"
        "sakura" -> if (isNight) "#2E2438" else "#8FB36A"
        else -> if (isNight) "#2E3B33" else "#79B364"
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val rnd = Random(7)

        // 空
        val skyTop = Color.parseColor(cSkyTop())
        val skyBot = Color.parseColor(cSkyBot())
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, skyTop, skyBot, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h * 0.55f, p)
        p.shader = null

        if (isNight) {
            // 月と星
            p.color = Color.parseColor("#FFF6C9")
            c.drawCircle(w * 0.8f, h * 0.12f, w * 0.07f, p)
            p.color = skyTop
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
        p.color = Color.parseColor(cHill())
        c.drawOval(RectF(-w * 0.3f, h * 0.42f, w * 0.7f, h * 0.62f), p)
        c.drawOval(RectF(w * 0.4f, h * 0.44f, w * 1.3f, h * 0.62f), p)

        // 地面
        p.color = Color.parseColor(cGround())
        c.drawRect(0f, h * 0.52f, w, h, p)

        // 雪テーマは地面に積雪の白を重ねる
        if (theme == "snow" && !isNight) {
            p.color = Color.argb(120, 255, 255, 255)
            c.drawRect(0f, h * 0.52f, w, h * 0.6f, p)
        }

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

        // 桜テーマは花びらを散らす
        if (theme == "sakura") {
            p.color = Color.argb(200, 255, 183, 213)
            repeat(24) {
                c.drawCircle(rnd.nextFloat() * w, h * 0.1f + rnd.nextFloat() * h * 0.8f,
                    w * 0.008f + rnd.nextFloat() * w * 0.01f, p)
            }
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
// シークレットモード：1軒の家を描くビュー
// 夜は人狼が外出して家が空になる（プレイヤーには見えない）
// =====================================================

class HouseView(context: Context, private val night: Boolean) : View(context) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var isPlayerHouse = false      // あなたの家（赤丸で囲む）
    var isEmpty = false            // 空き家として暴かれた（人狼だったと判明）
    var isCleared = false          // 調べて人狼ではなかった家
    var isGone = false             // 住人が脱落した家

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pad = w * 0.10f
        val hw = w - pad * 2
        val hh = h * 0.72f
        val x = pad
        val y = h * 0.16f

        // あなたの家は赤い太線の丸で囲む
        if (isPlayerHouse) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = w * 0.045f
            p.color = Color.parseColor("#FF3B30")
            c.drawCircle(w / 2f, h / 2f, minOf(w, h) / 2f - p.strokeWidth, p)
            p.style = Paint.Style.FILL
        }

        val dim = isGone || isCleared
        val wallTop = y + hh * 0.4f
        // 壁
        p.color = when {
            isEmpty -> Color.parseColor("#3A2A2A")
            dim -> Color.parseColor("#4A4A52")
            night -> Color.parseColor("#5A5346")
            else -> Color.parseColor("#EFE3C8")
        }
        c.drawRect(x, wallTop, x + hw, y + hh, p)
        // 屋根
        p.color = when {
            isEmpty -> Color.parseColor("#5A2222")
            dim -> Color.parseColor("#5A5A62")
            night -> Color.parseColor("#7A3A3A")
            else -> Color.parseColor("#C6553F")
        }
        val roof = Path()
        roof.moveTo(x - hw * 0.08f, wallTop)
        roof.lineTo(x + hw / 2f, y)
        roof.lineTo(x + hw * 1.08f, wallTop)
        roof.close()
        c.drawPath(roof, p)
        // 窓（夜は灯りがともる。空き家・脱落は消灯）
        p.color = when {
            isEmpty || isGone -> Color.parseColor("#2A2A30")
            isCleared -> Color.parseColor("#9FD8A8")
            night -> Color.parseColor("#FFD97A")
            else -> Color.parseColor("#7EC8F2")
        }
        c.drawRect(x + hw * 0.13f, wallTop + hh * 0.16f, x + hw * 0.33f, wallTop + hh * 0.4f, p)
        c.drawRect(x + hw * 0.67f, wallTop + hh * 0.16f, x + hw * 0.87f, wallTop + hh * 0.4f, p)
        // ドア
        p.color = if (isEmpty) Color.parseColor("#1A1A1E") else Color.parseColor("#4A3626")
        c.drawRect(x + hw * 0.42f, wallTop + hh * 0.25f, x + hw * 0.58f, y + hh, p)
    }
}

// =====================================================
// シークレットモード：9軒の家から人狼2名の家を探すゲーム
//  ・各キャラは自分の家に住んでいる
//  ・夜、人狼2名は外出する（家が空になる）
//  ・プレイヤーは家を選んで調べ、人狼の家かどうかを確かめる
//  ・1日たつと住人が1体減る（人狼に襲われる）
// =====================================================

class SecretEngine {

    companion object { const val N = 9 }

    val wolfHouses = LinkedHashSet<Int>()   // 人狼が今いる家（毎晩移動する）
    var playerHouse = -1                    // あなたの家
    val gone = LinkedHashSet<Int>()         // 住人がいなくなった家（襲撃された）
    val checked = LinkedHashSet<Int>()      // 今夜すでに調べた家
    var foundCount = 0                      // 人狼を追い詰めた回数
    var day = 1
    var searchesLeft = 1                    // 1日に調べられる回数
    var lastMessage = ""
    val wills = ArrayList<String>()         // 犠牲者が遺したヒント（遺書）

    fun setup() {
        wolfHouses.clear(); gone.clear(); checked.clear(); wills.clear()
        day = 1; searchesLeft = 1; lastMessage = ""; foundCount = 0
        val ids = (0 until N).shuffled()
        playerHouse = ids[0]
        val rest = ids.drop(1)
        wolfHouses.add(rest[0]); wolfHouses.add(rest[1])
    }

    // 人狼が隠れられる家＝空き家（襲撃済み）またはまだ生きている家（あなたの家以外）
    fun hideableHouses(): List<Int> = (0 until N).filter { it != playerHouse }

    fun aliveHouses(): List<Int> = (0 until N).filter { !gone.contains(it) }

    // 家を調べる。trueなら人狼がいた
    fun search(house: Int): Boolean {
        checked.add(house)
        searchesLeft--
        val hit = wolfHouses.contains(house)
        if (hit) {
            foundCount++
            wolfHouses.remove(house)
            lastMessage = "もぬけの殻だ…！ここに人狼がひそんでいた。追い詰めた！"
        } else if (gone.contains(house)) {
            lastMessage = "空き家だった。人狼の気配はない。"
        } else {
            lastMessage = "住人は眠っていた。人狼ではないようだ。"
        }
        return hit
    }

    // 夜が明ける：犠牲者が1人出て遺書を残し、人狼は別の家へ移動する
    fun advanceDay(): Int {
        day++
        searchesLeft = 1
        checked.clear()

        // 犠牲者（あなたと人狼の家以外の生存者から）
        val victims = (0 until N).filter {
            it != playerHouse && !wolfHouses.contains(it) && !gone.contains(it)
        }
        var victim = -1
        if (victims.isNotEmpty()) {
            victim = victims.random()
            gone.add(victim)
        }

        // 人狼は毎晩、別の家に移動する（既存の家か、空き家に隠れる）
        val newSpots = LinkedHashSet<Int>()
        for (w in wolfHouses) {
            val cands = hideableHouses().filter { !newSpots.contains(it) }
            newSpots.add(if (cands.isNotEmpty()) cands.random() else w)
        }
        wolfHouses.clear(); wolfHouses.addAll(newSpots)

        // 犠牲者の遺書（残り人数に応じて核心に迫るヒントを出す）
        if (victim >= 0) wills.add(buildWill(victim))
        return victim
    }

    // 遺書のヒント：残りが少ないほど核心に迫る
    private fun buildWill(victim: Int): String {
        val remain = aliveHouses().size
        val w = wolfHouses.toList()
        if (w.isEmpty()) return "${victim + 1}番の家の住人「…もう、大丈夫みたいだ」"
        val target = w.random()
        val hint = when {
            // 残りが少ない＝かなり具体的
            remain <= 4 -> {
                if (target % 3 == 0) "やつは左の列にいる"
                else if (target % 3 == 1) "やつは真ん中の列にいる"
                else "やつは右の列にいる"
            }
            remain <= 6 -> {
                if (target < 3) "やつは上のほうの家にひそんでいる"
                else if (target < 6) "やつは真ん中あたりの家にひそんでいる"
                else "やつは下のほうの家にひそんでいる"
            }
            else -> {
                if (gone.contains(target)) "やつは……空き家に隠れているかもしれない"
                else "やつは……まだ人の住む家にまぎれている"
            }
        }
        val extra = if (remain <= 3) "　${target + 1}番の家をよく見ろ…" else ""
        return "📜 ${victim + 1}番の家の遺書：「$hint$extra」"
    }

    // 0=続行 1=あなたの勝ち 2=あなたの負け
    fun winner(): Int {
        if (foundCount >= 2) return 1
        val villagers = (0 until N).filter { !gone.contains(it) && it != playerHouse }
        if (villagers.isEmpty()) return 2
        return 0
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
    // オプション（ゲーム開始時にSharedPreferencesから読み込む）
    private var optNumbering = false
    private var optCounter = true
    private var optAnalysis = false
    private var optWinrate = false
    private var optOnebyone = false
    private var optQuickNext = true   // 右下に固定の「次へ」ボタンを出すか
    private var talkReveal = 0   // ⑤1人ずつ送り：表示済みの会話数
    private var humanMode = false   // 人モード：動物のかわりに人間の立ち絵を使う

    // 人モード設定を反映したCharacterViewを作る
    private fun charView(animal: Animal, alive: Boolean): CharacterView {
        val cv = CharacterView(this, animal, alive)
        cv.humanMode = humanMode
        return cv
    }
    private var predictedWolves = LinkedHashSet<Int>()   // 観戦前の人狼予想（2匹）
    private var predictionActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0E1430"))
        setContentView(root)
        val sp0 = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
        humanMode = sp0.getBoolean("human_mode", false)
        optQuickNext = sp0.getBoolean("opt_quicknext", true)
        showSplash()
    }

    // ---------- スタート画面（イントロ動画・初回のみ） ----------

    private fun showSplash() {
        val fl = FrameLayout(this)
        fl.setBackgroundColor(Color.BLACK)

        val video = VideoView(this)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT)
        lp.gravity = Gravity.CENTER
        fl.addView(video, lp)

        val uri = Uri.parse("android.resource://$packageName/raw/intro")
        video.setVideoURI(uri)

        // タップでスキップ
        val skip = tv("タップでスキップ ▶", 13f, false, Color.parseColor("#CCFFFFFF"))
        skip.setPadding(dp(16), dp(16), dp(16), dp(16))
        val slp = FrameLayout.LayoutParams(-2, -2)
        slp.gravity = Gravity.BOTTOM or Gravity.END
        fl.addView(skip, slp)

        var done = false
        val goTitle = {
            if (!done) {
                done = true
                video.stopPlayback()
                showTitle()
            }
        }
        video.setOnCompletionListener { goTitle() }
        video.setOnErrorListener { _, _, _ -> goTitle(); true }
        video.setOnPreparedListener { mp -> mp.isLooping = false; video.start() }
        fl.setOnClickListener { goTitle() }

        setScreen(fl)
    }

    // ---------- UIヘルパー ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // 右下固定ボタンに割り当てる「次に進む」動作（画面ごとに設定）
    private var quickNextAction: (() -> Unit)? = null
    private var quickNextLabel: String = "次へ"

    // 画面の主要な「次に進む」操作を右下の固定ボタンにも割り当てる
    private fun setQuickNext(label: String, action: (() -> Unit)?) {
        quickNextLabel = label
        quickNextAction = action
    }

    // 選択が必要な画面で「次へ」を押したとき、ランダムに選んでよいか確認する
    private fun confirmRandomPick(what: String, cands: List<Player>, onPick: (Player) -> Unit) {
        if (cands.isEmpty()) return
        val pick = cands.random()
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val outer = card()
        outer.addView(tv("🎲 おまかせで選びますか？", 18f, true, Color.parseColor("#FFE28A")))
        outer.addView(space(dp(8)))
        outer.addView(tv("$what：${pick.pname}", 16f, true, Color.WHITE))
        outer.addView(space(dp(4)))
        outer.addView(tv("「次へ」でこの相手に決定します。自分で選びたいときは「もどる」を押してください。",
            12f, false, Color.parseColor("#BFD0FF")))
        outer.addView(space(dp(14)))
        outer.addView(btn("次へ ▶", Color.parseColor("#D8703D")) {
            d.dismiss(); onPick(pick)
        })
        outer.addView(space(dp(8)))
        outer.addView(btn("もどる") { d.dismiss() })
        val dm = resources.displayMetrics
        d.setContentView(outer)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((dm.widthPixels * 0.86f).toInt(), -2)
        d.show()
    }

    private fun setScreen(content: View, scrollToBottom: Boolean = false) {
        root.removeAllViews()
        val theme = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
            .getString("bg_theme", "normal") ?: "normal"
        root.addView(TownView(this, night, theme), FrameLayout.LayoutParams(-1, -1))
        val sc = ScrollView(this)
        sc.isFillViewport = true
        sc.addView(content)
        root.addView(sc, FrameLayout.LayoutParams(-1, -1))

        // 右下に小さく固定の「次へ」ボタン（オプションでON時のみ）
        val act = quickNextAction
        if (optQuickNext && act != null) {
            val fab = Button(this)
            fab.text = "$quickNextLabel ▶"
            fab.textSize = 13f
            fab.setTextColor(Color.WHITE)
            fab.isAllCaps = false
            fab.setPadding(dp(14), dp(6), dp(14), dp(6))
            fab.background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#D8703D"))
                setStroke(dp(2), Color.argb(180, 255, 255, 255))
            }
            fab.elevation = dp(6).toFloat()
            fab.setOnClickListener { act.invoke() }
            val flp = FrameLayout.LayoutParams(-2, -2)
            flp.gravity = Gravity.BOTTOM or Gravity.END
            flp.setMargins(0, 0, dp(14), dp(18))
            root.addView(fab, flp)
        }

        // 左上に小さく「終了」ボタン（ゲーム中のみ。誤タップ防止のため確認あり）
        if (showExitButton) {
            val ex = Button(this)
            ex.text = "✕ 終了"
            ex.textSize = 12f
            ex.setTextColor(Color.WHITE)
            ex.isAllCaps = false
            ex.setPadding(dp(12), dp(5), dp(12), dp(5))
            ex.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(190, 60, 66, 92))
                setStroke(dp(1), Color.argb(150, 255, 255, 255))
            }
            ex.elevation = dp(6).toFloat()
            ex.setOnClickListener { confirmExitToTitle() }
            val elp = FrameLayout.LayoutParams(-2, -2)
            elp.gravity = Gravity.TOP or Gravity.START
            elp.setMargins(dp(12), dp(12), 0, 0)
            root.addView(ex, elp)
        }

        if (scrollToBottom) {
            sc.post { sc.fullScroll(View.FOCUS_DOWN) }
        }
        // 次の画面に持ち越さないようクリアする（各画面で毎回setQuickNextする）
        quickNextAction = null
    }

    // 終了ボタンを出すか（ゲーム中のみtrue。タイトルや図鑑では出さない）
    private var showExitButton = false

    private fun confirmExitToTitle() {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val outer = card()
        outer.addView(tv("ゲームを終了しますか？", 17f, true, Color.parseColor("#FFE28A")))
        outer.addView(space(dp(6)))
        outer.addView(tv("いま進めているゲームは記録されず、タイトルに戻ります。",
            12f, false, Color.parseColor("#BFD0FF")))
        outer.addView(space(dp(14)))
        outer.addView(btn("終了してタイトルへ", Color.parseColor("#D8703D")) {
            d.dismiss()
            night = false
            showExitButton = false
            showTitle()
        })
        outer.addView(space(dp(8)))
        outer.addView(btn("つづける") { d.dismiss() })
        val dm = resources.displayMetrics
        d.setContentView(outer)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((dm.widthPixels * 0.84f).toInt(), -2)
        d.show()
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

    // その時の局面から各キャラの表情を決める
    private var moodVictory = 0    // 1=村人勝利 2=人狼勝利（ゲームオーバー時）
    private fun emotionOf(pl: Player): Int {
        if (!pl.alive) return 0
        // ゲーム終了時：勝った陣営は喜び、負けた陣営は悲しみ
        if (moodVictory == 1) return if (pl.role.wolfSide) 2 else if (pl.role == Role.FOX_SPIRIT) 2 else 1
        if (moodVictory == 2) return if (pl.role.wolfSide) 1 else 2
        if (moodVictory == 3) return if (pl.role == Role.FOX_SPIRIT) 1 else 2
        if (moodVictory == 4) return if (engine.isLover(pl)) 1 else 2
        val e = engine
        // 名探偵は自信ありげに安心
        if (pl.id == e.detectiveId) return 6
        // 今もっとも疑われているキャラは焦る
        if (e.mostSuspectedIds.contains(pl.id)) return 5
        // あなたが旗を立てた相手は怯える（恐怖）
        if (e.flags.contains(pl.id)) return 4
        // 黒判定を受けたキャラは怒る（濡れ衣に反発 or 開き直り）
        if (e.publicBlack.contains(pl.id)) return 3
        return 0
    }

    private fun charCell(pl: Player, sizeDp: Int, onClick: ((Player) -> Unit)?): LinearLayout {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER

        // キャラ画像の上に旗/疑いマークを重ねる
        val stack = FrameLayout(this)
        stack.clipChildren = false   // 跳ねるアニメで頭が切れないように

        // あなた自身は赤い太線の丸で囲んで、ひと目で分かるようにする
        val isYouCell = engine.humanId >= 0 && pl.id == engine.humanId
        if (isYouCell) {
            val ring = View(this)
            ring.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(4), Color.parseColor("#FF3B30"))
            }
            val rsz = (sizeDp * 1.06f).toInt()
            val rlp = FrameLayout.LayoutParams(dp(rsz), dp(rsz))
            rlp.gravity = Gravity.CENTER
            stack.addView(ring, rlp)
        }

        val cv = charView(pl.animal, pl.alive)
        cv.emotion = emotionOf(pl)
        stack.addView(cv, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))

        // 最も疑われているキャラの目印（頭上に👀）
        if (pl.alive && engine.mostSuspectedIds.contains(pl.id)) {
            val mark = tv("👀", (sizeDp * 0.32f).coerceAtLeast(14f))
            val mlp = FrameLayout.LayoutParams(-2, -2)
            mlp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            stack.addView(mark, mlp)
        }
        // あなたが立てた旗
        if (pl.alive && engine.flags.contains(pl.id)) {
            val flag = tv("🚩", (sizeDp * 0.36f).coerceAtLeast(16f))
            val flp = FrameLayout.LayoutParams(-2, -2)
            flp.gravity = Gravity.TOP or Gravity.END
            stack.addView(flag, flp)
        }
        // 動物に固定番号をつけるオプション
        if (optNumbering) {
            val num = "①②③④⑤⑥⑦⑧⑨".getOrNull(pl.animal.ordinal)?.toString() ?: ""
            val badge = tv(num, (sizeDp * 0.34f).coerceAtLeast(15f), true, Color.parseColor("#FFE28A"))
            val blp = FrameLayout.LayoutParams(-2, -2)
            blp.gravity = Gravity.TOP or Gravity.START
            stack.addView(badge, blp)
        }
        // 🐺 人狼だと確定しているキャラに狼マークを重ねる
        if (engine.isWolfConfirmed(pl, moodVictory != 0)) {
            val resId = resources.getIdentifier("wolf_mark", "drawable", packageName)
            if (resId != 0) {
                val wolf = android.widget.ImageView(this)
                wolf.setImageResource(resId)
                val wsz = (sizeDp * 0.52f).toInt().coerceAtLeast(22)
                val wlp = FrameLayout.LayoutParams(dp(wsz), dp(wsz))
                wlp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                stack.addView(wolf, wlp)
            }
        }
        cell.addView(stack)

        val nameText = pl.pname + if (!pl.alive) " †" else ""
        val name = tv(nameText, 12f, true,
            if (pl.alive) Color.WHITE else Color.parseColor("#9AA0B5"))
        name.gravity = Gravity.CENTER
        cell.addView(name)
        if (isYouCell) {
            val you = tv("● YOU", 11f, true, Color.parseColor("#FF6B60"))
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
            } else if (onClick != null && !pl.alive) {
                // 薄くするのは脱落者だけ。生きているキャラは選べなくてもはっきり表示する
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
        setQuickNext("観戦") { onContinue() }
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
                if (e.dayCount > 1) e.runVote(null)   // 1日目は処刑なし
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
            } else if (!pl.alive) {
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
        setQuickNext("おまかせ") {
            // まだ2匹選んでいなければランダムで埋めて観戦へ
            val rest = engine.alive().filter { !predictedWolves.contains(it.id) }.shuffled()
            for (p2 in rest) {
                if (predictedWolves.size >= 2) break
                predictedWolves.add(p2.id)
            }
            predictionActive = true
            onContinue()
        }
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
            rowL.addView(charView(pl.animal, true),
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
        setQuickNext("もどる") { showTitle() }
        cd.addView(btn("タイトルへ", Color.parseColor("#D8703D")) { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- 自由会話（疑い話し合いの前） ----------

    private var freeTalksToday = ArrayList<Talk>()

    private fun startFreeTalk() {
        // 1日目でまだ自己紹介をしていなければ、必ず自己紹介から始める
        if (engine.dayCount == 1 && !engine.introDone) {
            introTalks = ArrayList(engine.buildIntroTalks())
            introReveal = 1
            showIntro()
            return
        }
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
        setQuickNext("話し合いへ") {
            currentTalks.addAll(freeTalksToday)
            currentTalks.addAll(engine.discussionTalks())
            talkReveal = if (optOnebyone) 1 else currentTalks.size
            showDay()
        }
        cd.addView(btn("疑いの話し合いへ", Color.parseColor("#D8703D")) {
            currentTalks.addAll(freeTalksToday)
            currentTalks.addAll(engine.discussionTalks())
            talkReveal = if (optOnebyone) 1 else currentTalks.size
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
        setQuickNext("もどる") { showFreeTalk() }
        cd.addView(btn("やめておく") { showFreeTalk() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- 昼の会話：吹き出し ----------

    // 会話文中の役職ワードを、役職ごとの暗い色＋太字で強調
    private fun roleHighlightTv(text: String): TextView {
        val base = Color.parseColor("#22283C")
        val tvw = TextView(this)
        tvw.textSize = 14f
        tvw.setTextColor(base)
        // 役職語 → 濃くて視認性の高い色
        val roleColors = listOf(
            "人狼" to Color.parseColor("#8E1B1B"),   // 濃い赤
            "占い師" to Color.parseColor("#3A2E7A"), // 濃い紫紺
            "占い" to Color.parseColor("#3A2E7A"),
            "霊能" to Color.parseColor("#155E63"),   // 濃い青緑
            "狩人" to Color.parseColor("#1F5A2E"),   // 濃い緑
            "村人" to Color.parseColor("#6B4A1A"),   // 濃い茶
            "名探偵" to Color.parseColor("#0F3A63")  // 濃い藍
        )
        val sp = android.text.SpannableString(text)
        for ((word, col) in roleColors) {
            var from = text.indexOf(word)
            while (from >= 0) {
                val to = from + word.length
                sp.setSpan(android.text.style.ForegroundColorSpan(col), from, to,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(android.text.style.StyleSpan(Typeface.BOLD), from, to,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                from = text.indexOf(word, to)
            }
        }
        tvw.text = sp
        return tvw
    }

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
        val cvv = charView(sp.animal, sp.alive)
        cvv.emotion = when {
            !sp.alive -> 0
            t.suspect -> 2   // 誰かを疑う=険しい表情
            t.text.contains("信用") || t.text.contains("信頼") || t.text.contains("♪") -> 1
            else -> 0
        }
        // 番号や確定人狼マークを重ねる必要があるときはFrameLayoutで包む
        val confirmed = engine.isWolfConfirmed(sp, moodVictory != 0)
        if (optNumbering || confirmed || isYou) {
            val stack = FrameLayout(this)
            stack.clipChildren = false   // 跳ねるアニメで頭が切れないように
            // あなた自身の発言は赤い丸で囲む
            if (isYou) {
                val ring = View(this)
                ring.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(3), Color.parseColor("#FF3B30"))
                }
                val rlp = FrameLayout.LayoutParams(dp(55), dp(55))
                rlp.gravity = Gravity.CENTER
                stack.addView(ring, rlp)
            }
            stack.addView(cvv, FrameLayout.LayoutParams(dp(52), dp(52)))
            if (optNumbering) {
                val num = "①②③④⑤⑥⑦⑧⑨".getOrNull(sp.animal.ordinal)?.toString() ?: ""
                val badge = tv(num, 17f, true, Color.parseColor("#FFE28A"))
                val blp = FrameLayout.LayoutParams(-2, -2)
                blp.gravity = Gravity.TOP or Gravity.START
                stack.addView(badge, blp)
            }
            if (confirmed) {
                val resId = resources.getIdentifier("wolf_mark", "drawable", packageName)
                if (resId != 0) {
                    val wolf = android.widget.ImageView(this)
                    wolf.setImageResource(resId)
                    val wlp = FrameLayout.LayoutParams(dp(26), dp(26))
                    wlp.gravity = Gravity.BOTTOM or Gravity.END
                    stack.addView(wolf, wlp)
                }
            }
            left.addView(stack, LinearLayout.LayoutParams(dp(52), dp(52)))
        } else {
            left.addView(cvv, LinearLayout.LayoutParams(dp(52), dp(52)))
        }
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
        bubble.addView(roleHighlightTv(t.text))

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
        outer.addView(tv("狼の絵 = 人狼だと判明している人（†は脱落済み）", 12f, false,
            Color.parseColor("#FFC9C9")))
        outer.addView(space(dp(6)))

        // 相関図（疑いの矢印のみ描画）
        val suspectTalks = talks.filter { it.suspect }
        val dm = resources.displayMetrics
        val side = (dm.widthPixels * 0.82f).toInt()
        outer.addView(SummaryView(this, e, suspectTalks, humanMode, moodVictory != 0),
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

    // ---------- 推理ノート ----------

    private fun showHintDialog() {
        val e = engine
        e.computeMostSuspected(currentTalks)
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val outer = card()
        val sc = ScrollView(this)
        sc.addView(outer)
        val dm = resources.displayMetrics

        outer.addView(tv("🔍 AIアシスタント", 19f, true, Color.parseColor("#FFE28A")))
        outer.addView(tv("いまの状況からの推理のヒント", 12f, false, Color.parseColor("#BFD0FF")))
        outer.addView(space(dp(10)))
        for (hint in e.buildHints()) {
            val box = card()
            box.addView(tv(hint, 14f))
            outer.addView(box)
            outer.addView(space(dp(8)))
        }
        outer.addView(tv("※ ヒントは公開情報だけをもとにした一般的な助言です。最後に決めるのはあなたです。",
            11f, false, Color.parseColor("#9AA0B5")))
        outer.addView(space(dp(12)))
        outer.addView(btn("とじる") { d.dismiss() })
        d.setContentView(sc)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((dm.widthPixels * 0.94f).toInt(), (dm.heightPixels * 0.86f).toInt())
        d.show()
    }

    private fun showNoteDialog() {
        val e = engine
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val outer = card()
        val sc = ScrollView(this)
        sc.addView(outer)
        val dm = resources.displayMetrics

        outer.addView(tv("📓 推理ノート", 19f, true, Color.parseColor("#FFE28A")))
        outer.addView(space(dp(4)))
        outer.addView(tv("これまでの発言・投票・能力の記録", 12f, false, Color.parseColor("#BFD0FF")))
        outer.addView(space(dp(10)))

        // 自己紹介フェーズの記録（1日目に全員が名乗った内容）
        if (e.introDone) {
            outer.addView(tv("🗣️ 自己紹介（1日目）", 15f, true, Color.parseColor("#FFD08A")))
            outer.addView(tv("人狼は「言った人」に1人、「言わなかった人」に1人います", 11f, false,
                Color.parseColor("#BFD0FF")))
            val said = e.players.filter { e.introDenied.contains(it.id) }
            val silent = e.players.filter { !e.introDenied.contains(it.id) }
            outer.addView(tv("「人狼ではない」と言った: " + said.joinToString("、") {
                it.pname + if (!it.alive) "†" else "" }, 13f, false, Color.parseColor("#C8F0C2")))
            outer.addView(tv("何も言わなかった: " + silent.joinToString("、") {
                it.pname + if (!it.alive) "†" else "" }, 13f, false, Color.parseColor("#FFE0B0")))
            outer.addView(space(dp(10)))
        }

        // 能力・出来事の履歴
        outer.addView(tv("🔮 能力・出来事", 15f, true, Color.parseColor("#A8D8FF")))
        if (e.noteAbilities.isEmpty()) {
            outer.addView(tv("まだ記録はありません。", 13f, false, Color.parseColor("#9AA0B5")))
        } else {
            for (line in e.noteAbilities) outer.addView(tv("・$line", 13f))
        }
        outer.addView(space(dp(10)))

        // 投票履歴（日ごと）
        outer.addView(tv("⚖️ 投票の記録", 15f, true, Color.parseColor("#FFD08A")))
        if (e.noteVotes.isEmpty()) {
            outer.addView(tv("まだ投票はありません。", 13f, false, Color.parseColor("#9AA0B5")))
        } else {
            val byDay = e.noteVotes.groupBy { it.first }
            for ((day, list) in byDay.toSortedMap()) {
                outer.addView(tv("${day}日目", 13f, true, Color.parseColor("#FFE28A")))
                for ((_, voter, target) in list) {
                    outer.addView(tv("　${e.players[voter].pname} → ${e.players[target].pname}", 13f))
                }
            }
        }
        outer.addView(space(dp(10)))

        // 発言履歴（疑い/信頼）
        outer.addView(tv("💬 発言の記録", 15f, true, Color.parseColor("#A8E6A1")))
        if (e.noteTalks.isEmpty()) {
            outer.addView(tv("まだ発言はありません。", 13f, false, Color.parseColor("#9AA0B5")))
        } else {
            var curDay = -1
            for ((day, t) in e.noteTalks) {
                if (day != curDay) {
                    outer.addView(tv("${day}日目", 13f, true, Color.parseColor("#FFE28A")))
                    curDay = day
                }
                val arrow = if (t.suspect) "🐺疑" else "🤝信"
                val col = if (t.suspect) Color.parseColor("#FFC9C9") else Color.parseColor("#C8F0C2")
                outer.addView(tv("　$arrow ${e.players[t.speakerId].pname} → ${e.players[t.targetId].pname}",
                    12f, false, col))
            }
        }

        outer.addView(space(dp(12)))
        outer.addView(btn("とじる") { d.dismiss() })

        d.setContentView(sc)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((dm.widthPixels * 0.94f).toInt(), (dm.heightPixels * 0.88f).toInt())
        d.show()
    }

    // ---------- シークレットモード ----------

    private var secret = SecretEngine()

    private fun startSecret() {
        showExitButton = true
        secret = SecretEngine()
        secret.setup()
        night = true
        showSecret()
    }

    private fun showSecret() {
        val s = secret
        val pn = panel()
        val cd = card()
        cd.addView(tv("🌙 シークレットモード", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(tv("夜、人狼は家を空けて出歩く。もぬけの殻の家を探し出せ！",
            12f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(8)))

        // 状況表示
        val statusRow = tv("${s.day}日目　🔍 調べられる回数: ${s.searchesLeft}　🐺 追い詰めた: ${s.foundCount}/2",
            14f, true, Color.parseColor("#FFE28A"))
        statusRow.gravity = Gravity.CENTER
        cd.addView(statusRow)
        val remain = s.aliveHouses().size
        val sub = tv("住人ののこる家: $remain 軒　（人狼は毎晩ちがう家に隠れる）",
            12f, false, Color.parseColor("#BFD0FF"))
        sub.gravity = Gravity.CENTER
        cd.addView(sub)
        cd.addView(space(dp(10)))

        // 犠牲者が遺したヒント（遺書）
        if (s.wills.isNotEmpty()) {
            val wbox = card()
            wbox.addView(tv("📜 遺書", 14f, true, Color.parseColor("#FFD08A")))
            for (wl in s.wills.takeLast(3)) {
                wbox.addView(tv(wl, 12f, false, Color.parseColor("#FFE0B0")))
            }
            cd.addView(wbox)
            cd.addView(space(dp(8)))
        }

        if (s.lastMessage.isNotEmpty()) {
            val msg = tv(s.lastMessage, 14f, true,
                if (s.lastMessage.contains("突き止めた")) Color.parseColor("#FF9B9B")
                else Color.parseColor("#C8F0C2"))
            msg.gravity = Gravity.CENTER
            cd.addView(msg)
            cd.addView(space(dp(8)))
        }

        // 9軒の家を3×3で配置
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.gravity = Gravity.CENTER_HORIZONTAL
        val dm = resources.displayMetrics
        val cellPx = (dm.widthPixels * 0.26f).toInt()
        for (row in 0 until 3) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER
            for (col in 0 until 3) {
                val id = row * 3 + col
                val holder = LinearLayout(this)
                holder.orientation = LinearLayout.VERTICAL
                holder.gravity = Gravity.CENTER

                val hv = HouseView(this, night)
                hv.isPlayerHouse = (id == s.playerHouse)
                hv.isEmpty = false
                hv.isCleared = s.checked.contains(id)
                hv.isGone = s.gone.contains(id)
                // 空き家にも人狼が隠れるので、脱落した家も調べられる
                val selectable = id != s.playerHouse && !s.checked.contains(id) && s.searchesLeft > 0
                if (selectable) {
                    holder.setOnClickListener { doSecretSearch(id) }
                }
                holder.addView(hv, LinearLayout.LayoutParams(cellPx, cellPx))

                val label = when {
                    id == s.playerHouse -> "あなたの家"
                    s.checked.contains(id) -> "✔ 今夜は調査済"
                    s.gone.contains(id) -> "† ${id + 1}番（空き家）"
                    else -> "${id + 1}番の家"
                }
                val col2 = when {
                    id == s.playerHouse -> Color.parseColor("#FF6B60")
                    s.checked.contains(id) -> Color.parseColor("#A8E6A1")
                    s.gone.contains(id) -> Color.parseColor("#9AA0B5")
                    else -> Color.WHITE
                }
                val lt = tv(label, 11f, true, col2)
                lt.gravity = Gravity.CENTER
                holder.addView(lt)

                val lp = LinearLayout.LayoutParams(-2, -2)
                lp.setMargins(dp(2), dp(4), dp(2), dp(4))
                r.addView(holder, lp)
            }
            grid.addView(r)
        }
        cd.addView(grid)
        cd.addView(space(dp(10)))

        val w = s.winner()
        if (w != 0) {
            cd.addView(space(dp(6)))
            val res = tv(if (w == 1) "🎉 人狼2匹の家をすべて突き止めた！" else "🐺 村人が全員いなくなってしまった…",
                18f, true, if (w == 1) Color.parseColor("#A8E6A1") else Color.parseColor("#FF9B9B"))
            res.gravity = Gravity.CENTER
            cd.addView(res)
            cd.addView(space(dp(6)))
            val ans = s.wolfHouses.joinToString("、") { "${it + 1}番" }
            if (ans.isNotEmpty())
                cd.addView(tv("逃げ延びた人狼は $ans にひそんでいました。", 13f, false, Color.parseColor("#BFD0FF")))
            cd.addView(space(dp(12)))
            setQuickNext("もう一度") { startSecret() }
            cd.addView(btn("もう一度あそぶ", Color.parseColor("#D8703D")) { startSecret() })
            cd.addView(space(dp(8)))
            cd.addView(btn("タイトルへ") { night = false; showTitle() })
        } else if (s.searchesLeft <= 0) {
            setQuickNext("朝へ") { nextSecretDay() }
            cd.addView(btn("☀️ 朝を迎える", Color.parseColor("#5A4FD8")) { nextSecretDay() })
            cd.addView(space(dp(8)))
            cd.addView(tv("今夜はもう調べられません。朝になると人狼が誰かを襲います。",
                12f, false, Color.parseColor("#BFD0FF")))
        } else {
            cd.addView(tv("調べたい家をタップしてください。", 13f, true, Color.parseColor("#FFE28A")))
            cd.addView(space(dp(8)))
            setQuickNext("おまかせ") {
                val cands = (0 until SecretEngine.N).filter {
                    it != s.playerHouse && !s.checked.contains(it)
                }
                if (cands.isNotEmpty()) doSecretSearch(cands.random())
            }
        }

        pn.addView(cd)
        setScreen(pn)
    }

    private fun doSecretSearch(house: Int) {
        secret.search(house)
        showSecret()
    }

    private fun nextSecretDay() {
        val v = secret.advanceDay()
        secret.lastMessage = if (v >= 0)
            "夜が明けた。${v + 1}番の家の住人が姿を消していた…" else "夜が明けた。"
        showSecret()
    }

    private fun showTitle() {
        showExitButton = false
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
        Animal.values().forEachIndexed { i, an ->
            preview.add(Player(i, if (humanMode) GameEngine.HUMAN_NAMES[i] else GameEngine.NAMES[i], an)) }
        val cd0 = card()
        cd0.addView(charGrid(preview, 58, 3, null))
        pn.addView(cd0, LinearLayout.LayoutParams(-1, -2))
        pn.addView(space(dp(14)))

        val sp = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
        val g = sp.getInt("games", 0)
        if (g > 0) {
            val streak = sp.getInt("win_streak", 0)
            val streakTxt = if (streak >= 2) "　🔥$streak 連勝" else ""
            val st = tv("戦績: ${sp.getInt("wins", 0)}勝 / ${g}戦$streakTxt", 13f, true, Color.WHITE)
            st.gravity = Gravity.CENTER
            st.setShadowLayer(6f, 0f, 2f, Color.argb(180, 0, 0, 0))
            pn.addView(st)
            pn.addView(space(dp(10)))
        }

        // 難易度切替（タップで やさしい→ふつう→むずかしい を循環）
        val diffNames = arrayOf("やさしい", "ふつう", "むずかしい")
        val diffColors = arrayOf("#A8E6A1", "#FFE28A", "#FF9B9B")
        val curDiff = sp.getInt("difficulty", 1)
        val diffBtn = btn("難易度: ${diffNames[curDiff]}", Color.parseColor(diffColors[curDiff])) {
            val next = (sp.getInt("difficulty", 1) + 1) % 3
            sp.edit().putInt("difficulty", next).apply()
            showTitle()
        }
        pn.addView(diffBtn, LinearLayout.LayoutParams(-1, -2))
        pn.addView(space(dp(10)))

        setQuickNext("はじめる") { startGame() }
        pn.addView(btn("はじめる", Color.parseColor("#D8703D")) { startGame() },
            LinearLayout.LayoutParams(-1, -2))
        pn.addView(space(dp(10)))
        pn.addView(btn("🌙 シークレットモード", Color.parseColor("#5A4FD8")) { startSecret() },
            LinearLayout.LayoutParams(-1, -2))
        pn.addView(space(dp(10)))
        pn.addView(btn("⚙️ オプション", Color.parseColor("#3D6BD8")) { showOptions() },
            LinearLayout.LayoutParams(-1, -2))
        pn.addView(space(dp(10)))
        pn.addView(btn("📖 キャラ図鑑", Color.parseColor("#3D9E6B")) { showZukan() },
            LinearLayout.LayoutParams(-1, -2))
        pn.addView(space(dp(10)))
        pn.addView(btn("🎭 役職図鑑", Color.parseColor("#7A4FD8")) { showRoleZukan() },
            LinearLayout.LayoutParams(-1, -2))
        pn.addView(space(dp(10)))
        pn.addView(btn("🏁 エンディング図鑑", Color.parseColor("#7A4FD8")) { showEndingZukan() },
            LinearLayout.LayoutParams(-1, -2))
        pn.addView(space(dp(10)))
        pn.addView(btn("🏅 実績", Color.parseColor("#3D6BD8")) { showAchievements() },
            LinearLayout.LayoutParams(-1, -2))
        pn.addView(space(dp(10)))
        pn.addView(btn("📊 成績", Color.parseColor("#3D9E6B")) { showStats() },
            LinearLayout.LayoutParams(-1, -2))
        pn.addView(space(dp(10)))
        pn.addView(btn("🛍️ ショップ（🪙${sp.getInt("coins", 0)}）", Color.parseColor("#D8703D")) { showShop() },
            LinearLayout.LayoutParams(-1, -2))
        pn.addView(space(dp(10)))
        pn.addView(btn("ルール") { showRules() }, LinearLayout.LayoutParams(-1, -2))
        setScreen(pn)
    }

    // ---------- オプション ----------

    // ON/OFFトグルボタンを作る共通ヘルパー
    private fun toggleBtn(sp: android.content.SharedPreferences, key: String, label: String,
                          defaultOn: Boolean, onColor: String = "#A8E6A1"): Button {
        val on = sp.getBoolean(key, defaultOn)
        return btn("$label: " + if (on) "する" else "しない",
            Color.parseColor(if (on) onColor else "#6B7280")) {
            sp.edit().putBoolean(key, !on).apply()
            if (key == "opt_quicknext") optQuickNext = !on
            showOptions()
        }
    }

    private fun showOptions() {
        val sp = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
        val pn = panel()
        val cd = card()
        cd.addView(tv("⚙️ オプション", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(space(dp(10)))

        // --- キャラクターモード ---
        cd.addView(tv("キャラクター", 15f, true, Color.parseColor("#A8D8FF")))
        val hm = sp.getBoolean("human_mode", false)
        cd.addView(btn("登場キャラ: " + if (hm) "👤 人モード" else "🐰 どうぶつモード",
            Color.parseColor(if (hm) "#7A9BD8" else "#3D9E6B")) {
            sp.edit().putBoolean("human_mode", !hm).apply()
            humanMode = !hm
            showOptions()
        })
        cd.addView(tv(if (hm) "9人の人間キャラ（篤史・茜・敏行・真由美・杏奈・健一・慎吾・千鶴・透）で遊びます。"
                      else "9匹のどうぶつキャラで遊びます。", 11f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(12)))

        // --- 特別ルール ---
        cd.addView(tv("特別ルール", 15f, true, Color.parseColor("#A8D8FF")))
        val masonOn = sp.getBoolean("mason_rule", false)
        cd.addView(btn("共有者ルール: " + if (masonOn) "あり" else "なし",
            Color.parseColor(if (masonOn) "#A8E6A1" else "#6B7280")) {
            sp.edit().putBoolean("mason_rule", !masonOn).apply(); showOptions()
        })
        cd.addView(tv("村人2枠が、お互いを知る共有者2人になります。", 11f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(6)))
        val loversOn = sp.getBoolean("lovers_rule", false)
        cd.addView(btn("恋人ルール: " + if (loversOn) "あり" else "なし",
            Color.parseColor(if (loversOn) "#FF9BD0" else "#6B7280")) {
            sp.edit().putBoolean("lovers_rule", !loversOn).apply(); showOptions()
        })
        cd.addView(tv("ランダムな2人が恋人に。2人だけ生き残れば恋人の勝ち。", 11f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(12)))

        // --- 表示オプション ---
        cd.addView(tv("画面表示", 15f, true, Color.parseColor("#A8D8FF")))
        cd.addView(toggleBtn(sp, "opt_numbering", "① 動物に番号をつける", false))
        cd.addView(tv("9匹に固定の番号（①〜⑨）を表示します。", 11f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(6)))
        cd.addView(toggleBtn(sp, "opt_counter", "② 生存数を上に表示", true))
        cd.addView(tv("人狼の残り数と、それ以外の残り数を画面上部に常に表示します。", 11f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(6)))
        cd.addView(toggleBtn(sp, "opt_analysis", "③ 会話ごとにAI分析図", false, "#7A4FD8"))
        cd.addView(tv("会話画面に、疑いの構図をまとめた図解を表示します。", 11f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(6)))
        cd.addView(toggleBtn(sp, "opt_winrate", "④ 人狼の有利率を表示", false, "#FF9B9B"))
        cd.addView(tv("いまの局面で人狼がどれくらい有利かをAI推論で表示します。", 11f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(6)))
        cd.addView(toggleBtn(sp, "opt_onebyone", "⑤ 会話を1人ずつ送りで進める", false, "#FFD08A"))
        cd.addView(tv("全員の会話をまとめて出さず、番号順に1人ずつ「次へ」で送ります。", 11f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(6)))
        cd.addView(toggleBtn(sp, "opt_quicknext", "⑥ 右下に「次へ」ボタンを固定", true, "#D8703D"))
        cd.addView(tv("画面のどこにいても押せる小さな次へボタンを右下に常時表示します（画面ごとにボタン位置を探さずに済みます）。",
            11f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(14)))

        setQuickNext("もどる") { showTitle() }
        cd.addView(btn("タイトルへ", Color.parseColor("#D8703D")) { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- ショップ（背景きせかえ） ----------

    // id, 名前, 価格（0=最初から所持）
    private val bgThemes = listOf(
        Triple("normal", "ふつうの村", 0),
        Triple("dusk", "夕暮れの村", 120),
        Triple("snow", "雪の村", 200),
        Triple("sakura", "桜の村", 280)
    )

    private fun showShop() {
        val sp = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
        val pn = panel()
        val cd = card()
        val coins = sp.getInt("coins", 0)
        val current = sp.getString("bg_theme", "normal") ?: "normal"
        cd.addView(tv("🛍️ ショップ", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(tv("🪙 所持コイン: $coins", 15f, true, Color.parseColor("#FFD450")))
        cd.addView(tv("ゲームをプレイするとコインがたまります（1戦+10・勝利+20）", 11f, false,
            Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(10)))
        cd.addView(tv("🌄 背景きせかえ", 15f, true, Color.parseColor("#A8D8FF")))

        for ((id, name, price) in bgThemes) {
            val owned = price == 0 || sp.getBoolean("bg_owned_$id", false)
            val selected = current == id
            val box = card()
            box.addView(tv(name, 15f, true,
                if (selected) Color.parseColor("#A8E6A1") else Color.WHITE))
            // 小さなプレビュー（昼のテーマ帯）
            box.addView(TownView(this, false, id), LinearLayout.LayoutParams(-1, dp(70)))
            box.addView(space(dp(6)))
            when {
                selected -> box.addView(tv("✔ 使用中", 13f, true, Color.parseColor("#A8E6A1")))
                owned -> box.addView(btn("これにする", Color.parseColor("#3D9E6B")) {
                    sp.edit().putString("bg_theme", id).apply(); showShop()
                })
                coins >= price -> box.addView(btn("🪙$price で購入", Color.parseColor("#D8703D")) {
                    sp.edit()
                        .putInt("coins", coins - price)
                        .putBoolean("bg_owned_$id", true)
                        .putString("bg_theme", id)
                        .apply()
                    showShop()
                })
                else -> box.addView(tv("🪙$price （コインが足りません）", 13f, false,
                    Color.parseColor("#9AA0B5")))
            }
            cd.addView(box)
            cd.addView(space(dp(8)))
        }

        cd.addView(space(dp(6)))
        setQuickNext("もどる") { showTitle() }
        cd.addView(btn("タイトルへ", Color.parseColor("#D8703D")) { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- 成績（統計） ----------

    private fun showStats() {
        val sp = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
        val pn = panel()
        val cd = card()
        cd.addView(tv("📊 成績", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(space(dp(10)))

        val games = sp.getInt("games", 0)
        val wins = sp.getInt("wins", 0)
        val rate = if (games > 0) wins * 100 / games else 0
        cd.addView(tv("総合", 15f, true, Color.parseColor("#A8D8FF")))
        cd.addView(tv("プレイ回数　$games 戦", 14f))
        cd.addView(tv("勝利　$wins 勝", 14f))
        cd.addView(tv("勝率　$rate %", 14f, true,
            when { rate >= 60 -> Color.parseColor("#A8E6A1")
                   rate >= 40 -> Color.parseColor("#FFE28A")
                   else -> Color.parseColor("#FF9B9B") }))
        cd.addView(tv("連勝　いま ${sp.getInt("win_streak", 0)} / 最高 ${sp.getInt("best_streak", 0)}",
            14f, true, Color.parseColor("#FF9B6B")))
        cd.addView(space(dp(6)))
        // 勝率バー
        val barBg = LinearLayout(this)
        barBg.setBackgroundColor(Color.argb(60, 255, 255, 255))
        val bar = View(this)
        bar.setBackgroundColor(Color.parseColor("#A8E6A1"))
        barBg.addView(bar, LinearLayout.LayoutParams(0, dp(14), rate.toFloat().coerceAtLeast(1f)))
        val barPad = View(this)
        barBg.addView(barPad, LinearLayout.LayoutParams(0, dp(14), (100 - rate).toFloat()))
        cd.addView(barBg, LinearLayout.LayoutParams(-1, dp(14)))
        cd.addView(space(dp(12)))

        // 役職ごとの解放状況（役職図鑑と連動）
        cd.addView(tv("役職デビュー", 15f, true, Color.parseColor("#A8D8FF")))
        val seenCount = Role.values().count { sp.getBoolean("role_seen_${it.name}", false) }
        cd.addView(tv("経験した役職　$seenCount / ${Role.values().size} 種", 14f))
        for (r in Role.values()) {
            val seen = sp.getBoolean("role_seen_${r.name}", false)
            if (seen) {
                val rp = sp.getInt("rolep_${r.name}", 0)
                val rw = sp.getInt("rolew_${r.name}", 0)
                val rr = if (rp > 0) rw * 100 / rp else 0
                cd.addView(tv("✔ ${r.jp}　${rw}勝/${rp}戦（$rr%）", 13f, false,
                    Color.parseColor("#C8F0C2")))
            } else {
                cd.addView(tv("・ ${r.jp}", 13f, false, Color.parseColor("#9AA0B5")))
            }
        }
        cd.addView(space(dp(12)))

        // 実績の達成状況
        val achCount = achievementDefs.count { sp.getBoolean("ach_${it.first}", false) }
        cd.addView(tv("実績　$achCount / ${achievementDefs.size} 解放", 15f, true,
            Color.parseColor("#A8D8FF")))

        cd.addView(space(dp(14)))
        setQuickNext("もどる") { showTitle() }
        cd.addView(btn("タイトルへ", Color.parseColor("#D8703D")) { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- 役職図鑑 ----------

    // ---------- エンディング図鑑 ----------

    private fun showEndingZukan() {
        val sp = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
        val pn = panel()
        val cd = card()
        cd.addView(tv("🏁 エンディング図鑑", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(tv("たどり着いた結末を集めよう", 12f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(10)))

        // (勝敗コード, 絵文字, 名前, 説明)
        val endings = listOf(
            listOf("1", "🎉", "村の平和", "人狼をすべて追放し、村に平和が戻った。"),
            listOf("2", "🐺", "闇夜の勝利", "人狼が村を支配した。"),
            listOf("3", "🦊", "妖狐の暗躍", "第三勢力・妖狐が最後まで生き残った。"),
            listOf("4", "💕", "永遠の恋", "恋人2人だけが生き残り、幸せをつかんだ。")
        )
        val got = endings.count { sp.getBoolean("ending_${it[0]}", false) }
        cd.addView(tv("収集 $got / ${endings.size}", 13f, true, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(8)))

        for (e in endings) {
            val seen = sp.getBoolean("ending_${e[0]}", false)
            val box = card()
            if (seen) {
                box.addView(tv("${e[1]} ${e[2]}", 16f, true, Color.parseColor("#FFE28A")))
                box.addView(tv(e[3], 13f))
            } else {
                box.addView(tv("🔒 ？？？", 16f, true, Color.parseColor("#6B7280")))
                box.addView(tv("まだ見ていないエンディング", 13f, false, Color.parseColor("#9AA0B5")))
            }
            cd.addView(box)
            cd.addView(space(dp(8)))
        }
        if (!sp.getBoolean("ending_4", false)) {
            cd.addView(tv("💡 ヒント: 恋人ルールをONにすると、新しい結末が待っているかも？",
                12f, false, Color.parseColor("#FF9BD0")))
            cd.addView(space(dp(6)))
        }

        setQuickNext("もどる") { showTitle() }
        cd.addView(btn("タイトルへ", Color.parseColor("#D8703D")) { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }

    private fun showRoleZukan() {
        val sp = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
        val pn = panel()
        val cd = card()
        cd.addView(tv("🎭 役職図鑑", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(tv("その役職で一度でも遊ぶと解放されます", 12f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(10)))

        for (r in Role.values()) {
            val seen = sp.getBoolean("role_seen_${r.name}", false)
            val faction = when {
                r.isWolf -> "人狼陣営"
                r.wolfSide -> "人狼陣営（人間）"
                r == Role.FOX_SPIRIT -> "第三陣営"
                else -> "村人陣営"
            }
            val col = when {
                r.isWolf -> Color.parseColor("#FF9B9B")
                r.wolfSide -> Color.parseColor("#FFC98A")
                r == Role.FOX_SPIRIT -> Color.parseColor("#E0A8FF")
                else -> Color.parseColor("#C8F0C2")
            }
            val box = card()
            if (seen) {
                box.addView(tv("${r.jp}　［$faction］", 16f, true, col))
                box.addView(tv(r.desc, 13f))
            } else {
                box.addView(tv("？？？　［$faction］", 16f, true, Color.parseColor("#6B7280")))
                box.addView(tv("まだこの役職で遊んでいません。", 13f, false, Color.parseColor("#9AA0B5")))
            }
            cd.addView(box)
            cd.addView(space(dp(8)))
        }

        cd.addView(space(dp(6)))
        setQuickNext("もどる") { showTitle() }
        cd.addView(btn("タイトルへ", Color.parseColor("#D8703D")) { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- 実績 ----------

    // 実績の定義（id, 名前, 説明）
    private val achievementDefs = listOf(
        Triple("first_win", "はじめての勝利", "初めてゲームに勝った"),
        Triple("win_villager", "村の守り手", "村人陣営で勝利した"),
        Triple("win_wolf", "月夜の狩人", "人狼陣営で勝利した"),
        Triple("win_fox", "闇に紛れし者", "妖狐として単独勝利した"),
        Triple("win_seer", "千里眼", "占い師として勝利した"),
        Triple("detective", "名探偵", "名探偵の称号を手に入れた"),
        Triple("survivor", "生存者", "最後まで生き残って勝利した"),
        Triple("play10", "村の常連", "10回遊んだ"),
        Triple("all_roles", "役者そろい踏み", "すべての役職で遊んだ")
    )

    private fun showAchievements() {
        val sp = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
        val pn = panel()
        val cd = card()
        val unlocked = achievementDefs.count { sp.getBoolean("ach_${it.first}", false) }
        cd.addView(tv("🏅 実績", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(tv("解放 $unlocked / ${achievementDefs.size}", 13f, true, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(10)))

        for ((id, name, desc) in achievementDefs) {
            val got = sp.getBoolean("ach_$id", false)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(6), 0, dp(6))
            val icon = tv(if (got) "🏅" else "🔒", 26f)
            icon.setPadding(0, 0, dp(10), 0)
            row.addView(icon)
            val info = LinearLayout(this)
            info.orientation = LinearLayout.VERTICAL
            if (got) {
                info.addView(tv(name, 15f, true, Color.parseColor("#FFE28A")))
                info.addView(tv(desc, 12f, false, Color.WHITE))
            } else {
                info.addView(tv(name, 15f, true, Color.parseColor("#9AA0B5")))
                info.addView(tv(desc, 12f, false, Color.parseColor("#9AA0B5")))
            }
            row.addView(info)
            cd.addView(row)
            val sep = View(this)
            sep.setBackgroundColor(Color.argb(40, 255, 255, 255))
            cd.addView(sep, LinearLayout.LayoutParams(-1, dp(1)))
        }

        cd.addView(space(dp(12)))
        setQuickNext("もどる") { showTitle() }
        cd.addView(btn("タイトルへ", Color.parseColor("#D8703D")) { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- キャラ図鑑（好感度） ----------

    private fun showZukan() {
        val sp = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
        val pn = panel()
        val cd = card()
        cd.addView(tv("📖 キャラ図鑑", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(tv("一緒に遊ぶ・そのコで勝つと好感度が上がります", 12f, false,
            Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(10)))

        Animal.values().forEachIndexed { i, an ->
            val name = if (humanMode) GameEngine.HUMAN_NAMES[i] else GameEngine.NAMES[i]
            val met = sp.getInt("met_$i", 0)       // 一緒に遊んだ回数
            val won = sp.getInt("won_$i", 0)       // そのコが村の勝ちに貢献
            val fav = favValue(sp, i)              // 好感度(0-100)

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(6), 0, dp(6))

            val pl = Player(i, name, an)
            if (met == 0) {
                // 未遭遇はシルエット（グレー）扱い
                val ph = tv("？", 30f, true, Color.parseColor("#6B7280"))
                ph.gravity = Gravity.CENTER
                val box = FrameLayout(this)
                box.addView(ph, FrameLayout.LayoutParams(dp(56), dp(56)).also { it.gravity = Gravity.CENTER })
                row.addView(box)
            } else {
                row.addView(charView(an, true),
                    LinearLayout.LayoutParams(dp(56), dp(56)))
            }

            val info = LinearLayout(this)
            info.orientation = LinearLayout.VERTICAL
            info.setPadding(dp(10), 0, 0, 0)
            if (met == 0) {
                info.addView(tv("？？？（${an.jp}）", 15f, true, Color.parseColor("#9AA0B5")))
                info.addView(tv("まだ出会っていない", 12f, false, Color.parseColor("#9AA0B5")))
            } else {
                info.addView(tv("$name（${an.jp}・${an.persona}）", 15f, true))
                // 好感度バー（ハート）
                val hearts = (fav / 20)   // 0-5
                val heartStr = "❤".repeat(hearts) + "♡".repeat(5 - hearts)
                info.addView(tv("好感度 $heartStr  ($fav)", 13f, false, Color.parseColor("#FFC9C9")))
                info.addView(tv("遊んだ回数 ${met}　勝利貢献 ${won}", 11f, false,
                    Color.parseColor("#BFD0FF")))
                info.addView(btn("💬 会いに行く", Color.parseColor("#7A4FD8")) { showChat(i) })
            }
            row.addView(info)
            cd.addView(row)

            val sep = View(this)
            sep.setBackgroundColor(Color.argb(40, 255, 255, 255))
            cd.addView(sep, LinearLayout.LayoutParams(-1, dp(1)))
        }

        cd.addView(space(dp(14)))
        setQuickNext("もどる") { showTitle() }
        cd.addView(btn("タイトルへ", Color.parseColor("#D8703D")) { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- AIチャット（雑談・プレゼント） ----------

    private var chatLog = ArrayList<Pair<Boolean, String>>()   // (自分か, テキスト)

    private fun showChat(animalIdx: Int) {
        val sp = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
        val an = Animal.values()[animalIdx]
        val name = if (humanMode) GameEngine.HUMAN_NAMES[animalIdx] else GameEngine.NAMES[animalIdx]
        val fav = favValue(sp, animalIdx)
        val coins = sp.getInt("coins", 0)

        val pn = panel()
        val cd = card()
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(charView(an, true), LinearLayout.LayoutParams(dp(64), dp(64)))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.setPadding(dp(10), 0, 0, 0)
        head.addView(tv("$name（${an.jp}・${an.persona}）", 17f, true))
        val hearts = fav / 20
        head.addView(tv("好感度 " + "❤".repeat(hearts) + "♡".repeat(5 - hearts) + "  ($fav)",
            13f, false, Color.parseColor("#FFC9C9")))
        row.addView(head)
        cd.addView(row)
        cd.addView(space(dp(8)))

        // チャットログ
        val logBox = card()
        if (chatLog.isEmpty()) {
            logBox.addView(tv(chatGreeting(name, fav), 14f))
        } else {
            for ((mine, text) in chatLog) {
                val b = tv((if (mine) "あなた: " else "$name: ") + text, 14f, mine,
                    if (mine) Color.parseColor("#BFD0FF") else Color.WHITE)
                logBox.addView(b)
                logBox.addView(space(dp(4)))
            }
        }
        cd.addView(logBox)
        cd.addView(space(dp(10)))

        // 話しかけるボタン（好感度で反応が変わる。話すと少しだけ好感度が上がる）
        cd.addView(tv("話しかける", 14f, true, Color.parseColor("#A8D8FF")))
        val topics = listOf("あいさつする", "人狼のコツを聞く", "ほめる")
        for (topic in topics) {
            cd.addView(btn(topic, Color.parseColor("#3D6BD8")) {
                chatLog.add(true to topic)
                chatLog.add(false to chatReply(name, an, topic, favValue(sp, animalIdx)))
                // 話すと好感度+1（1日1回上限などは省略しライトに）
                sp.edit().putInt("gift_$animalIdx",
                    (sp.getInt("gift_$animalIdx", 0) + 1)).apply()
                showChatRefresh(animalIdx)
            })
            cd.addView(space(dp(4)))
        }
        cd.addView(space(dp(8)))

        // プレゼント（コインを使って好感度を上げる）
        cd.addView(tv("🎁 プレゼント（好感度アップ）", 14f, true, Color.parseColor("#FFD450")))
        cd.addView(tv("所持コイン: 🪙$coins", 12f, false, Color.parseColor("#BFD0FF")))
        val gifts = listOf(Triple("木の実", 20, 5), Triple("お花", 50, 12), Triple("ごちそう", 100, 25))
        for ((gname, price, up) in gifts) {
            if (coins >= price) {
                cd.addView(btn("$gname をあげる（🪙$price → 好感度+$up）", Color.parseColor("#D8703D")) {
                    sp.edit()
                        .putInt("coins", coins - price)
                        .putInt("gift_$animalIdx", sp.getInt("gift_$animalIdx", 0) + up)
                        .apply()
                    chatLog.add(true to "$gname をプレゼントした")
                    chatLog.add(false to giftReaction(name, gname))
                    showChatRefresh(animalIdx)
                })
            } else {
                cd.addView(tv("$gname （🪙$price・コインが足りません）", 12f, false,
                    Color.parseColor("#9AA0B5")))
            }
            cd.addView(space(dp(4)))
        }

        cd.addView(space(dp(12)))
        setQuickNext("もどる") { chatLog = ArrayList(); showZukan() }
        cd.addView(btn("図鑑へもどる", Color.parseColor("#D8703D")) {
            chatLog = ArrayList(); showZukan()
        })
        pn.addView(cd)
        setScreen(pn)
    }

    private fun showChatRefresh(animalIdx: Int) = showChat(animalIdx)

    private fun chatGreeting(name: String, fav: Int): String = when {
        fav >= 80 -> "$name「わぁ、来てくれたんだ！あなたとお話しするの、大すき！」"
        fav >= 40 -> "$name「やあ、こんにちは。今日はどうしたの？」"
        fav >= 15 -> "$name「…あ、どうも。なにか用かな？」"
        else -> "$name「……（まだ少し警戒しているようだ）」"
    }

    private fun chatReply(name: String, an: Animal, topic: String, fav: Int): String {
        val warm = fav >= 50
        return when (topic) {
            "あいさつする" ->
                if (warm) "$name「こんにちは！会えてうれしいよ😊」"
                else "$name「…こんにちは。」"
            "人狼のコツを聞く" ->
                if (warm) "$name「占い師が2人出たら、片方は必ず偽物だよ。発言の食い違いをよく見るといいよ！」"
                else "$name「コツ…？うーん、自分で考えるのも大事だと思うな。」"
            "ほめる" ->
                if (warm) "$name「えへへ、そんなにほめても何も出ないよ〜♪」"
                else "$name「…どうも。おせじでもうれしいけど。」"
            else -> "$name「……？」"
        }
    }

    private fun giftReaction(name: String, gift: String): String =
        "$name「わぁ、$gift だ！ありがとう、大切にするね！（好感度が上がった）」"

    // ---------- ルール図解用の部品 ----------

    // 1日の流れの1ステップ（アイコン＋見出し＋説明）
    private fun flowStep(icon: String, title: String, lines: List<String>, accent: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.setPadding(dp(10), dp(10), dp(10), dp(10))
        box.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(46, 255, 255, 255))
            setStroke(dp(2), Color.parseColor(accent))
        }
        val ic = tv(icon, 26f)
        ic.setPadding(0, 0, dp(10), 0)
        box.addView(ic)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(title, 15f, true, Color.parseColor(accent)))
        for (l in lines) col.addView(tv("・$l", 13f))
        box.addView(col)
        return box
    }

    // ステップ間をつなぐ下向き矢印
    private fun flowArrow(): TextView {
        val t = tv("▼", 16f, true, Color.parseColor("#8FA0C8"))
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(2), 0, dp(2))
        return t
    }

    // セクションの見出し帯
    private fun sectionBar(text: String, color: String): TextView {
        val t = tv(text, 16f, true, Color.WHITE)
        t.setPadding(dp(12), dp(8), dp(12), dp(8))
        t.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor(color))
        }
        return t
    }

    // 役職カード（アイコン・名前・人数・能力）
    private fun roleCard(icon: String, name: String, count: String, desc: String,
                         accent: String): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), dp(7), dp(8), dp(7))
        row.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(38, 255, 255, 255))
            setStroke(dp(1), Color.parseColor(accent))
        }
        val ic = tv(icon, 20f)
        ic.setPadding(0, 0, dp(8), 0)
        row.addView(ic)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(tv(name, 14f, true, Color.parseColor(accent)))
        val cnt = tv(" $count", 12f, true, Color.parseColor("#FFE28A"))
        head.addView(cnt)
        col.addView(head)
        col.addView(tv(desc, 12f, false, Color.parseColor("#DCE4FF")))
        row.addView(col)
        return row
    }

    // 勝利条件の対戦表カード
    private fun winCard(icon: String, team: String, cond: String, accent: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(dp(8), dp(10), dp(8), dp(10))
        box.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(40, 255, 255, 255))
            setStroke(dp(2), Color.parseColor(accent))
        }
        val i = tv(icon, 26f); i.gravity = Gravity.CENTER
        box.addView(i)
        val t = tv(team, 13f, true, Color.parseColor(accent)); t.gravity = Gravity.CENTER
        box.addView(t)
        val c = tv(cond, 11f, false, Color.WHITE); c.gravity = Gravity.CENTER
        box.addView(c)
        return box
    }

    private fun showRules() {
        val pn = panel()
        val cd = card()
        cd.addView(tv("📖 ルール", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(tv("9人の中にひそむ2匹の人狼を、話し合いと投票で見つけ出そう！",
            13f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(8)))
        val toc = LinearLayout(this)
        toc.orientation = LinearLayout.VERTICAL
        toc.setPadding(dp(10), dp(8), dp(10), dp(8))
        toc.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(40, 255, 255, 255))
        }
        toc.addView(tv("このページの内容", 12f, true, Color.parseColor("#FFE28A")))
        toc.addView(tv("🏆 勝利条件 → 🔄 ゲームの流れ → 🎭 役職 → 🔎 占いの早見表 → ✨ 特別な仕組み → ⚙️ オプション",
            12f, false, Color.parseColor("#DCE4FF")))
        cd.addView(toc)
        cd.addView(space(dp(12)))

        // ============ 勝利条件（対戦カード） ============
        cd.addView(sectionBar("🏆 勝利条件", "#3D6BD8"))
        cd.addView(space(dp(8)))
        val winRow = LinearLayout(this)
        winRow.orientation = LinearLayout.HORIZONTAL
        val w1 = LinearLayout.LayoutParams(0, -2, 1f).also { it.setMargins(0, 0, dp(4), 0) }
        val w2 = LinearLayout.LayoutParams(0, -2, 1f).also { it.setMargins(dp(4), 0, 0, 0) }
        winRow.addView(winCard("🏘️", "村人チーム", "人狼を全員\n処刑すれば勝ち", "#A8E6A1"), w1)
        winRow.addView(winCard("🐺", "人狼チーム", "人狼の数が村人側と\n同数になれば勝ち", "#FF9B9B"), w2)
        cd.addView(winRow)
        cd.addView(space(dp(6)))
        val winRow2 = LinearLayout(this)
        winRow2.orientation = LinearLayout.HORIZONTAL
        winRow2.addView(winCard("🦊", "妖狐（第三）", "決着時に生きていれば\n単独勝ち", "#E0A8FF"), w1)
        winRow2.addView(winCard("💕", "恋人（第三）", "2人だけ生き残れば\n2人の勝ち", "#FF9BD0"), w2)
        cd.addView(winRow2)
        cd.addView(space(dp(6)))
        cd.addView(tv("※ 恋人はオプションでONにしたときだけ登場します",
            11f, false, Color.parseColor("#9AA0B5")))
        cd.addView(space(dp(16)))

        // ============ 1日の流れ（フロー図） ============
        cd.addView(sectionBar("🔄 ゲームの流れ", "#7A4FD8"))
        cd.addView(space(dp(8)))
        cd.addView(tv("【 1日目だけ 】", 12f, true, Color.parseColor("#FFE28A")))
        cd.addView(space(dp(4)))
        cd.addView(flowStep("🗣️", "自己紹介", listOf(
            "1番から順に全員が名乗る",
            "「人狼ではない」と言う人が4〜5人、言わない人が残り",
            "人狼は 言った組に1人・言わない組に1人 必ずいる",
            "どちらだったか覚えておくと終盤で絞り込める"), "#FFD08A"))
        cd.addView(flowArrow())
        cd.addView(tv("【 ここから毎日くり返し 】", 12f, true, Color.parseColor("#FFE28A")))
        cd.addView(space(dp(4)))
        cd.addView(flowStep("🔮", "占い師フェーズ（2日目〜）", listOf(
            "占い師を名乗る人が結果を発表",
            "人狼や狂人が偽占い師として出ることも",
            "名乗り出るのは最初の1回だけ"), "#C9B6FF"))
        cd.addView(flowArrow())
        cd.addView(flowStep("☕", "自由会話", listOf(
            "みんなが自由に発言（無言の人も）",
            "キャラをタップして「人狼だと思う相手」を伝えられる（1日1回）",
            "相手が人狼で相方を教えると、夜に狙われる！"), "#A8D8FF"))
        cd.addView(flowArrow())
        cd.addView(flowStep("💬", "話し合い", listOf(
            "全員で議論。疑い・信頼の発言が飛び交う",
            "怪しい人に🚩旗を立てられる（最大＝生存人狼数）",
            "👀は今いちばん疑われている人の印"), "#FFE28A"))
        cd.addView(flowArrow())
        cd.addView(flowStep("⚖️", "投票 → 処刑", listOf(
            "全員が1人に投票し、最多票の人が処刑される",
            "1日目の昼は処刑なし（手がかりがないため）"), "#FF9B9B"))
        cd.addView(flowArrow())
        cd.addView(flowStep("🌙", "夜", listOf(
            "🐺 人狼が1人を襲撃",
            "🔮 占い師が1人を占う",
            "🛡️ 狩人が1人を護衛（守れれば犠牲者なし）"), "#5A4FD8"))
        cd.addView(flowArrow())
        cd.addView(flowStep("☀️", "朝", listOf(
            "襲撃された人が判明（護衛成功なら0人）",
            "👻 霊能者は前日処刑された人の正体を知る"), "#FFD450"))
        cd.addView(space(dp(4)))
        val loop = tv("⤴ 決着がつくまでくり返し", 12f, true, Color.parseColor("#8FA0C8"))
        loop.gravity = Gravity.CENTER
        cd.addView(loop)
        cd.addView(space(dp(16)))

        // ============ 役職一覧（陣営ごと） ============
        cd.addView(sectionBar("🎭 役職（全9人）", "#3D9E6B"))
        cd.addView(space(dp(8)))
        cd.addView(tv("🏘️ 村人チーム", 14f, true, Color.parseColor("#A8E6A1")))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("🧑‍🌾", "村人", "×2", "能力なし。推理と投票で村を守る", "#C8F0C2"))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("🔮", "占い師", "×1", "毎晩1人を占い、人狼かどうか分かる", "#C9B6FF"))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("👻", "霊能者", "×1", "処刑された人が人狼だったか分かる", "#A8D8FF"))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("🛡️", "狩人", "×1", "毎晩1人を襲撃から守る", "#8FD0FF"))
        cd.addView(space(dp(10)))
        cd.addView(tv("🐺 人狼チーム", 14f, true, Color.parseColor("#FF9B9B")))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("🐺", "人狼", "×2", "毎晩1人を襲撃。仲間が誰か分かる", "#FF9B9B"))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("🌀", "狂人", "×1", "人間だが人狼の味方。占いでは白と出る／仲間は知らない", "#FFC98A"))
        cd.addView(space(dp(10)))
        cd.addView(tv("🦊 第三陣営", 14f, true, Color.parseColor("#E0A8FF")))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("🦊", "妖狐", "×1", "占われると死ぬが襲撃では死なない。最後まで生き残れば単独勝利", "#E0A8FF"))
        cd.addView(space(dp(10)))
        cd.addView(tv("＋オプションで追加", 13f, true, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("🤝", "共有者", "×2", "村人2枠と交代。お互いが誰か分かり、確実に人狼ではないと確認できる", "#A8E6A1"))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("💕", "恋人", "×2", "役職とは別。片方が死ぬともう片方も後を追う", "#FF9BD0"))
        cd.addView(space(dp(16)))

        // ============ 占いの見分け方（早見表） ============
        cd.addView(sectionBar("🔎 占い結果の早見表", "#D8703D"))
        cd.addView(space(dp(8)))
        cd.addView(tv("占い師が「人狼」と判定するのは人狼だけ。それ以外は全員「人狼ではない」と出ます。",
            12f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(6)))
        cd.addView(roleCard("⚫", "人狼", "→ 人狼！", "唯一の黒判定", "#FF9B9B"))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("⚪", "村人・占い師・霊能者・狩人・共有者", "→ 人狼ではない", "正真正銘の白", "#C8F0C2"))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("⚪", "狂人", "→ 人狼ではない", "白と出るが人狼の味方！注意", "#FFC98A"))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("💀", "妖狐", "→ 人狼ではない", "白と出るが、占われた本人は死んでしまう", "#E0A8FF"))
        cd.addView(space(dp(16)))

        // ============ 特別な仕組み ============
        cd.addView(sectionBar("✨ 特別な仕組み", "#7A9BD8"))
        cd.addView(space(dp(8)))
        cd.addView(flowStep("🎩", "名探偵", listOf(
            "投票で2回連続人狼を当てると「名探偵」に",
            "以降みんなが名探偵の予想に同調して投票する",
            "名探偵を説得できれば村全体の票が動く",
            "人狼が説得すると2回に1回バレる！"), "#FFE28A"))
        cd.addView(space(dp(6)))
        cd.addView(flowStep("🚩", "旗と👀マーク", listOf(
            "怪しい人に旗を立てられる（最大＝生存している人狼の数）",
            "旗を立てた相手には、信用があれば仲間が同調してくれる",
            "👀はその時いちばん疑われている人の印"), "#FF9B9B"))
        cd.addView(space(dp(6)))
        cd.addView(flowStep("📓", "推理ノート・ヒント", listOf(
            "話し合い画面から、発言・投票・占い結果の履歴を確認できる",
            "🔍ヒントでは今の状況からAIが推理の助言をくれる"), "#A8D8FF"))
        cd.addView(space(dp(16)))

        // ============ オプション ============
        cd.addView(sectionBar("⚙️ オプション", "#3D6BD8"))
        cd.addView(space(dp(8)))
        cd.addView(tv("タイトルの「⚙️オプション」から設定できます。", 12f, false,
            Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(6)))
        cd.addView(roleCard("👤", "人モード", "", "どうぶつのかわりに9人の人間キャラで遊ぶ", "#7A9BD8"))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("🎚️", "難易度", "易/普/難", "むずかしいほどCPUの推理と連携が賢くなる", "#FFE28A"))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("①", "番号表示", "", "9人に固定の番号をつけて見分けやすくする", "#FFD08A"))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("📊", "生存数・有利率", "", "残り人数や人狼の有利率を画面に表示", "#A8D8FF"))
        cd.addView(space(dp(4)))
        cd.addView(roleCard("▶", "1人ずつ送り", "", "会話をまとめず1人ずつ順番に表示する", "#C8F0C2"))
        cd.addView(space(dp(18)))

        cd.addView(space(dp(14)))
        setQuickNext("もどる") { showTitle() }
        cd.addView(btn("タイトルへ戻る") { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- ゲーム開始 / 役職確認 ----------

    private fun startGame() {
        showExitButton = true
        val sp = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
        engine = GameEngine()
        // setup()の前にルールを注入する（役職構成・恋人・名前に影響するため）
        engine.masonRule = sp.getBoolean("mason_rule", false)
        engine.loversRule = sp.getBoolean("lovers_rule", false)
        engine.humanCast = sp.getBoolean("human_mode", false)
        engine.setup()
        engine.difficulty = sp.getInt("difficulty", 1)
        optNumbering = sp.getBoolean("opt_numbering", false)
        optCounter = sp.getBoolean("opt_counter", true)
        optAnalysis = sp.getBoolean("opt_analysis", false)
        optWinrate = sp.getBoolean("opt_winrate", false)
        optOnebyone = sp.getBoolean("opt_onebyone", false)
        optQuickNext = sp.getBoolean("opt_quicknext", true)
        humanMode = sp.getBoolean("human_mode", false)
        // 各キャラの好感度（遊んだ回数・勝利貢献・プレゼント）をエンジンへ注入
        for (i in Animal.values().indices) {
            engine.favByAnimal[i] = favValue(sp, i)
        }
        currentTalks = ArrayList()
        predictedWolves = LinkedHashSet()
        predictionActive = false
        moodVictory = 0
        loggedTalkDay = -1
        showRoleReveal()
    }

    // 好感度（0-100）= 遊んだ回数×5 + 勝利貢献×15 + プレゼント
    private fun favValue(sp: android.content.SharedPreferences, animalIdx: Int): Int {
        val met = sp.getInt("met_$animalIdx", 0)
        val won = sp.getInt("won_$animalIdx", 0)
        val gift = sp.getInt("gift_$animalIdx", 0)
        return (met * 5 + won * 15 + gift).coerceAtMost(100)
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
        val roleColor = when {
            h.role.wolfSide -> Color.parseColor("#FF9B9B")
            h.role == Role.FOX_SPIRIT -> Color.parseColor("#E0A8FF")
            else -> Color.parseColor("#A8E6A1")
        }
        val roleT = tv("役職: ${h.role.jp}", 22f, true, roleColor)
        roleT.gravity = Gravity.CENTER
        cd.addView(roleT)
        cd.addView(tv(h.role.desc, 14f))
        if (h.role.isWolf) {
            val partner = engine.players.first { it.role.isWolf && it.id != h.id }
            cd.addView(space(dp(6)))
            cd.addView(tv("🐺 仲間の人狼: ${partner.pname}（${partner.animal.jp}）",
                15f, true, Color.parseColor("#FF9B9B")))
        } else if (h.role == Role.MADMAN) {
            cd.addView(space(dp(6)))
            cd.addView(tv("🌀 あなたは狂人。人狼が誰かは分かりません。\n占いでは白（人狼ではない）と出ます。人狼が勝てば、あなたの勝ちです。",
                14f, true, Color.parseColor("#FFC98A")))
        } else if (h.role == Role.FOX_SPIRIT) {
            cd.addView(space(dp(6)))
            cd.addView(tv("🦊 あなたは妖狐。占い師に占われると死んでしまいます。処刑と占いにだけ気をつけて、最後まで生き残りましょう。",
                14f, true, Color.parseColor("#E0A8FF")))
        } else if (h.role == Role.MASON) {
            val mate = engine.masonPartner(h)
            cd.addView(space(dp(6)))
            if (mate != null) {
                cd.addView(tv("🤝 もう1人の共有者: ${mate.pname}（${mate.animal.jp}）\n${mate.pname} は人狼ではないと確定しています。協力しましょう。",
                    15f, true, Color.parseColor("#A8E6A1")))
            }
        }
        // 恋人（役職とは別軸）
        if (engine.isLover(h)) {
            val lover = engine.loverPartner(h)
            if (lover != null) {
                cd.addView(space(dp(6)))
                cd.addView(tv("💕 あなたの恋人: ${lover.pname}（${lover.animal.jp}）\n2人だけが最後まで生き残れば、陣営に関係なく恋人の勝利！ただし相手が死ぬと、あなたも後を追ってしまいます。",
                    14f, true, Color.parseColor("#FF9BD0")))
            }
        }
        cd.addView(space(dp(16)))
        setQuickNext("昼へ") {
            currentTalks = ArrayList()
            introTalks = ArrayList(engine.buildIntroTalks())
            introReveal = 1
            showIntro()
        }
        cd.addView(btn("1日目の昼へ", Color.parseColor("#D8703D")) {
            currentTalks = ArrayList()
            introTalks = ArrayList(engine.buildIntroTalks())
            introReveal = 1
            showIntro()
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
        setQuickNext("朝へ") { finishNight(null, null, null) }
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
        setQuickNext("おまかせ") {
            confirmRandomPick("襲撃する相手", cands) { t -> finishNight(t, null, null) }
        }
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
        setQuickNext("おまかせ") {
            confirmRandomPick("占う相手", cands) { t -> finishNight(null, t, null) }
        }
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
        setQuickNext("おまかせ") {
            confirmRandomPick("護衛する相手", cands) { t -> finishNight(null, null, t) }
        }
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
        setQuickNext("朝へ") { showMorning() }
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
            setQuickNext("結果へ") { showGameOver(w) }
            cd.addView(btn("結果を見る", Color.parseColor("#D8703D")) { showGameOver(w) })
        } else if (v != null && v.id == e.humanId) {
            // あなたが襲撃された → やられた画面へ
            setQuickNext("次へ") {
                nightNextAfterDeath = false
                showHumanDead("昨夜、人狼に襲撃されてしまった…") { showSeerPhase() }
            }
            cd.addView(btn("次へ", Color.parseColor("#5A4FD8")) {
                nightNextAfterDeath = false   // 次は昼フェーズから
                showHumanDead("昨夜、人狼に襲撃されてしまった…") { showSeerPhase() }
            })
        } else {
            setQuickNext("占い師フェーズへ") { showSeerPhase() }
            cd.addView(btn("🔮 占い師フェーズへ") { showSeerPhase() })
        }
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- 占い師フェーズ（2日目の朝から・昼の前） ----------

    // ---------- 自己紹介フェーズ（1日目） ----------

    private var introTalks = ArrayList<Talk>()
    private var introReveal = 0

    private fun showIntro() {
        val e = engine
        val pn = panel()
        val cd = card()
        cd.addView(tv("🗣️ 自己紹介", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(tv("1番から順に名乗ります。「自分は人狼ではない」と言う人・言わない人がいます。よく覚えておきましょう。",
            12f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(10)))

        val shown = introReveal.coerceIn(0, introTalks.size)
        for (i in 0 until shown) {
            val t = introTalks[i]
            val num = "①②③④⑤⑥⑦⑧⑨".getOrNull(e.players[t.speakerId].animal.ordinal)?.toString() ?: ""
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val numTv = tv(num, 20f, true, Color.parseColor("#FFE28A"))
            numTv.setPadding(0, 0, dp(6), 0)
            row.addView(numTv)
            row.addView(talkBubble(t), LinearLayout.LayoutParams(0, -2, 1f))
            cd.addView(row)
            cd.addView(space(dp(8)))
        }

        if (shown < introTalks.size) {
            setQuickNext("次の人") {
                introReveal = (introReveal + 1).coerceAtMost(introTalks.size)
                showIntro()
            }
            cd.addView(btn("次の人の自己紹介をきく ▶", Color.parseColor("#3D6BD8")) {
                introReveal = (introReveal + 1).coerceAtMost(introTalks.size)
                showIntro()
            })
            cd.addView(space(dp(6)))
            cd.addView(tv("（$shown / ${introTalks.size} 人）", 12f, false, Color.parseColor("#BFD0FF")))
            pn.addView(cd)
            setScreen(pn, shown > 1)
            return
        }

        // 全員終わったら、まとめて振り返れる一覧を表示
        cd.addView(space(dp(6)))
        cd.addView(tv("📋 「人狼ではない」と言った人", 15f, true, Color.parseColor("#A8E6A1")))
        val said = e.players.filter { e.introDenied.contains(it.id) }
        cd.addView(tv(said.joinToString("、") { it.pname }, 14f, false, Color.parseColor("#C8F0C2")))
        cd.addView(space(dp(6)))
        cd.addView(tv("🤐 何も言わなかった人", 15f, true, Color.parseColor("#FFD08A")))
        val silent = e.players.filter { !e.introDenied.contains(it.id) }
        cd.addView(tv(silent.joinToString("、") { it.pname }, 14f, false, Color.parseColor("#FFE0B0")))
        cd.addView(space(dp(8)))
        cd.addView(tv("💡 人狼は「言った人」の中に1人、「言わなかった人」の中に1人ひそんでいます。",
            13f, true, Color.parseColor("#FF9B9B")))
        cd.addView(space(dp(14)))
        setQuickNext("話し合いへ") { startFreeTalk() }
        cd.addView(btn("話し合いへ", Color.parseColor("#D8703D")) { startFreeTalk() })
        pn.addView(cd)
        setScreen(pn)
    }

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
            setQuickNext("隠れる") {
                e.ensureSeerPhase(false)
                renderSeerPhase()
            }
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
        setQuickNext("自由会話へ") { startFreeTalk() }
        cd.addView(btn("自由会話へ") { startFreeTalk() })
        pn.addView(cd)
        setScreen(pn)
    }

    private fun toggleFlag(pl: Player) {
        val e = engine
        if (e.flags.contains(pl.id)) {
            e.flags.remove(pl.id)
        } else {
            // 生存人狼が減って上限が下がった場合、古い旗を落として調整
            while (e.flags.size >= e.maxFlags() && e.flags.isNotEmpty()) {
                e.flags.remove(e.flags.first())
            }
            if (e.flags.size < e.maxFlags()) e.flags.add(pl.id)
        }
        showDay()
    }

    private var loggedTalkDay = -1

    // 生存数カウンター（②）と人狼有利率（④）を上部に表示
    private fun addStatusHeader(cd: LinearLayout) {
        val e = engine
        if (optCounter) {
            val living = e.alive()
            val wolves = living.count { it.role.isWolf }
            val others = living.size - wolves
            val row = tv("🐺 人狼 のこり $wolves　｜　🏘️ それ以外 のこり $others", 14f, true,
                Color.parseColor("#FFE28A"))
            row.gravity = Gravity.CENTER
            cd.addView(row)
        }
        if (optWinrate) {
            val adv = e.wolfAdvantage()
            cd.addView(space(dp(4)))
            val label = tv("🤖 AI推論：人狼の有利率 $adv%", 13f, true,
                when { adv >= 60 -> Color.parseColor("#FF9B9B")
                       adv >= 40 -> Color.parseColor("#FFE28A")
                       else -> Color.parseColor("#A8E6A1") })
            label.gravity = Gravity.CENTER
            cd.addView(label)
            // 有利率バー
            val barBg = LinearLayout(this)
            barBg.setBackgroundColor(Color.argb(60, 255, 255, 255))
            val bar = View(this)
            bar.setBackgroundColor(Color.parseColor("#FF9B9B"))
            barBg.addView(bar, LinearLayout.LayoutParams(0, dp(10), adv.toFloat()))
            val pad = View(this)
            barBg.addView(pad, LinearLayout.LayoutParams(0, dp(10), (100 - adv).toFloat()))
            cd.addView(barBg, LinearLayout.LayoutParams(-1, dp(10)))
        }
        if (optCounter || optWinrate) cd.addView(space(dp(8)))
    }

    private fun showDay() {
        val e = engine
        val h = e.human()
        e.computeMostSuspected(currentTalks)   // 今ターン最も疑われている2キャラを更新
        if (loggedTalkDay != e.dayCount) {     // 当日の会話を推理ノートへ一度だけ記録
            e.logTalks(e.dayCount, currentTalks)
            loggedTalkDay = e.dayCount
        }
        val pn = panel()
        val cd = card()
        cd.addView(tv("💬 ${e.dayCount}日目の昼 - 話し合い", 19f, true, Color.parseColor("#FFE28A")))
        addStatusHeader(cd)
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
        cd.addView(space(dp(6)))
        cd.addView(btn("📓 推理ノート", Color.parseColor("#3D6BD8")) { showNoteDialog() })
        cd.addView(space(dp(6)))
        cd.addView(btn("🔍 ヒント（AIアシスタント）", Color.parseColor("#7A4FD8")) { showHintDialog() })

        // 🚩 旗機能（生きているあなただけ操作可能）
        if (h.alive) {
            cd.addView(space(dp(8)))
            cd.addView(tv("🚩 怪しいと思う動物に旗を立てる（最大 ${e.maxFlags()} 本）",
                13f, true, Color.parseColor("#FFC9C9")))
            cd.addView(tv("残り旗: ${e.maxFlags() - e.flags.size} 本　（タップで付け外し）",
                12f, false, Color.parseColor("#BFD0FF")))
            val cands = e.alive().filter { it.id != e.humanId }
            cd.addView(charGrid(cands, 56, 4) { pl -> toggleFlag(pl) })
            cd.addView(tv("👀 = 今もっとも疑われている動物", 11f, false, Color.parseColor("#BFD0FF")))
        }

        cd.addView(space(dp(10)))

        // 会話：キャラを1列に並べて吹き出しで表示（画面ごと下にスクロール可能）
        val shown = if (optOnebyone) talkReveal.coerceIn(0, currentTalks.size) else currentTalks.size
        for (i in 0 until shown) {
            cd.addView(talkBubble(currentTalks[i]))
            cd.addView(space(dp(8)))
        }
        // ⑤ 1人ずつ送り：まだ残りがあれば「次へ」ボタン
        if (optOnebyone && shown < currentTalks.size) {
            setQuickNext("次の人") {
                talkReveal = (talkReveal + 1).coerceAtMost(currentTalks.size)
                showDay()
            }
            cd.addView(btn("次の人の話をきく ▶", Color.parseColor("#3D6BD8")) {
                talkReveal = (talkReveal + 1).coerceAtMost(currentTalks.size)
                showDay()
            })
            cd.addView(space(dp(8)))
            cd.addView(tv("（${shown} / ${currentTalks.size} 人）", 12f, false, Color.parseColor("#BFD0FF")))
            pn.addView(cd)
            // 2人目以降は、直前の会話と「次へ」ボタンが見える位置（画面下）で固定する
            setScreen(pn, shown > 1)
            return
        }

        // ③ 会話ごとのAI分析図（疑いの構図を図解）
        if (optAnalysis) {
            cd.addView(space(dp(6)))
            cd.addView(tv("🤖 AI分析：会話の全体像", 14f, true, Color.parseColor("#C9B6FF")))
            cd.addView(tv("🐺付きの矢印＝疑っている相手", 11f, false, Color.parseColor("#BFD0FF")))
            e.computeMostSuspected(currentTalks)
            val suspectTalks = currentTalks.filter { it.suspect }
            val dm2 = resources.displayMetrics
            val side = (dm2.widthPixels * 0.8f).toInt()
            cd.addView(SummaryView(this, e, suspectTalks, humanMode, moodVictory != 0),
                LinearLayout.LayoutParams(side, side).also { it.gravity = Gravity.CENTER_HORIZONTAL })
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
        if (e.dayCount == 1) {
            // 1日目は情報がないため処刑なし。話し合いだけして夜へ
            cd.addView(tv("🌙 1日目は手がかりがないので、今夜は投票（処刑）を行いません。", 13f, true,
                Color.parseColor("#BFD0FF")))
            cd.addView(space(dp(8)))
            setQuickNext("夜へ") { beginNight() }
            setQuickNext("夜へ") { beginNight() }
            cd.addView(btn("夜になる", Color.parseColor("#5A4FD8")) { beginNight() })
        } else if (h.alive) {
            setQuickNext("投票へ") { showVote() }
            cd.addView(btn("投票へ進む", Color.parseColor("#D8703D")) { showVote() })
        } else {
            setQuickNext("開票へ") {
                val ex = e.runVote(null)
                showExecution(ex)
            }
            cd.addView(btn("開票へ（観戦）") {
                val ex = e.runVote(null)
                showExecution(ex)
            })
        }
        pn.addView(cd)
        // 1人ずつ送りのときは、会話の続きが見える画面下で固定する
        setScreen(pn, optOnebyone && currentTalks.isNotEmpty())
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
        setQuickNext("おまかせ") {
            confirmRandomPick("投票する相手", cands) { t ->
                val ex = e.runVote(t)
                showExecution(ex)
            }
        }
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
            setQuickNext("結果へ") { showGameOver(w) }
            cd.addView(btn("結果を見る", Color.parseColor("#D8703D")) { showGameOver(w) })
        } else if (ex.id == e.humanId) {
            setQuickNext("次へ") {
                nightNextAfterDeath = true
                showHumanDead("投票で処刑されてしまった…") { beginNight() }
            }
            cd.addView(btn("次へ", Color.parseColor("#5A4FD8")) {
                nightNextAfterDeath = true   // 次は夜から
                showHumanDead("投票で処刑されてしまった…") { beginNight() }
            })
        } else {
            setQuickNext("夜へ") { beginNight() }
            cd.addView(btn("夜になる", Color.parseColor("#5A4FD8")) { beginNight() })
        }
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- ゲーム終了 ----------

    private fun showGameOver(w: Int) {
        night = false
        moodVictory = w    // 1=村人 2=人狼 3=妖狐
        val e = engine
        val h = e.human()
        val humanWin = when (w) {
            1 -> !h.role.wolfSide && h.role != Role.FOX_SPIRIT && !e.isLover(h)
            2 -> h.role.wolfSide && !e.isLover(h)
            3 -> h.role == Role.FOX_SPIRIT
            4 -> e.isLover(h)
            else -> false
        }

        val sp = getSharedPreferences("jinrou", Context.MODE_PRIVATE)
        val editor = sp.edit()
            .putInt("games", sp.getInt("games", 0) + 1)
            .putInt("wins", sp.getInt("wins", 0) + if (humanWin) 1 else 0)
        // 図鑑：登場キャラの「遊んだ回数」+1。勝った陣営に属して生き残ったキャラは「勝利貢献」+1
        for (p2 in e.players) {
            val idx = p2.animal.ordinal
            editor.putInt("met_$idx", sp.getInt("met_$idx", 0) + 1)
            val contributed = p2.alive && when (w) {
                1 -> !p2.role.wolfSide && p2.role != Role.FOX_SPIRIT
                2 -> p2.role.wolfSide
                3 -> p2.role == Role.FOX_SPIRIT
                4 -> e.isLover(p2)
                else -> false
            }
            if (contributed) editor.putInt("won_$idx", sp.getInt("won_$idx", 0) + 1)
        }

        // 役職図鑑：あなたの役職を解放
        editor.putBoolean("role_seen_${h.role.name}", true)
        // エンディング図鑑：勝敗種別を記録（1村/2狼/3狐/4恋人）
        editor.putBoolean("ending_$w", true)
        // 役職別の統計（あなたが担当した役職のプレイ数・勝利数）
        editor.putInt("rolep_${h.role.name}", sp.getInt("rolep_${h.role.name}", 0) + 1)
        if (humanWin) editor.putInt("rolew_${h.role.name}", sp.getInt("rolew_${h.role.name}", 0) + 1)

        // 連勝ストリークの更新
        val prevStreak = sp.getInt("win_streak", 0)
        val newStreak = if (humanWin) prevStreak + 1 else 0
        val best = maxOf(sp.getInt("best_streak", 0), newStreak)
        editor.putInt("win_streak", newStreak)
        editor.putInt("best_streak", best)
        // 連勝ボーナス（3連勝以上で1連勝ごとに+10）
        val streakBonus = if (newStreak >= 3) (newStreak - 2) * 10 else 0

        // コイン付与（1戦=10、勝利=+20、連勝ボーナス）
        val gained = 10 + (if (humanWin) 20 else 0) + streakBonus
        editor.putInt("coins", sp.getInt("coins", 0) + gained)

        // 実績の達成判定
        val totalGames = sp.getInt("games", 0) + 1
        val newlyUnlocked = ArrayList<String>()
        fun ach(id: String, name: String, cond: Boolean) {
            if (cond && !sp.getBoolean("ach_$id", false)) {
                editor.putBoolean("ach_$id", true)
                newlyUnlocked.add(name)
            }
        }
        ach("first_win", "はじめての勝利", humanWin)
        ach("win_villager", "村の守り手", humanWin && !h.role.wolfSide && h.role != Role.FOX_SPIRIT)
        ach("win_wolf", "月夜の狩人", humanWin && h.role.wolfSide)
        ach("win_fox", "闇に紛れし者", humanWin && h.role == Role.FOX_SPIRIT)
        ach("win_seer", "千里眼", humanWin && h.role == Role.SEER)
        ach("detective", "名探偵", e.detectiveId == e.humanId)
        ach("survivor", "生存者", humanWin && h.alive)
        ach("play10", "村の常連", totalGames >= 10)
        val allSeen = Role.values().all {
            it == h.role || sp.getBoolean("role_seen_${it.name}", false)
        }
        ach("all_roles", "役者そろい踏み", allSeen)

        editor.apply()

        val pn = panel()
        val cd = card()
        val (winText, winColor) = when (w) {
            1 -> "🎉 村人チームの勝利！" to Color.parseColor("#A8E6A1")
            2 -> "🐺 人狼チームの勝利！" to Color.parseColor("#FF9B9B")
            3 -> "🦊 妖狐の勝利！" to Color.parseColor("#E0A8FF")
            else -> "💕 恋人の勝利！" to Color.parseColor("#FF9BD0")
        }
        val wt = tv(winText, 22f, true, winColor)
        wt.gravity = Gravity.CENTER
        cd.addView(wt)
        if (w == 3) {
            cd.addView(tv("闇に紛れた妖狐が、最後まで生き残った…", 13f, false, Color.parseColor("#E0A8FF")))
        }
        if (w == 4) {
            val lovers = e.loverIds.map { e.players[it].pname }
            cd.addView(tv("💕 ${lovers.joinToString("と")} は最後まで寄り添い、2人だけの世界を手に入れた…",
                13f, false, Color.parseColor("#FF9BD0")))
        }
        val ht = tv(if (humanWin) "あなたの勝ちです！" else "あなたの負けです…", 16f, true)
        ht.gravity = Gravity.CENTER
        cd.addView(ht)
        if (newlyUnlocked.isNotEmpty()) {
            cd.addView(space(dp(8)))
            cd.addView(tv("🏅 実績を解放しました！", 14f, true, Color.parseColor("#FFE28A")))
            for (nm in newlyUnlocked) {
                cd.addView(tv("　・$nm", 13f, true, Color.parseColor("#FFD08A")))
            }
        }
        cd.addView(space(dp(6)))
        cd.addView(tv("🪙 +$gained コイン獲得！（所持: ${sp.getInt("coins", 0) + gained}）", 13f, true,
            Color.parseColor("#FFD450")))
        if (newStreak >= 2) {
            cd.addView(tv("🔥 $newStreak 連勝中！" + if (streakBonus > 0) "（連勝ボーナス +$streakBonus）" else "",
                13f, true, Color.parseColor("#FF9B6B")))
        }
        cd.addView(space(dp(12)))
        cd.addView(tv("【役職公開】", 14f, true, Color.parseColor("#FFE28A")))
        for (p2 in e.players) {
            var line = "${p2.pname}（${p2.animal.jp}）: ${p2.role.jp}"
            if (!p2.alive) line += " †"
            if (p2.id == e.humanId) line += " ← あなた"
            cd.addView(tv(line, 14f, false,
                when {
                    p2.role.isWolf -> Color.parseColor("#FF9B9B")
                    p2.role == Role.MADMAN -> Color.parseColor("#FFC98A")
                    p2.role == Role.FOX_SPIRIT -> Color.parseColor("#E0A8FF")
                    else -> Color.WHITE
                }))
        }
        cd.addView(space(dp(16)))
        cd.addView(btn("🎬 リプレイ（試合をふりかえる）", Color.parseColor("#3D6BD8")) {
            showReplay()
        })
        cd.addView(space(dp(8)))
        if (predictionActive && predictedWolves.size == 2) {
            cd.addView(btn("🔍 人狼予想の答え合わせ", Color.parseColor("#7A4FD8")) {
                showPredictionResult()
            })
            cd.addView(space(dp(8)))
        }
        setQuickNext("もう一度") { startGame() }
        cd.addView(btn("もう一度あそぶ", Color.parseColor("#D8703D")) { startGame() })
        cd.addView(space(dp(8)))
        cd.addView(btn("タイトルへ") { showTitle() })
        pn.addView(cd)
        setScreen(pn)
    }

    // ---------- リプレイ（全ログ・真相確認） ----------

    private fun showReplay() {
        val e = engine
        val pn = panel()
        val cd = card()
        cd.addView(tv("🎬 リプレイ", 20f, true, Color.parseColor("#FFE28A")))
        cd.addView(tv("この試合のすべての記録と真相", 12f, false, Color.parseColor("#BFD0FF")))
        cd.addView(space(dp(10)))

        // 真相：各キャラの正体
        cd.addView(tv("🎭 正体", 15f, true, Color.parseColor("#FFD08A")))
        for (p2 in e.players) {
            val col = when {
                p2.role.isWolf -> Color.parseColor("#FF9B9B")
                p2.role == Role.MADMAN -> Color.parseColor("#FFC98A")
                p2.role == Role.FOX_SPIRIT -> Color.parseColor("#E0A8FF")
                else -> Color.parseColor("#C8F0C2")
            }
            val you = if (p2.id == e.humanId) "（あなた）" else ""
            cd.addView(tv("・${p2.pname}$you = ${p2.role.jp}" + if (!p2.alive) " †" else "", 13f, false, col))
        }
        cd.addView(space(dp(10)))

        // 時系列の出来事（能力・襲撃・処刑）
        cd.addView(tv("📖 試合の流れ", 15f, true, Color.parseColor("#A8D8FF")))
        if (e.noteAbilities.isEmpty()) {
            cd.addView(tv("記録なし", 13f, false, Color.parseColor("#9AA0B5")))
        } else {
            for (line in e.noteAbilities) cd.addView(tv("・$line", 13f))
        }
        cd.addView(space(dp(10)))

        // 投票の記録
        cd.addView(tv("⚖️ 投票の記録", 15f, true, Color.parseColor("#FFD08A")))
        if (e.noteVotes.isEmpty()) {
            cd.addView(tv("投票なし", 13f, false, Color.parseColor("#9AA0B5")))
        } else {
            val byDay = e.noteVotes.groupBy { it.first }
            for ((day, list) in byDay.toSortedMap()) {
                cd.addView(tv("${day}日目", 13f, true, Color.parseColor("#FFE28A")))
                for ((_, voter, target) in list) {
                    // 真相込みで、人狼に入れた票には🐺を付ける
                    val mark = if (e.players[target].role.isWolf) " 🐺的中" else ""
                    cd.addView(tv("　${e.players[voter].pname} → ${e.players[target].pname}$mark", 13f))
                }
            }
        }
        cd.addView(space(dp(10)))

        // 発言の記録（疑い/信頼）
        cd.addView(tv("💬 発言の記録", 15f, true, Color.parseColor("#A8E6A1")))
        if (e.noteTalks.isEmpty()) {
            cd.addView(tv("発言なし", 13f, false, Color.parseColor("#9AA0B5")))
        } else {
            var curDay = -1
            for ((day, t) in e.noteTalks) {
                if (day != curDay) {
                    cd.addView(tv("${day}日目", 13f, true, Color.parseColor("#FFE28A")))
                    curDay = day
                }
                val arrow = if (t.suspect) "🐺疑" else "🤝信"
                val col = if (t.suspect) Color.parseColor("#FFC9C9") else Color.parseColor("#C8F0C2")
                cd.addView(tv("　$arrow ${e.players[t.speakerId].pname} → ${e.players[t.targetId].pname}",
                    12f, false, col))
            }
        }

        cd.addView(space(dp(14)))
        setQuickNext("もどる") { showGameOver(moodVictory) }
        cd.addView(btn("結果にもどる", Color.parseColor("#D8703D")) { showGameOver(moodVictory) })
        pn.addView(cd)
        setScreen(pn)
    }
}

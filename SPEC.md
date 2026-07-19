# どうぶつ人狼（JinrouApp）仕様書 — v5 / versionCode 1 時点

別のチャットで開発を再開するときの引き継ぎ資料。
**このSPEC.md と `app/src/main/java/com/sekiguchi/jinrou/MainActivity.kt` の全文を渡せば、同じ精度で続きができます。**
（MainActivity.kt は約2100行の単一ファイル。他にKotlinファイルはありません）

---

## 0. 開発環境と更新フロー（必ず守る規約）

- スマホのTermuxのみで開発。**ローカルでコンパイルしない**。GitHub Actionsが全ビルドを行う
- AGP **8.5.2** / Kotlin **1.9.24** / Gradle **8.9**（Actions内で `gradle-version: '8.9'` 指定）
- **Gradle wrapperなし**（`gradlew` を置かない。Actionsは `gradle assembleDebug --no-daemon` を直接実行）
- **外部依存ゼロ**（`app/build.gradle` に dependencies ブロックを持たない）
- **XMLレイアウトなし**。UIは全てKotlinのプログラマティック生成。res配下はアイコンのみ
- **画像アセットゼロ**。9体のキャラも背景もすべて `Canvas` の手描き
- **`app/debug.keystore` をリポジトリに同梱**し、debug署名を固定する
  → 常に同じ署名になるので、アンインストール不要で上書きインストールできる
  → **これが無いとActionsが毎回鍵を自動生成し、更新のたびにインストール失敗する**（v2で実際に発生）
- 保存先: `SharedPreferences("jinrou")` のみ（`games` / `wins` の戦績2項目）

### ビルド設定
| 項目 | 値 |
|---|---|
| applicationId / namespace | `com.sekiguchi.jinrou` |
| minSdk / targetSdk / compileSdk | 26 / 34 / 34 |
| Java / jvmTarget | 17 |
| theme | `@android:style/Theme.Material.NoActionBar.Fullscreen` |
| 画面向き | portrait 固定 |
| リポジトリ | `Sekiguchi-Takashi/JinrouApp` |
| Actions artifact名 | `jinrou-apk`（`app/build/outputs/apk/debug/app-debug.apk`） |

### 納品・更新の手順
1. `versionCode` を +1、`versionName` を上げる
2. 変更ファイルを含むzipを渡す（相対パスは `JinrouApp/...` から）
3. Termux側:
```bash
cd ~ && unzip -o ~/storage/downloads/JinrouApp-vN.zip -d ~ && \
cd ~/JinrouApp && git add . && git commit -m "vN: 内容" && git push
```
4. Actionsの緑チェック後、artifactのAPKを上書きインストール

### 過去にハマった落とし穴（再発防止）
- **`git init` はプロジェクトフォルダの中で行う**。ホームで実行すると `.bash_history` や `.config/gh/hosts.yml` のトークンごとコミットされ、GitHubのPush Protection（GH013）で弾かれる。実際に発生済み
- unzip時は必ず `-o`。`-d ~` を付けないとzip構造によってはホーム直下に散らばる
- keystore未同梱だと署名不一致でインストール失敗（上記）

---

## 1. ゲームルール

### 構成（9人固定）
村人 ×4 / 占い師 ×1 / 霊能者 ×1 / 狩人 ×1 / 人狼 ×2

- キャラは9体固定・**id順の配置も固定**（表示は常に3×3で同じ位置）
- プレイヤーは9体のうちランダム1体に割当。役職もランダム
- 勝利条件: 村人勝利=人狼全滅 / 人狼勝利=人狼数 ≧ 村人側の数

### キャラクター（id順 = 表示順）
| id | 名前 | 動物 | enum |
|---|---|---|---|
| 0 | ミミ | うさぎ | RABBIT |
| 1 | コン | きつね | FOX |
| 2 | タマ | ねこ | CAT |
| 3 | ポチ | いぬ | DOG |
| 4 | クマ吉 | くま | BEAR |
| 5 | ホウ | ふくろう | OWL |
| 6 | リスケ | りす | SQUIRREL |
| 7 | コアタ | こあら | KOALA |
| 8 | ペン太 | ぺんぎん | PENGUIN |

### ゲームの流れ（1日目は昼から始まる）
```
タイトル → ルール確認 → 役職確認
  → 【1日目】自由会話 → 疑いの話し合い → 投票 → 開票処刑
  → 夜（人狼襲撃 / 占い / 護衛）
  → 朝（犠牲者判明・霊能結果）
  → 【2日目以降】占い師フェーズ → 自由会話 → 話し合い → 投票 → 開票
  → 夜 …（決着まで繰り返し）
```

---

## 2. 各フェーズの詳細仕様

### 🌙 夜（`beginNight` → 役職別画面 → `finishNight` → `resolveNight`）
- 人狼: 襲撃相手を選ぶ。CPU人狼は①`wolfGrudge`が立っていれば80%であなたを狙う ②CO中の本物占い師を70%で狙う ③その他ランダム
- 占い師: 1人を占う。結果は `humanSeerResults` / `cpuSeerResults` に蓄積
- 狩人: 1人を護衛。**占い師COが2人いるときは必ずどちらかを護衛**（人間狩人も選択肢が2人に絞られる）。1人のときは60%でその人、他は自由
- 護衛成功なら犠牲者なし。犠牲者は `wolfVictimIds` に記録され、夜画面下部に累積表示される
- 夜の各画面の下の空きスペースに「🐺 これまでに襲撃されたどうぶつ」を表示

### 🔮 占い師フェーズ（2日目の朝から・昼の前）
`ensureSeerPhase()` で**最初の1回だけ**名乗り出を確定。以後、途中から名乗り出ることは無い。

- 本物占い師: 生存していれば75%で名乗り出る（あなたが占い師なら「名乗り出る/隠れる」を選択。隠れると以後CO不可）
- 偽占い師: CPU人狼のうち1人が55%で名乗り出る（`fakeSeerId`）
- → **0人 / 1人 / 2人** のCO状況がありえる
- 本物は本当のことを言う
- 偽物は50%で「本物と全く同じことを言う」、50%で「もう1人の人狼以外を人狼だと言う」（`fakeAccused` で重複回避）
- 黒と言われた相手は `suspicionBoost` に入り、昼の会話で65%優先的に疑われる

### ☕ 自由会話（疑いの話し合いの前・毎日）
`freeTalks()` がCPUの発言を生成。確率は 無言18% / 自己弁護22% / 信用表明18% / 占い師予想18% / 狩人予想24%。

**説得システム（`persuade`、1日1回・`persuadedToday`）**
- キャラまたは吹き出しをタップ → 「人狼だと思う相手」を選ぶ
- 通常: 70%で `persuaded[listenerId] = targetId` が成立 → 昼の会話で80%、投票で75%採用される
- **相手が人狼で、伝えた相手も人狼のとき** → `wolfGrudge = true`（夜に80%であなたが狙われる）
- **あなたが人狼で名探偵を説得** → 50%でバレて `publicBlack` に入り `humanTrust = false`
- **信用喪失（`humanTrust = false`）**: あなたが「人狼だ」と主張した相手（`humanClaims`）が霊能公開や占い白判定でシロと判明したとき。以後の説得は全て無視される

### 🎩 名探偵システム
- `runVote()` で、村人側CPUが人狼に投票するたび `voteStreak` を加算。**2回連続**で `detectiveId` に称号付与（開票結果に勝つ必要はない）
- 誕生した開票画面で告知（`newDetectiveJustNow`）。以後キャラセルの下に「🎩名探偵」表示
- 名探偵は昼に「🎩 名探偵のカン！ ◯◯が人狼だ！」と予想（`detectivePick`）。優先度は 説得済み > 黒判定 > suspicionBoost > 白以外 > ランダム
- 他CPUは会話で75%、投票で85%同調 → 村側の票がまとまる
- **狙い**: 名探偵1人を説得すれば村全体の票を動かせる。これが村人側の勝率を上げる主要素

### 💬 昼の話し合い（`discussionTalks`）
発言の優先順位:
1. 名探偵の予想（1件）
2. あなたの説得を採用した発言（80%）
3. 名探偵への同調（75%）
4. 黒判定への投票呼びかけ（70%）
5. 白判定への信頼表明（30%）
6. 通常の疑い（`suspicionBoost` を65%優先）

- 占い師CO中のキャラと名探偵は上記ループから除外（重複発言防止）
- 表示: キャラを1列に並べ、隣に吹き出し。**名前だけ色付き太字で強調**。あなたの発言は緑枠
- 上部に「📋 まとめを見る」ボタン

### 📋 まとめダイアログ（`showSummaryDialog` / `SummaryView`）
- 生存キャラを円形配置し、疑いの発言（`Talk.suspect == true`）を赤い矢印で結ぶ。矢印の中央に🐺
- その他（信頼発言・黒白判定・CO状況・脱落者）は文字で簡潔に列挙

### ⚖️ 投票・開票
- 投票の優先度: 名探偵本人は自分の予想 > 説得採用75% > 名探偵同調85% > 黒判定 > 白/CO除外ランダム
- 人狼は仲間に投票しない
- 開票画面で「誰が誰に入れたか」を全件表示 → 最多得票者を処刑（同数はランダム）

### 💀 やられた画面（`showHumanDead`）
襲撃死・処刑死どちらでも表示。3択:
- **👀 観戦を続ける** — そのまま進行
- **🐺 人狼を予想して観戦** — 2匹選択（`predictedWolves`、脱落済みも選択可）→ 終了後に答え合わせ画面（`showPredictionResult`、「2匹中X匹正解！」）
- **🏁 終了** — `fastForward()` で残りをCPUだけで一気に進めて最終結果へ

下部に **`statusCard()`**: 9体を固定3×3で全体表示（脱落者はグレー＋×目）＋ 生存数/日数/脱落者/占い師CO/黒白判定

---

## 3. コード構成（MainActivity.kt 単一ファイル・上から順）

| 区分 | 内容 |
|---|---|
| データ定義 | `enum Role`（jp/isWolf）, `enum Animal`（jp）, `class Player`(id/pname/animal/role/alive), `class Talk`(speakerId/text/targetId/suspect) |
| `GameEngine` | 状態フィールド → `setup()` / `winner()` / `resolveNight()` / `ensureSeerPhase()` / `seerPhaseTalks()` / `freeTalks()` / `persuade()` / `discussionTalks()` / `runVote()` / `publishHumanMedium()`。`companion object { NAMES, N=9 }` |
| `CharacterArt` | 動物9種のCanvas描画（`draw` / `drawEars` / 色ヘルパー）。死亡時はグレーベール＋×目 |
| `CharacterView` | CharacterArtを描くだけのView |
| `SummaryView` | まとめの相関図（円形配置＋矢印＋🐺） |
| `TownView` | 背景（昼夜切替、空グラデ、月/太陽、丘、石畳、DQ風の家3軒） |
| `MainActivity` | UIヘルパー（`dp` `setScreen` `panel` `card` `tv` `btn` `space` `charCell` `charGrid` `charGridFixed` `statusCard` `talkBubble`）→ 各画面関数 |

### Kotlin上の注意点
- `Button.transformationMethod = null` を使う（`isAllCaps` プロパティは使えない）
- `max()` は削除済み → `maxOrNull()` を使う
- 空リストの `.random()` は例外 → `randomOrNull()` を使う
- bash heredocでコード追記するときは必ず `<< 'KEOF'` とクォートする（`${...}` の文字列テンプレートが展開されて壊れる）

### 色パレット
金 `#FFE28A` / 赤 `#FF9B9B` / 緑 `#A8E6A1` / 紫 `#C9B6FF` / 水色 `#A8D8FF` / 青ボタン `#3D6BD8` / オレンジ `#D8703D` / 夜紫 `#5A4FD8` / 緑ボタン `#3D9E6B`

### アイコン
- `res/drawable/ic_launcher_foreground.xml`: **コアラの顔**（ふわふわ耳＋ピンクの内側、大きな鼻、ユーカリの葉）のベクター
- `res/values/ic_launcher_background.xml`: `#CDE8D5`（淡い緑）
- `res/mipmap-anydpi-v26/ic_launcher.xml` / `ic_launcher_round.xml`: アダプティブアイコン
- ※ 狼アイコンは審査・配布時の印象を考えて不採用にした経緯あり

---

## 4. バージョン履歴
| ver | 内容 |
|---|---|
| v1 | 7人構成で初版（村人2/占い師1/霊能1/狩人1/人狼2）。夜→朝→昼→投票の基本ループ |
| v2 | 9人構成に拡張。昼の会話を吹き出し1列表示に。まとめ相関図ダイアログ。夜画面下部に犠牲者表示 |
| v3 | 1日目を昼開始に変更。占い師フェーズ（偽占い師・名乗り出制）を追加。狼アイコン追加 |
| v4 | やられた画面に3択（観戦/人狼予想して観戦/終了）。予想の答え合わせ画面。全選択画面を固定3×3配置に。村の状況カード |
| v5 | パンダ→コアラに変更、アイコンもコアラ化。自由会話＋説得システム。名探偵システム（村人側の勝率改善） |

## 5. 次にやるなら（未実装のアイデア）
- 難易度選択（CPUの説得採用率・名探偵の発生条件を変える）
- 戦績の詳細化（役職別勝率、名探偵になった回数）
- BGM/SE（IroGameで `MediaPlayer` を使った実績あり）
- 人数可変（11人・13人構成、狂人役の追加）

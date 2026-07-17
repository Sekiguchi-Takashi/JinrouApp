# どうぶつ人狼（JinrouApp）

9匹のアニメ風どうぶつキャラで遊ぶ1人用人狼ゲーム（うさぎ・きつね・ねこ・いぬ・くま・ふくろう・りす・こあら・ぺんぎん）。1日目は昼から始まり、2日目の朝からは占い師フェーズ（偽占い師あり）が入ります。
背景はドラクエ風の村（昼/夜切り替え）。画像アセットなし、全部Canvas手描き。

## 構成（総数9人）
村人×4 / 占い師×1 / 霊能者×1 / 狩人×1 / 人狼×2
あなたはランダムに1匹を担当。残り8匹はCPU。

## いつものパイプライン
- AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.9（Actionsで固定・wrapperなし）
- 外部依存ゼロ / XMLレイアウトなし（全コードUI） / SharedPreferencesで戦績保存
- 署名: `app/debug.keystore`（無ければActionsが自動生成。既存アプリと同じ署名にしたいなら、いつものdebug.keystoreを app/ にコピーしてコミット）

## Termuxからのpush手順
```bash
cd ~/JinrouApp        # ← 必ずプロジェクトフォルダの中で git init（ホームでやらない）
git init
git add .
git commit -m "どうぶつ人狼 v1.0"
git branch -M main
git remote add origin https://github.com/Sekiguchi-Takashi/JinrouApp.git
git push -u origin main
```
push後、Actionsの `Build APK` が動き、Artifacts の `jinrou-apk` から app-debug.apk をDLできます。

## 遊び方のポイント
- 占い師/霊能者になったら、昼画面の「CO」ボタンで結果を公開できる（公開するとCPUの投票に影響。ただし人狼に狙われやすくなる）
- 人狼になったら仲間が表示され、夜に襲撃相手を選ぶ
- 狩人は夜に護衛先を選ぶ（護衛成功なら「平和な朝」）
- 死亡後は観戦モードで最後まで見届けられる

# CLAUDE.md — android リポジトリの経緯と作業記録

Claude Code がこのリポジトリで作業するときに最初に読むファイル。
作業の経緯・判断の根拠・使用記録をここに残す。追記するときは日付を付ける。

## このリポジトリの目的

- `D:\github\android` で Android アプリを作る。
- アプリは `apps/` の下に 1 アプリ 1 フォルダで分け、フォルダ構成はシンプルで分かりやすく保つ。

## GitHub との同期

- リモート: https://github.com/take-undead/android (`origin`、ブランチ `main`)。2026-10-14 に初回 push。
- コミット・push はユーザーの指示があったときだけ行う。push 前に `git ls-tree -r --name-only HEAD` で
  config.txt・APK・local.properties・`.claude/settings.local.json` が入っていないことを確かめる(`.gitignore` 済み)。
- 改行は `.gitattributes` で管理(`gradlew` は LF 固定。Windows の autocrlf で壊れないように)。

## 作業ルール

- 根拠なく判断しない。ライブラリのバージョン・設定値・API の振る舞いは公式ドキュメントか
  Maven のメタデータで確かめてから使う。確認した日付と出典をこのファイルに残す。
- 報告では「根拠があること」と「推測」を分けて書く。
- 返答は日本語。コード内のコメントも日本語でよい。
- アプリを追加・変更したら、下の「作業記録」に 1 行追記する。

## 鍵を扱うときの注意(2026-10-14 追加・違反しそうなら警告する)

- **本番の鍵(WireGuard の秘密鍵など)を新しい端末に入れる前に、その端末が信用できるかを確かめて利用者に警告する。**
  確かめること: `ro.build.type`(user 以外は開発用)、`ro.build.tags`(test-keys は非正規)、`adb root` / `su` の有無、画面ロックの有無。
- 信用できない端末には本番の鍵を入れず、端末ごとの別の鍵(別のピア)を使う。経緯は「mt5mon」の節の「鍵の安全と非正規端末」。

## 事前の注意事項(アプリを作る前に必ず読む・違反しそうなら警告する)

方針: **スマホ版を先に作り、その後タブレット向けに調整する。** そのために最初から守ること。

| # | やってはいけない / やること | 理由 | 検出方法 |
|---|---|---|---|
| 1 | Manifest に `android:screenOrientation` を書かない(縦固定しない) | タブレット対応時に全画面を横向きで見直す羽目になる | Lint `LockedOrientationActivity` をエラーに設定済み。ビルド(`gradlew lintDebug`)が失敗する |
| 2 | 幅・高さを固定 dp で決め打ちしない(`fillMaxWidth()` / `weight` で伸縮させる) | 大画面で崩れる・間延びする | コードレビュー時に確認 |
| 3 | 画面の状態は `rememberSaveable` か ViewModel に持つ | 回転で入力値が消える | コードレビュー時に確認 |
| 4 | 画像はベクター XML(`drawable/*.xml`)にする | 解像度ごとの画像を用意しなくてよい | PNG を `res/` に入れるときは理由を書く |
| 5 | 本文は最大幅(目安 600dp)を決めて中央に置く | タブレットで 1 行が長すぎて読めない | コードレビュー時に確認 |

Claude Code への指示:
- 上の表、および次の「基本作法の注意事項」に反する変更を頼まれたら、
  **作業前に「注意事項 #N に反する」と警告し、理由を添えて確認する。**
  ユーザーが承知の上で進める場合はその旨を作業記録に書く。
- 新しいアプリを作るときは、`apps/hello-world` の `lint { }` 設定と Manifest のコメントをそのまま引き継ぐ。

タブレット向け調整は、スマホ版が動いてから次の順で行う:
本文の最大幅 → 幅が広いときの 2 ペイン化 → ナビゲーションバーからレールへの切り替え。

## 基本作法の注意事項(違反しそうなら作業前に警告する)

Lint 列の ID は 2026-10-13 に googlesamples.github.io/android-custom-lint-rules の一覧で実在を確認した。
「エラー」と書いたものは `apps/hello-world` の `lint { }` でエラーに設定済み(新しいアプリにも引き継ぐ)。

### A. 公開してから直せないもの(最重要)

| # | 注意事項 | 理由 | 検出 |
|---|---|---|---|
| A1 | `applicationId` は公開後に変えない。公開前に `com.example.*` から自分のドメイン名に変える | Play ストアでは別アプリ扱いになる | 公開前チェック(手動) |
| A2 | 署名キーストア(`*.jks` / `*.keystore`)とパスワードは絶対にリポジトリに入れない。`.gitignore` 済み | 漏れると偽アプリを配布される。紛失するとアプリ更新ができなくなる | `.gitignore` |
| A3 | `versionCode` は更新のたびに必ず増やす | 増えていないと Play に上げられない | 公開前チェック(手動) |
| A4 | `local.properties`、API キー、トークンをコミットしない | 秘密情報の漏洩 | `.gitignore` + コードレビュー |

### B. セキュリティ

| # | 注意事項 | 理由 | 検出 |
|---|---|---|---|
| B1 | 権限は必要最小限。使わない `<uses-permission>` を書かない。実行時権限は使う直前に求める | 審査落ち・ユーザー離脱 | Lint `MissingPermission` |
| B2 | 通信は HTTPS のみ。`usesCleartextTraffic` を true にしない。証明書検証を無効化しない | 盗聴・改ざん | Lint `InsecureBaseConfiguration`、`TrustAllX509TrustManager`(エラー) |
| B3 | Manifest に `android:debuggable` を書かない | リリース版がデバッグ可能になる | Lint `HardcodedDebugMode`(エラー) |
| B4 | 外部から受け取った Intent をそのまま別コンポーネントに転送しない | 権限の横取り | Lint `UnsafeIntentLaunch` |
| B5 | `exported="true"` は起動 Activity など外部から呼ばれる必要があるものだけ | 他アプリから勝手に呼ばれる | Lint `ExportedReceiver` ほか |
| B6 | WebView で JavaScript を有効にするときは理由を書く。信頼できる URL のみ読み込む | XSS・情報漏洩 | Lint `SetJavaScriptEnabled` |
| B7 | ログに個人情報・トークンを出さない。リリース版でログを出さない | ログは他アプリから読める場合がある | Lint `LogConditional` + コードレビュー |

### C. 文字列・リソース

| # | 注意事項 | 理由 | 検出 |
|---|---|---|---|
| C1 | 画面に出す文字は `res/values/strings.xml` に置き、Compose では `stringResource()` で参照する。コードに直書きしない | 多言語対応・表記統一ができない。Lint は XML レイアウトしか検出しないので Compose はレビューで確認 | Lint `HardcodedText`(エラー、XML のみ) + コードレビュー |
| C2 | 数値を埋め込む文字列は `%1$d` などの書式引数を使う(文字列連結しない) | 言語によって語順が変わる | Lint `StringFormatMatches` |
| C3 | 文字サイズは `sp`、余白や大きさは `dp` | ユーザーの文字サイズ設定が効かない | Lint `SpUsage` |
| C4 | 使わなくなったリソースは消す | APK が大きくなる・混乱する | Lint `UnusedResources` |
| C5 | 画像には `contentDescription` を付ける(装飾なら `null` と明示) | 読み上げ(アクセシビリティ)で意味が伝わらない | Lint `ContentDescription` |

### D. Compose の書き方

| # | 注意事項 | 理由 | 検出 |
|---|---|---|---|
| D1 | `Scaffold` の中身は渡された `innerPadding` を必ず使う | ステータスバーや下のバーに内容が隠れる | Lint `UnusedMaterial3ScaffoldPaddingParameter`(エラー) |
| D2 | `mutableStateOf` に `MutableList` などの可変コレクションを入れない。`mutableStateListOf` か不変リストの差し替えにする | 中身を変えても画面が更新されない | Lint `MutableCollectionMutableState`(エラー) |
| D3 | Composable 関数の中で通信・ファイル読み書き・重い処理をしない。`LaunchedEffect` か ViewModel のコルーチンで行う | 再描画のたびに実行される・画面が固まる | コードレビュー |
| D4 | 一覧(`LazyColumn`)の項目には安定した `key` を付ける | 並べ替え・削除で表示がずれる | コードレビュー |
| D5 | Composable 関数は `Modifier` を最初の省略可能引数として受け取る | 呼び出し側でレイアウト調整できない | コードレビュー |

### E. 動作・互換性

| # | 注意事項 | 理由 | 検出 |
|---|---|---|---|
| E1 | メインスレッドで通信・DB・ファイル I/O をしない | ANR(アプリが応答しません)になる | コードレビュー(StrictMode を debug で有効にするのも可) |
| E2 | `minSdk` より新しい API を呼ぶときは `Build.VERSION.SDK_INT` で分岐する | 古い端末でクラッシュ | Lint `NewApi`(既定でエラー) |
| E3 | `targetSdk` は毎年 Play の要件に合わせて上げる | 古いと Play に上げられない | Lint `ExpiredTargetSdkVersion` |
| E4 | `Cursor` / ストリーム / `TypedArray` は使い終わったら閉じる(`use { }`) | リソースリーク | Lint `Recycle` |
| E5 | 非同期処理は `Thread` や `AsyncTask` ではなくコルーチン(`viewModelScope`)で書く | 画面破棄後のリーク・クラッシュ | コードレビュー |

### F. 運用

| # | 注意事項 | 理由 | 検出 |
|---|---|---|---|
| F1 | ビルド前に `gradlew lintDebug` を通す。Lint の警告を `@Suppress` で消すときは理由をコメントに書く | 警告は後で必ず問題になる | 手順 |
| F2 | ライブラリ追加は `libs.versions.toml` に書き、バージョンは Maven で確認した安定版にする(alpha/beta を使うときは理由を書く) | 再現性・根拠の記録 | コードレビュー |
| F3 | 変更したら「作業記録」に追記する | 経緯が追えなくなる | このファイル |

## 後で手直しが大変になること(最初に決める・あとから変えない)

ここに書いたものは「動くから後回し」にすると、直すときにアプリ全体に手が入る。
**該当する変更や省略を頼まれたら、作業前に「手直し警告 G-N」と警告して確認する。**

| # | 最初に決める / 守ること | 後回しにすると起きること | 今の雛形での扱い |
|---|---|---|---|
| G1 | **パッケージ名・applicationId** を最初に決める(自分のドメイン逆順、例 `jp.example.memo`)。`com.example.*` のまま作り続けない | 全ファイルの `package` 行とフォルダを移動する。公開後は変更不可(A1) | hello-world は `com.example.helloworld`。**2 つ目のアプリから本番用の名前で作る** |
| G2 | **UI・状態・データの 3 層に分ける**。Activity や Composable の中に通信や保存の処理を書かない(ViewModel と Repository に置く) | 画面が増えると同じ処理があちこちにコピーされ、1 か所直すと他が壊れる。テストも書けない | 画面数が 2 を超えた時点で `ui/` `data/` フォルダに分ける |
| G3 | **保存するデータの形(DB のテーブル・SharedPreferences / DataStore のキー)**は最初に設計し、変更時は必ず移行(migration)を書く | 既存ユーザーのデータが消える、起動時にクラッシュする。古い形式を永久にサポートする羽目になる | Room を使うなら `exportSchema = true` でスキーマを保存する |
| G4 | **画面の遷移(ナビゲーション)**は最初から Navigation ライブラリで組む。Activity を画面ごとに増やさない | 戻る操作・ディープリンク・2 ペイン化のたびに全画面の遷移コードを書き直す | 画面が 2 つになった時点で Navigation Compose を入れる |
| G5 | **向きの固定・固定幅**を入れない(#1, #2) | タブレット対応時に全画面を見直す | Lint でエラー |
| G6 | **文字列の直書き**をしない(C1) | 多言語化のときに全ファイルを探して置き換える | strings.xml + stringResource() |
| G7 | **テーマと色は 1 か所(`ui/theme/`)**に置き、各画面で色コードや文字サイズを直書きしない。Material3 の `MaterialTheme.colorScheme` / `typography` を使う | ダークモード対応・配色変更で全画面を修正する | 雛形は `MaterialTheme` 既定を使用。色を変えたくなったら `ui/theme/Theme.kt` を作る |
| G8 | **minSdk を最初に決め、安易に下げない**(今は 26) | 下げると新しい API を使った全箇所に分岐が必要になる。上げるのは簡単 | 26。下げる依頼があれば警告 |
| G9 | **非同期処理はコルーチン + Flow で統一**する。`Thread` / `Handler` / `AsyncTask` / コールバック地獄を混ぜない | 方式が混ざると画面破棄後のクラッシュやリークの原因が追えなくなる | 雛形に非同期処理はまだ無い。追加時はコルーチンのみ |
| G10 | **View(XML レイアウト)と Compose を混ぜない**。このリポジトリは Compose で統一 | 2 つの仕組みを両方保守することになる。相互運用コードが増える | `res/layout/` を作る依頼があれば警告 |
| G11 | **署名キーの作成と保管場所**を公開前に決め、バックアップを取る(A2) | 紛失するとアプリを更新できず、新しいアプリとして出し直し | キーストアは `.gitignore` 済み。保管場所は作業記録に書く(パスワードは書かない) |
| G12 | **依存ライブラリは本当に必要なものだけ**入れる。「とりあえず」で入れない。非推奨(deprecated)のライブラリを新規に使わない | 外すときに利用箇所をすべて書き直す。更新が止まったライブラリは AGP 更新のたびに壊れる | `libs.versions.toml` に書く前に用途を 1 行書く |
| G13 | **ユーザーデータのバックアップ方針**(`allowBackup`、`dataExtractionRules`)を公開前に決める | 機種変更時にデータが消える、または秘密情報が勝手にバックアップされる | 雛形は `allowBackup="true"`。秘密情報を保存するアプリでは除外ルールを書く |
| G14 | **ログ・分析・クラッシュレポート**を入れるなら早めに決める(入れない選択も可) | 後から入れると「どこで落ちたか」が分からない期間ができる | 未導入。入れるなら B7 を守る |

判断の目安: 「この変更は 1 ファイルで済むか、全画面に手が入るか」。全画面に手が入る性質のものは、小さいうちに正しい形にしておく。

## フォルダ構成

```
android/
├─ CLAUDE.md          # このファイル(経緯・根拠・作業記録)
├─ README.md          # 人向けの説明
├─ .gitignore
└─ apps/
   ├─ hello-world/    # 最初のサンプル(Kotlin + Jetpack Compose、独立した Gradle プロジェクト)
   ├─ mt5mon/         # PicoCalc の apps/net/mt5mon + vpschat を 1 本にした Android 版(下の「mt5mon」の節)
   └─ esp32cam/       # winapp の esp32cam(Tkinter)の Android 版(下の「esp32cam」の節)
```

- 各アプリは独立した Gradle プロジェクト(ルートに共通の settings.gradle は置かない)。
  理由: アプリ同士の依存をなくし、1 つのアプリだけを Android Studio で開けるようにするため。
- 新しいアプリは `apps/hello-world` をコピーして作る(手順は README.md)。

## 開発環境の状態(2026-10-13 時点)

PowerShell で確認した事実:

| 項目 | 状態 |
|---|---|
| Java | JRE 1.8.0_411 のみ(`C:\Program Files\Java\jre-1.8`) |
| JAVA_HOME / ANDROID_HOME | 未設定 |
| Android Studio / Android SDK | 未インストール |
| gradle / adb / sdkmanager | PATH に無し |
| winget | 無し |

### 2026-10-14 に Android Studio 無しでビルド環境を作った(事実)

| 項目 | 場所・値 | 取得元 |
|---|---|---|
| JDK 17 | `C:\Users\datak\.jdks\jdk-17`(Temurin 17.0.20.1+1) | api.adoptium.net(GitHub の temurin17-binaries へ転送) |
| Android SDK | `C:\Users\datak\AppData\Local\Android\Sdk` | commandlinetools-win-15859902_latest.zip(SHA-256 が公式ページの値と一致) |
| 入れた SDK パッケージ | platform-tools 37.0.1、platforms;android-36、platforms;android-37.0、build-tools;36.0.0 | sdkmanager |
| 実機 | Blackview BL8800Pro(Android 11、arm64-v8a) | adb devices |

- ビルドは `JAVA_HOME` と `ANDROID_HOME` の環境変数で指す。**`local.properties` は作らない**
  (Windows のパスを書くと Lint の `PropertyEscape` が error になり、`C\:` と書いても直らなかった。環境変数なら問題無い)。
- `sdkmanager` は非推奨の警告を出す(代わりは `android sdk`)が動く。ライセンスは `yes | sdkmanager --licenses` で受け入れた。
- API 37 の platform は `android-37.0 / 37.1 / 37.2` と小さい版に分かれている(`sdkmanager --list` で確認)。`compileSdk = 37` で `android-37.0` が使われた。
- 実行の例(Git Bash):
  ```
  export JAVA_HOME="C:/Users/datak/.jdks/jdk-17"; export ANDROID_HOME="C:/Users/datak/AppData/Local/Android/Sdk"
  cd apps/mt5mon && ./gradlew.bat --no-daemon -q lintDebug assembleDebug
  $ANDROID_HOME/platform-tools/adb.exe install -r -t app/build/outputs/apk/debug/app-debug.apk
  ```
- Lint の結果はタスクがキャッシュされるため、設定ファイルを消しても古い報告が残ることがある。疑わしいときは `--rerun-tasks`。
- Android Studio を後で入れるときは、同じ SDK フォルダを指定すればそのまま使える。

## 採用バージョンと根拠(2026-10-13 確認)

| 項目 | バージョン | 出典 |
|---|---|---|
| Android Gradle Plugin | 9.4.1 | dl.google.com の maven-metadata.xml で最新安定版と確認 |
| Gradle | 9.6.0 | AGP 9.4 のリリースノート(最小・既定とも 9.6.0、JDK 17 必須) |
| Kotlin / Compose Compiler plugin | 2.4.21 | repo1.maven.org の maven-metadata.xml で最新安定版と確認 |
| Compose BOM | 2026.09.00 | dl.google.com の maven-metadata.xml と Compose 公式セットアップ手順 |
| activity-compose | 1.13.0 | dl.google.com の maven-metadata.xml |
| core-ktx | 1.19.1 | 同上 |
| lifecycle-runtime-ktx | 2.11.0 | 同上 |
| compileSdk | 37 | Compose BOM 2026.09.00 のライブラリ(foundation 1.12.1 など)が AAR metadata で 37 以上を要求(2026-10-14 のビルドで確認。36 ではビルドが止まった) |
| targetSdk | 36 | 動作の切り替えは急がない(Lint の OldTargetApi は warning のまま) |
| minSdk | hello-world 26 / mt5mon 23 | hello-world は adaptive icon のため 26。mt5mon は Android 6.0 の MECHEN H1 で使うため 23(上の「2 台目」の節) |

設定上の注意(公式「Migrate to built-in Kotlin」で確認):
- AGP 9 以降は Kotlin が組み込みのため `org.jetbrains.kotlin.android` プラグインは適用しない。
- Compose を使うモジュールには `org.jetbrains.kotlin.plugin.compose` を適用し、
  `buildFeatures { compose = true }` を設定する。
- Gradle wrapper(gradlew / gradlew.bat / gradle-wrapper.jar)は github.com/gradle/gradle の v9.6.0 タグから取得。

未確認(推測を含む):
- 実機・エミュレータでのビルドと起動はまだ行っていない。Android Studio 導入後に要確認。
- AGP 9.4.1 の要件は 9.4.0 のリリースノートから読んだもの(パッチ版で変わらない前提)。

## mt5mon(PicoCalc の mt5mon + vpschat の Android 版)(2026-10-14)

利用者の指示: 「D:\github\picoclac\apps\net のアプリを一つにまとめて android に移植する。画面や操作性は android に合わせ刷新する。鍵は同じものを使う。」

### 元の仕組み(picoclac の AGENTS.md と VPS のソースで確認)

- 元のソース: `D:\github\picoclac\apps\net\mt5mon\`(C、Pico 2 W)と `apps\net\vpschat\`(通信部分は mt5mon を共用)。
- VPS 側の正のソース: `D:\github\vscode-\MT5\claude_ea\tools\vps\`(status_server.py・attack.py・chat.py)。**VPS 側は変えない。**
- 通信: Wi-Fi → WireGuard(VPS の 51820/udp)→ HTTP/1.0 の平文で `http://10.8.0.1:8080/…`(WireGuard の内側でだけ届く)。
  読む口: `/status.json`(5 秒ごと)・`/bars.json?tf=&n=`(M5/M15、30 秒ごと)・`/sup.json`(60 秒)・`/nippo.md`(5 分)・
  `/f2b.json`・`/stats.json`(30 秒)・`/notice.json`(30 秒・どの画面でも)・`/chat.json?since=N`(考え中 3 秒・普段 15 秒)。
  送る口: `POST /ban?ip=`・`/unban?ip=`・`/stats_refresh`・`/chat`(本文 2000 バイトまで)・`/chat_new`。応答は `{"ok":…,"msg":…}`。
- JSON の形は picoclac の `status.c`・`attack.c`・`nippo.c`・`notice.c`・vpschat の `main.c` の読み方をそのまま Kotlin に写した(`data/Parsers.kt`)。
- 鍵: PicoCalc の `/mt5mon/config.txt`(PC の `picoclac\build\mt5mon\config.txt`、`tools\mt5mon_config.py` が作る)の
  `wg_private_key`・`wg_address`(10.8.0.3)・`wg_peer_public_key`・`wg_endpoint`・`wg_allowed`(10.8.0.0/24)・`url`・`interval` を
  同じ形のまま読む(`data/AppConfig.kt`)。VPS の `[Peer]` の登録はそのまま使える。

### Android 版の作り(判断と根拠)

| 判断 | 内容 | 根拠・理由 |
|---|---|---|
| 1 本にまとめる | 下のナビゲーションバー(幅 600dp 以上は左のレール)で モニター / 日報 / 攻撃 / チャット / 設定 を切り替え | 利用者の指示。「事前の注意事項」の方針どおり幅で切り替える |
| WireGuard を内蔵 | 公式ライブラリ `com.wireguard.android:tunnel`(Apache-2.0、Maven Central、wireguard-go 入り)の `GoBackend` を使う。AllowedIPs は 10.8.0.0/24 だけ(スプリットトンネル) | 別の VPN アプリを入れずに「一つのアプリ」にするため。ライブラリの README(Gradle 座標・desugaring 必須)、`GoBackend.java`・`Config.java`・`Interface.java`・`Peer.java`・`Tunnel.java`・`Statistics.java`・`tunnel/AndroidManifest.xml`(VpnService の宣言はライブラリ側にある)を GitHub で確認 |
| Wi-Fi 設定・SNTP・かな漢字変換は移植しない | Android の OS と IME が持っている | 不要なコードを増やさない(G12) |
| 平文 HTTP の例外 | `res/xml/network_security_config.xml` で **10.8.0.1 だけ** `cleartextTrafficPermitted=true`。`usesCleartextTraffic` は使わない | B2 の例外。WireGuard が暗号化しているため。`<domain>` に IP を書けるかは公式ドキュメントに明記が無い(**未確認**。だめなら base-config に変える) |
| 鍵の持ち方 | config.txt の中身を DataStore(アプリ専用領域)に保存。`allowBackup=false` で端末のバックアップに乗せない。`.gitignore` に `**/config.txt` | A2・A4・G13。Android Keystore での暗号化は今回は見送り(端末は本人のみの前提。将来の課題) |
| パッケージ名 | `app.mt5mon` | G1。公開しない私用アプリなのでドメインは持たない。公開するなら最初に変える |
| JSON | 標準の `org.json` | 依存を増やさない(G12) |
| HTTP | `HttpURLConnection`(`Proxy.NO_PROXY`、接続 5 秒・読み 8 秒) | サーバーが HTTP/1.0 で答えて切る単純な相手。ライブラリ不要 |
| 画面の文字 | ラベルは `strings.xml`。**ViewModel が組み立てる診断文(「接続できない」「送れなかった: …」など)はコードに直書き** | C1 の例外。VM は Composable でないため `stringResource` を使えない。VPS の応答文(日本語)をそのまま出す箇所も多い。文字列資源に移す手段(資源 ID を持つ sealed class)は将来の課題として残す |
| 鳴らし方 | `AudioTrack` で PicoCalc と同じ波形(A5→E6、鐘っぽい減衰、1〜5 回、音量 1 段 3dB)を作る | 元の `alert.c` の式をそのまま写した |
| バックグラウンド | 画面を開いている間だけ取る(ViewModel の繰り返し)。通知は未実装 | まず動く形を作る。常時監視・通知は次の段階 |

### 採用バージョンと根拠(2026-10-14 確認、hello-world の分に追加)

| 項目 | バージョン | 出典 |
|---|---|---|
| com.wireguard.android:tunnel | 1.0.20260102 | repo1.maven.org の maven-metadata.xml(最新)。依存は androidx.annotation 1.9.1・collection 1.5.0、minSdk 24、ライセンス Apache-2.0(pom) |
| desugar_jdk_libs | 2.1.5 | dl.google.com の maven-metadata.xml。`isCoreLibraryDesugaringEnabled = true` の書き方は公式「Java 8+ API desugaring support」 |
| navigation-compose | 2.10.2 | dl.google.com の maven-metadata.xml |
| lifecycle-* | 2.11.0 | 同上 |
| datastore-preferences | 1.2.1 | 同上 |
| kotlinx-coroutines-android | 1.11.0 | repo1.maven.org の maven-metadata.xml |
| material-icons-core | (BOM 2026.09.00 → 1.7.8) | Compose BOM の対応表 |

### ビルドと実機の確認(2026-10-14)

- `lintDebug assembleDebug` が通った(error 0。warning は AndroidGradlePluginVersion・DataExtractionRules・MonochromeLauncherIcon・ObsoleteSdkInt・OldTargetApi の 5 件)。
- 直した不具合: Manifest の `android:name=".Mt5App"` 忘れ(起動時に ClassCastException)、`RowItem` のラムダに `RowScope` が無く `weight` が未解決、
  Lint ID の綴り(`UnusedMaterial3ScaffoldPaddingParameter` が正)。
- `mipmap-anydpi-v26` を Lint の ObsoleteSdkInt に従って `mipmap-anydpi` にすると aapt が `adaptive-icon` を解決できずビルドが止まる。**v26 のまま残す**(warning は無視)。
- `Statistics.peer(Key)` → `PeerStats` の record アクセサ(`rxBytes()` 等)はコンパイルが通った(公開版 1.0.20260102 でも record)。
- 実機(BL8800Pro、Android 11)で起動し、モニター画面(設定ファイル無しの案内)が出ることを adb の screencap で確認。

### 実機での動作確認(2026-10-14 11:49〜11:52、BL8800Pro・Android 11、adb の screencap で確認した事実)

- 設定 → ファイルから読み込む → `Download/mt5mon_config.txt` を選ぶ(**この選択だけは利用者が指でタップ**。adb の `input tap` は
  ファイル選択画面(DocumentsUI)の項目には効かなかった。メニューやタブのタップは効く)→ 公開鍵 `nuc8…DCc=` が表示 → 自動で VPN 接続。
- WireGuard: 「つながっている(最後のハンドシェイク 11 秒前)受信 12 KB」。VPN の許可画面も通った。
- モニター: LIVE、USDJPY の bid/ask、M1 のローソク足、キリ値の線と距離、残高・EA 4/4 稼働。M15 は VPS の `/bars.json` の足(02:00〜)も出た。
  → **`network_security_config.xml` の `<domain>` に IP リテラル(10.8.0.1)を書いた平文の許可は効く**(確認済み)。
- 日報: 要約(監督の生存・予測・ライン・EA_One・根拠・判断の記録)が出た。攻撃: 見張りの数・上位の一覧(永久 21 件)が出た。チャット: 履歴が出た。
- 直した: 左上の時間足の名前とキリ値の値が重なる(値の方を書かない)。VPN がつながった瞬間に status と bars を取りにいく(30 秒待たない)。
- **チャイムが鳴らなかった(12:00 に解決)**: 端末がおやすみモード(`dumpsys notification` で `mZenMode=ZEN_MODE_IMPORTANT_INTERRUPTIONS`、
  許可は ALARMS と MEDIA のみ)で、`USAGE_NOTIFICATION` の音が止められていた。`AudioAttributes.USAGE_ALARM` に変えたら「試しに鳴らす」で鳴った(利用者確認)。
  チャイムは端末の**アラーム音量**に従う(当時 11/15)。
- 未確認: 永久ブロック・解除・チャットの送信(本当に VPS を操作するので試験では押していない)、知らせのポップアップ(VPS に新しい知らせが来たとき)。

### 2 台目: MECHEN H1(Android 6.0・API 23・armeabi-v7a・480×800)への対応(2026-10-14 13:00)

- そのままでは `INSTALL_FAILED_OLDER_SDK`。**minSdk を 26 → 23 に下げた**(G8 の例外。利用者がこの端末で使いたいと言ったため。警告は出した)。
- 下げるために変えたもの(いずれも AAR の manifest の minSdkVersion を実際に読んで確認):
  - `com.wireguard.android:tunnel` 1.0.20260102(minSdk 24)→ **1.0.20230706**(minSdk 21、armeabi-v7a 入り)。公開 API(GoBackend・Statistics・PeerStats の record・Config の Builder)は同じ(javap で確認)。
  - `navigation-compose` 2.10.2(minSdk 24)→ **2.9.8**(minSdk 21)。
  - アイコン: 8.0 未満用に `res/mipmap-anydpi/ic_launcher.xml`(vector)を追加。8.0 以上は `-v26` の adaptive-icon が優先される。
- Lint は通った(警告に Aligned16KB ×3 が増えた。旧版ライブラリの .so が 16KB 境界でない。Android 15 の 16KB ページ端末で動かすには新しい版が要る → その端末では minSdk 24 に戻す)。
- H1 で起動を確認。Wi-Fi 192.168.0.229、おやすみモードは「アラームのみ」(zen_mode=3)。
- **H1 ではファイル選択画面が使えない**(事実): 選択画面を前に出した瞬間に、端末のファームウェアが MT5 Monitor を止める
  (logcat: `ActivityManager: Killing app.mt5mon (adj 2): stop app.mt5mon`、直前に端末独自の `jtest: recent size ..5`)。クラッシュではない。RAM 1.4GB。
  → 対策として **「開く」「共有」(ACTION_VIEW / ACTION_SEND、text/plain)で config.txt を受け取る口**を MainActivity に付けた(`launchMode=singleTop`)。
  中身が config.txt として読めたときだけ保存する。adb からは
  `am start -n app.mt5mon/.MainActivity -a android.intent.action.VIEW -d content://media/external/file/<id> -t text/plain --grant-read-uri-permission`
  (`<id>` は `content query --uri content://media/external/file --projection _id:_data --where "_data LIKE '%mt5mon_config%'"`。
  adb で push したファイルは `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/…` で索引に入れる)。
  この方法で H1 に config.txt を読み込めた(13:10)。
- **H1 の VPN(解決、13:20)**: 最初は `pm list packages` に `com.android.vpndialogs` が無く、`VpnService.prepare` の許可画面を開こうとして
  `ActivityNotFoundException` で落ちた(→ 捕まえて「VPN の許可画面が無い」と出すようにした)。`/dev/net/tun` も無かった。
  利用者「VPN を入れれば良いのでは」を受けて端末を調べた事実: userdebug ビルドで `adb root` が通る。カーネル 3.18.19 は TUN に対応
  (`/proc/misc` に `200 tun`。装置ファイルが無いだけ)。VPN の許可は AppOps の `ACTIVATE_VPN` に記録される。
  → root で `mknod /dev/net/tun c 10 200` と `appops set app.mt5mon ACTIVATE_VPN allow` を行ったら、許可画面なしで WireGuard が上がり
  (`tun0` 10.8.0.3、経路 10.8.0.0/24)、モニターに LIVE のデータが出た。手順は `apps/mt5mon/tools/h1_vpn_setup.ps1`。
  **`/dev/net/tun` は再起動で消える**(AppOps の許可は残る)ので、H1 を再起動したら USB でつないでスクリプトを流す。
  他の VPN アプリ(WireGuard 公式など)を入れても同じ許可画面で止まるので、入れる意味は無い(同じ VpnService を使うため)。
- **H1 でチャイムが鳴らない(13:28 解決)**: 端末の**アラーム音量がスピーカーで 0**だった(`dumpsys audio` の STREAM_ALARM speaker: 0)。
  再生自体は走っていた(logcat に AudioTrack 22050Hz)。Android 6 には `media volume` が無いので `service call audio 3 i32 4 i32 10 i32 0 s16 app.mt5mon`
  (setStreamVolume: stream 4=ALARM、10)で上げ、アプリ内の音量も 3 → 10 にした。利用者「鳴った」で確認。
  **2 台とも「チャイムが鳴らない」の原因は端末側の音量・モード**(BL8800Pro はおやすみモード、H1 はアラーム音量 0)。
- **BL8800Pro(1 台目)には新しい APK をまだ入れていない**(作業時に USB が外れていた)。次に USB でつないだら入れ直す(古い APK でも動作は同じ)。
- 2 台とも同じ鍵なので、**同時に VPN をつなぐと取り合いになる**。H1 で使うときは BL8800Pro の VPN を「切る」。

### 鍵の安全と非正規端末(2026-10-14 13:40、利用者と確認)

- 事実: **MECHEN H1 は非正規に近い開発用ビルド**(`ro.build.type=userdebug`、`ro.build.tags=test-keys`、`adb root` が通る、`/system/xbin/su` あり)。
  USB をつないだ人や端末内の別アプリが、アプリ専用領域の WireGuard 秘密鍵を読み出せる(adb root で中を覗けることを実際に確認)。
- アプリの鍵は VPS の内側(10.8.0.0/24)に入れる鍵で、モニターだけでなく永久ブロック・解除(`/ban` `/unban`)や Claude への問いかけにも使える。
- H1 に PicoCalc と同じ本番の鍵を置いたのは 2 台目の試験としての判断だが、**安全面の注意を先に出すべきだった**(反省。以後、鍵を新しい端末に入れる前に信用できる端末かを確かめて警告する)。
- 勧めた対策(VPS 側の変更が要る。**利用者の指示待ち**):
  1. 信用できない端末には PicoCalc と同じ鍵を入れない。端末ごとに別のピア(例: H1 = 10.8.0.5)を作り、漏れたらその鍵だけ消す。
  2. そのピアの AllowedIPs を VPS 側で絞る(10.8.0.1:8080 だけ)。
  3. `/ban` `/unban` `/stats_refresh` `/chat` など操作系は信用できる端末のピアからだけ受ける(今は 10.8.0.0/24 全部から受ける)。
  4. H1 の試験が終わったら鍵を作り直す(PicoCalc と共通なので、作り直すと PicoCalc と BL8800Pro にも入れ直し)。
- 今すぐできること: H1 に画面ロック(設定 → セキュリティ → 画面ロック。今は「スワイプ」)、H1 の VPN は試験のときだけつなぎ普段は「切る」。
- 動作の見込み(推測): Android 6 以上で VPN が使える端末なら動く可能性が高い。VPN が削られた端末は root が取れないと張れない。
  メーカー独自の節電で裏のアプリを止める端末では、画面を開いたままの専用機として使う。

### picoclac 側の更新の追従(2026-10-15 09:40)

- picoclac のコミット `0241f1f`(値動き警報 type=move への対応。EA_One v0.16)を Android 版に写した。
  `notice.json` の `type` / `kind`・`move_pips`・`move_sec`・`move_dir` を読み(`Parsers.notice`、`Notice` に 4 項目追加)、
  ポップアップの見出しを「値動き警報 → Claude の調査」、2 行目を「HH:MM 警報 ↓16.3p/60秒（≧15） → HH:MM 判断更新 #8」の形にした
  (スプレッド警報は「1.60p（≧1.5）」。picoclac の `notice.c` の `notice_ui_draw` と同じ組み立て。文字列は strings.xml)。
- 仕様の正は `MT5/NEW_EA/BRIDGE.md`(vscode- リポジトリ)の「値動き警報」。
- **追従の手順**: picoclac の `git log 43035f8..HEAD -- apps/net AGENTS.md` で差分を見て、`notice.c` / `status.c` / `attack.c` / `nippo.c` /
  vpschat `main.c` の読み方の変更を `data/Parsers.kt` と対応する画面に写す。次回は `0241f1f..HEAD`。
- 3 台目 MP01(Android 11、user/release-keys = 信用できる正規ビルド)に mt5mon を入れた(09:30)。
- 利用者「つなぐがない」→ 「つなぐ」は config.txt を読み込むまで押せない作り(`enabled = ui.config != null`)。端末に鍵のファイルも無かった。
  こちらで Download に置いたが、**MP01 のファイル選択画面(「ドキュメント」)にも adb で置いたファイルが出なかった**(利用者の報告。BL8800Pro では出た)。
  → H1 と同じく「開く」の口で渡した(`am start … -a VIEW -d content://media/external/file/16249 -t text/plain --grant-read-uri-permission`)。
  09:35 に WireGuard が上がり LIVE のデータが出た(VPN の許可画面は出ずに通った)。Download の鍵のファイルは消した。
- **鍵を新しい端末に入れるときは、ファイル選択画面に頼らず「開く」の口で渡すのが確実**(3 台中 2 台で選択画面が使えなかった)。

### 同じ鍵を 2 台で同時に使ったときの影響(2026-10-15 09:38〜09:40 に実測)

- 状況: 自宅で PicoCalc(公開 IP 58.188.67.132)が mt5mon を動かしたまま、外で MP01(182.249.x.x)の mt5mon をつないだ。
- VPS の `wg show wg0`(`ssh mt5vps`、読むだけ)を 3〜5 秒ごとに見た事実: 同じピア `nuc8…DCc=` の endpoint が
  自宅とスマホの間で **数秒ごとに入れ替わった**(1 分で 6 回ほど)。VPS は 1 つのピアに返事の宛先を 1 つしか持たず、
  最後に届いた方へ返す(WireGuard のローミング)ため、もう片方への返事が落ちる。
- スマホの画面: 一度「HTTP 失敗: 時間切れ」「19 秒前」を見たが、その後 1 分間 10 秒ごとに 6 回見て **6 回とも LIVE**。
  5 秒ごとの取り直しで追いつくので、**見るだけなら「たまに途切れる」程度**(利用者と確認)。
- 害は無い(読み取り中心で MT5 に触らない)。ただし、急な値動きのチャイムは取れなかった間の動きを見落とし得る(推測)、
  永久ブロック・チャットの送信は返事が落ちると二重送信のおそれがある。
- 根本の対処はスマホ用の別ピア(別アドレス)を VPS に登録すること(VPS の変更が要る。利用者の判断待ち)。

### 残る課題

- PicoCalc と同時に使うと取り合いになる(同じピア)。見るだけならたまに途切れる程度(上の実測)。運用では片方だけつなぐ。
- 画面を消している間は取らない(通知・常時監視は未実装)。
- USB の adb 接続が数十秒ごとに切れる(`no devices/emulators found` → すぐ戻る)。ケーブルかポートの問題とみている(推測)。`adb wait-for-device` を挟めば動く。

## esp32cam(winapp の esp32cam の Android 版)(2026-10-14)

利用者の指示: 「D:\github\Python\winapp\apps\esp32cam の要件を満たす android アプリを android に合わせて新たに作って」

### 元の要件(winapp の README.md・MANUAL.md・logic/*.py で確認)

- ESP32-CAM(net_cam ファームウェア)6 台の MJPEG(ポート 81、Content-Length 無し → JPEG のマーカーで切る)を同時表示。
- タイルをクリックして 1 台を選ぶ(青枠)。撮影(`/capture?flash=&t=`、PC の時刻を t で送る)・一覧(`/photos`)・状態(`/status`)は選んだ 1 台。
  全カメラ接続/撮影は失敗をまとめて 1 つのダイアログ。受信中に切れた台はダイアログを出さずタイルと状態欄に。
- 写真のプレビュー(前後へ切り替え、題名に「名前（横×縦）n / 全数」)、PC に保存。IP 6 台分を保存(既定 192.168.1.141〜146)。

### Android 版の作り(判断)

| 判断 | 内容 | 理由 |
|---|---|---|
| 画面 | カメラ(タイル 6 枚。幅 600dp 未満は 2 列 × 3 行、以上は 3 列 × 2 行)→ 写真一覧 → プレビュー(HorizontalPager で左右スワイプ)。設定は別画面 | 縦持ちのスマホに 3 列は狭い。「事前の注意事項」の幅での切り替え |
| 通信 | `HttpURLConnection` + `BitmapFactory`。ライブラリ無し | 元の Python と同じ単純な HTTP。G12 |
| 裏に回ったら映像を止め、戻ったら同じ台をつなぎ直す(`onStop` / `onStart`) | 電池と WiFi のため | Android 流。実機で確認(ホーム中は PC への接続 0、復帰で新しい /stream) |
| 映像中は画面を消さない(`FLAG_KEEP_SCREEN_ON`) | 監視用途 | — |
| 保存 | Android 10 以上は MediaStore で「ピクチャ/ESP32CAM」、それより前はアプリ専用 Pictures。権限不要 | B1 |
| 平文 HTTP | `network_security_config.xml` の base-config で **全宛先の平文を許可**(Lint の InsecureBaseConfiguration は warning のまま) | **B2 の例外**。カメラは LAN 内の HTTP 専用機器で、IP は利用者が入れるため宛先を限定できない。このアプリは他と通信しない |
| 全角の IP 入力 | 全角の数字・点を半角に直す(`CameraApi.toHalfWidth`) | **実機で Gboard の日本語モードが「１９２．１６８…」と入れた**(事実)。利用者も同じ目に遭う |
| minSdk | 23、navigation 2.9.8 | mt5mon と同じ。古い端末でも使えるように |
| 文字 | ラベルは strings.xml。VM の診断文(「カメラに接続できませんでした…」など)は元の Python の文言をそのままコードに | C1 の例外(mt5mon と同じ扱い) |

### 試験の方法(実機のカメラ無し)

- PC(192.168.0.204)で **偽のカメラサーバ**(Python、ポート 80 の API とポート 81 の MJPEG。PIL で動く絵を作る)を立て、
  アプリの IP を 192.168.0.204〜209 にした(1 台成功・5 台失敗の両方の道を通す)。スクリプトは試験用で一時フォルダに置き、リポジトリには入れない
  (winapp の CLAUDE.md「外部機器が要るアプリの確認」と同じ考え方)。
- BL8800Pro(Android 11)で確認した事実(14:05〜14:10、adb の screencap): 1 台の映像が動く、失敗 5 台が 1 つのダイアログ、撮影 → 一覧の自動更新、
  状態の表示、写真一覧(新しい順)、プレビューとスワイプ、題名の大きさ、端末への保存(`/sdcard/Pictures/ESP32CAM/cam1_….jpg` が出来た)、
  裏に回す/復帰で映像の停止と再接続。
- 直した不具合: プレビューで先読みされたページの「現在の 1 枚」が取れず題名の大きさと保存が効かない(ページごとの読み込み結果を持つ形に)、
  接続エラーの一覧が届いた順(番号順に並べ替え)、Lint の NewApi(java.time → SimpleDateFormat)・ContextCastToActivity・LocalContextGetResourceValueCall。
- 全カメラ撮影も確認(14:12): 成功 1 台は載らず、失敗 5 台がカメラの番号順に 1 つのダイアログ。本文が長いと下が切れたので、ダイアログの本文をスクロール可能にした。
- **実機の ESP32-CAM(192.168.0.141)で映像を確認**(14:27)。800×600 の JPEG 約 26KB/枚。利用者「映らない」→ こちらで［接続］を押したら映った
  (起動しただけでは接続しない。元の Windows 版と同じ)。利用者「良い感じ」。
- 利用者「ボタンが大きく表示スペースの邪魔」(14:30)→ 題名の行をなくし、小さなボタン 1 段(全接続/光/撮影/全撮影/状態/写真)、歯車は下の状態行の右端、
  タイルの見出しを「1: 192.168.0.141」に、タイルは画面の高さいっぱい(映像は 4:3 のまま中に収める)。実機で 1 段に収まり IP が全部見えることを確認。
- 利用者「カメラをダブルタップしたら、そのカメラを全画面表示」(14:38)→ `ui/FullScreen.kt`(経路 `full/{index}`)。タイルは `detectTapGestures`
  (1 回タップで対象、ダブルタップで全画面)。全画面は 1 回タップか戻るで戻る。未接続ならつないでから出す。全画面中は画面を消さない。
  adb の `input tap` ではダブルタップを再現できない(1 回の送出が遅い)ので、**利用者の実機での確認待ち**。
  副作用: ダブルタップ判定を待つ分、1 回タップの反応が約 0.3 秒遅れる。
- 未確認: 6 台同時の負荷(実機は 1 台)、ダブルタップの全画面(利用者の確認待ち)。
- **3 台目: MP01(Android 11・API 30・arm64・user/release-keys、720×1600)に esp32cam を入れた**(2026-10-15 09:22)。
  最初は adb に見えず(USB デバッグ未設定)、利用者が開発者向けオプションから USB デバッグを ON にして見えた。
  IP を 192.168.0.141〜146 に設定(設定画面の「並べる」。この端末の Gboard も全角で入れたが、半角に直す処理で正しく保存された)。
  カメラ 1(192.168.0.141)の［接続］で映像が出た。クラッシュ無し。正規ビルドなので鍵の心配は無い(このアプリは鍵を持たない)。

## 作業記録

- 2026-10-15 09:40: mt5mon を picoclac の更新(値動き警報の知らせ)に追従し、MP01 に入れた。鍵は「開く」の口で渡し、LIVE を確認。GitHub 未 push。
- 2026-10-15 09:22: 3 台目の端末 MP01(Android 11)に esp32cam を入れ、実機カメラの映像を確認(上の esp32cam の節)。GitHub 未 push。
- 2026-10-14 14:40: esp32cam を実機カメラ(192.168.0.141)で確認。ボタンを 1 段の小さなものに、タイルを画面いっぱいに、ダブルタップで全画面を追加。
  mt5mon(H1 対応)と esp32cam をまとめてコミット・push(利用者の指示)。
- 2026-10-14 14:10: `apps/esp32cam` を新規作成(winapp の esp32cam の Android 版)。偽サーバで通信経路を確認、BL8800Pro で動作確認。
  BL8800Pro の mt5mon も最新 APK に入れ替えた(保留分)。GitHub 未 push。
- 2026-10-14 13:40: H1 が userdebug/test-keys の非正規に近い端末と確認。鍵の安全の注意を「鍵を扱うときの注意」として追加。
  端末ごとの別鍵・VPS 側での絞り込みを提案(利用者の指示待ち)。H1 のチャイムはアラーム音量 0 が原因(解決)。
- 2026-10-14 13:15: 2 台目 MECHEN H1(Android 6.0)対応。minSdk 23・WireGuard 1.0.20230706・navigation 2.9.8 に変更、
  config.txt を「開く/共有」で受け取る口を追加、VPN 許可画面が無い端末で落ちないようにした。
  H1 は root で TUN の装置ファイルと AppOps の VPN 許可を入れて WireGuard が上がり、LIVE のデータが出た(`tools/h1_vpn_setup.ps1`)。
  BL8800Pro には新 APK 未反映(次に USB でつないだら入れる)。GitHub 未 push。
- 2026-10-14 12:10: git 初期化、最初のコミット(61 ファイル)を https://github.com/take-undead/android に push。
- 2026-10-14 12:00: チャイムが鳴らない → おやすみモードが原因。用途を USAGE_ALARM に変えて解決(利用者が「鳴りました」と確認)。
  アプリは端末のアプリ一覧に「MT5 Monitor」(紺地に白い三角のアイコン)として入っている(利用者が見つけたことを確認)。
  APK は `apps/mt5mon/app/build/outputs/apk/debug/app-debug.apk`(git 管理外)。
- 2026-10-14: Android Studio 無しで SDK + JDK 17 を入れ(上の「開発環境の状態」)、mt5mon をビルド・Lint・実機インストール・起動まで確認。
  compileSdk を 37 に上げた(hello-world も)。config.txt を読み込み、WireGuard 接続 → モニター・日報・攻撃・チャットの全画面で
  VPS の実データが出ることを実機で確認(上の「実機での動作確認」)。
- 2026-10-14: `apps/mt5mon` を新規作成(PicoCalc の mt5mon + vpschat の Android 版。Kotlin + Compose、WireGuard 内蔵)。
  ビルド未実施・実機未確認。上の「mt5mon」の節に判断と根拠を記録。C1(文字直書き)の例外を VM の診断文に限って認めた(上の表)。

- 2026-10-13: 「後で手直しが大変になること」(G1〜G14)を追加。
- 2026-10-13: 「基本作法の注意事項」(A〜F)を追加。hello-world の Lint 設定にエラー項目を追加し、
  サンプルの文字列を strings.xml + stringResource() に変更(C1 の見本)。
- 2026-10-13: 「事前の注意事項」を追加。Lint `LockedOrientationActivity` をエラーに設定(hello-world)。
  出典: googlesamples.github.io/android-custom-lint-rules の同チェックのページ(既定の重大度は Warning)。
- 2026-10-13: リポジトリ初期化。`apps/` 構成を決め、`apps/hello-world`(Compose のカウンターアプリ)を作成。
  環境に JDK 17 / Android SDK が無いためビルド未実施。

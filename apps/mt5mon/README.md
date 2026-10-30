# mt5mon（Android 版）

PicoCalc の `apps/net/mt5mon`（MT5 状況モニター）と `apps/net/vpschat`（VPS の Claude と話す）を 1 本にまとめた Android アプリ。
画面と操作は Android に合わせて作り直した。VPS 側（status_server.py）はそのまま使う。WireGuard の鍵も PicoCalc と同じものを使う。

## できること

| 画面 | 内容 | 元のアプリ |
|---|---|---|
| モニター | USDJPY のローソク足（M1 / M5 / M15 / M30 / H1。横にドラッグで過去へ、ダブルタップで最新）、建玉・EA、口座、急な値動きのチャイム | mt5mon |
| 日報 | Claude 監督の判断の要約（/sup.json）と日報の全文（/nippo.md） | mt5mon の N |
| 攻撃 | fail2ban の記録（上位・永久・記録・国別）、永久ブロック・解除（確かめてから送る） | mt5mon の B |
| チャット | VPS の Claude と話す（日本語入力は Android の IME） | vpschat |
| 設定 | WireGuard のつなぐ・切る、config.txt の読み込み、チャイム、画面を消さない（スリープ禁止）、知らせの表示秒 | — |

どの画面でも、VPS からの知らせ（スプレッド警報の調査結果）が来ればポップアップで出してチャイムを鳴らす。

## 構成

```
mt5mon/
├─ app/src/main/java/app/mt5mon/
│  ├─ MainActivity.kt / Mt5App.kt      # 入口。Mt5App がアプリ全体で 1 つのもの（設定・HTTP・WireGuard）を持つ
│  ├─ data/                            # データ層（画面を知らない）
│  │  ├─ AppConfig.kt                  #   config.txt の読み取り（PicoCalc と同じ形）
│  │  ├─ Models.kt / Parsers.kt        #   VPS の JSON の形と読み取り
│  │  ├─ VpsApi.kt                     #   HTTP（HttpURLConnection。WireGuard の内側の 10.8.0.1:8080）
│  │  ├─ WgTunnel.kt                   #   WireGuard（公式ライブラリ com.wireguard.android:tunnel）
│  │  ├─ ConfigStore.kt                #   アプリの設定の保存（DataStore）
│  │  ├─ Chime.kt / AlertDetector.kt   #   チャイムの音と急な値動きの判定
│  ├─ vm/                              # 画面ごとの状態と取得の繰り返し（ViewModel）
│  ├─ ui/                              # 画面（Jetpack Compose）。色は ui/theme/Theme.kt に集約
│  └─ ...
└─ app/src/main/res/
   ├─ values/strings.xml               # 画面の文字
   └─ xml/network_security_config.xml  # 10.8.0.1 だけ平文 HTTP を許す（WireGuard の内側）
```

## 対応端末

Android 6.0（API 23）以上。32bit / 64bit の ARM。**VPN（VpnService）が使える端末であること**。
2026-10-14 に Blackview BL8800Pro（Android 11）と MECHEN H1（Android 6.0）で動作を確認。
H1 は端末に VPN の許可画面と `/dev/net/tun` が無いので、USB でつないで `tools\h1_vpn_setup.ps1` を流す（root で装置ファイルと VPN の許可を入れる。再起動のたびに必要）。
同じ鍵を使うので、**VPN をつなぐのは 1 台ずつ**（PicoCalc を含む）。

### config.txt をファイル選択画面なしで渡す

RAM の少ない端末ではファイル選択画面を開くとアプリが止められることがある。その場合はファイル管理アプリで `mt5mon_config.txt` を「開く」か「共有」して MT5 Monitor を選ぶ。adb からは次でも渡せる（`<id>` は `content query` で調べる。CLAUDE.md 参照）。

```
adb shell am start -n app.mt5mon/.MainActivity -a android.intent.action.VIEW -d content://media/external/file/<id> -t text/plain --grant-read-uri-permission
```

## 端末での場所

アプリ一覧に **「MT5 Monitor」**（紺地に白い三角のアイコン）として入る。adb で直接入れるので、ストアは通らない。

## 最初の 1 回（鍵を入れる）

1. PC の `D:\github\picoclac\build\mt5mon\config.txt`（`tools\mt5mon_config.py` が作ったもの。秘密鍵入り）を端末に写す
   （USB ケーブルで Download フォルダへ、など。**人に渡さない**）。
2. アプリの「設定」→「ファイルから読み込む」でそのファイルを選ぶ。公開鍵が表示されるので、VPS の `[Peer]` と同じことを確かめる。
3. 「つなぐ」を押す。Android の VPN の許可画面が出るので許可する。
4. ハンドシェイクが通ればモニター画面にデータが出る。
5. 端末に写した config.txt は消してよい（アプリの専用領域に保存済み）。

PicoCalc と同じ鍵・同じアドレス（10.8.0.3）なので VPS 側の登録は要らない。ただし **PicoCalc の mt5mon / vpschat と同時にはつなげない**（同じピアは 1 つ。後からハンドシェイクした方が勝つ）。

## 今ある VPN を使う(鍵なし。2026-10-31 追加)

端末にもう VPS への VPN(公式 WireGuard アプリなど。my-ai-bot の端末ごとの鍵)があるなら、設定の「今ある VPN を使う」をオンにする。
このアプリはトンネルを張らず、config.txt も読まずに `http://10.8.0.1:8080/` を読む(url と取得間隔は `mt5mon_config.py` の既定と同じ)。

- Android は VPN を 1 つしか持てない。自分でトンネルを張ると、ほかの VPN(マイボットの WireGuard など)が切れる。これを避けるため。
- 端末に本番の鍵(PicoCalc と共用の 10.8.0.3)を置かずに済み、PicoCalc とも取り合いにならない。
- mt5-status は 10.8.0.0/24 からなら受ける(`status_server.py` の `WG_NET`)ので、端末の VPN のアドレスが何番でも読める。永久ブロック・解除の POST もその端末から送れる。
- 届くかどうかは HTTP の結果でしか分からないので、VPN の札はつながっている扱い(緑)にする。届かなければ HTTP の失敗として出る。
- 保存のキーは `external_vpn`(既定 false。今までの端末は変わらない)。読み込んだ config.txt は消さずに残る。
- 2026-10-31 に HEADWOLF F5(Android 14。my-ai-bot の WireGuard 10.8.0.10)で、マイボットの VPN を保ったままモニターが LIVE になるのを確かめた。

## 画面を消さない（2026-10-31 追加）

設定の「画面」→「画面を消さない（スリープ禁止）」をオンにすると、このアプリが前にある間だけ画面が自動で消えない（`View.keepScreenOn`）。
ほかのアプリに移るか電源ボタンを押せば今までどおり消える。保存のキーは `keep_screen_on`（既定 false）。
HEADWOLF F5 で、オンの間は WindowManager が mt5mon の分の SCREEN_BRIGHT の wake lock を持ち、オフで外れることを `dumpsys power` で確かめた。

チャートの建値の「買」「売」は 18sp の太字にした（前は時刻の目盛りと同じ 10sp）。

## ビルド

Android Studio で `apps/mt5mon` を開くか、コマンドで（JDK 17 と Android SDK の場所は環境変数で指す。`local.properties` は作らない）:

```
set JAVA_HOME=C:\Users\datak\.jdks\jdk-17
set ANDROID_HOME=C:\Users\datak\AppData\Local\Android\Sdk
cd apps\mt5mon
gradlew.bat lintDebug assembleDebug
%ANDROID_HOME%\platform-tools\adb.exe install -r -t app\build\outputs\apk\debug\app-debug.apk
```

2026-10-14 にこの手順でビルドが通り、実機（Android 11）で起動を確認した。

2026-10-14 に実機（Android 11）で、config.txt の読み込み → WireGuard 接続 → モニター（M1/M15）・日報・攻撃・チャットの表示まで確認した。

## 未確認のこと

- 永久ブロック・解除・チャットの送信（本当に VPS を操作するので試験では押していない）。
- 知らせのポップアップ（VPS に新しい知らせが来たとき）。

## チャイムについて

チャイムは端末の**アラーム音量**で鳴る（おやすみモードでも鳴るように「アラーム」の用途にしてある）。
鳴らないときは、まず端末のアラーム音量（設定 → 音）を確かめる。2026-10-14 の 2 台はどちらも端末側が原因だった（おやすみモード／アラーム音量 0）。
アプリ内の音量（設定のスライダー、1〜10）は端末のアラーム音量に重ねてかかる。
- 画面を消している間の取得（バックグラウンド）。今は画面を開いている間だけ取る。

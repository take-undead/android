# esp32cam（Android 版）

`D:\github\Python\winapp\apps\esp32cam`（Windows の Tkinter 版）と同じ要件を Android で作り直したもの。
ESP32-CAM（AI-Thinker、`net_cam` ファームウェア）**6 台**の映像を同時に表示し、撮影を指示し、SD カード内の写真を見る。
カメラ側のファームウェアとエンドポイントは元のまま。

## できること（元の要件との対応）

| 要件 | Android 版 |
|---|---|
| 6 台の MJPEG 映像を同時表示 | カメラ画面のタイル 6 枚（縦持ち 2 列 × 3 行、横長・タブレット 3 列 × 2 行） |
| タイルをクリックして対象を選ぶ（青枠） | タイルをタップ。青枠と下の「対象: カメラ n」 |
| 1 台だけ接続・切断 | タイル右上の［接続］［切断］ |
| 全カメラ接続・切断、失敗はまとめて 1 つのダイアログ | ツールバーのボタン。失敗はまとめて 1 つのダイアログ |
| 撮影（フラッシュ有無、PC の時刻を t で送る） | ［撮影］［フラッシュ］。端末の時計を送る。撮影後に一覧を自動更新 |
| 全カメラ撮影、「4 / 6 台で撮影しました」 | ［全カメラ撮影］。同時に送る。結果は状態欄とダイアログ |
| 写真一覧（新しい順）、表示、前後へ切り替え | ［写真］で一覧画面 → タップでプレビュー。左右スワイプで前後へ。題名に「名前（横×縦）n / 全数」 |
| PC に保存 | プレビュー右上の保存。Android 10 以上は「ピクチャ/ESP32CAM」、それより前はアプリ専用の Pictures |
| 状態（SD カード容量と IP） | ［状態］で下の状態欄に出す |
| IP を settings.json に保存 | 設定画面で 6 台分を入力。DataStore に保存 |
| 受信中に切れた台はダイアログを出さない | タイルに「切断されました」、状態欄に理由 |

Android ならではの振る舞い:

- 画面が裏に回ると映像を止め、戻ると同じ台をつなぎ直す（電池と WiFi のため）。
- 映像を受けている間は画面を消さない。
- 写真の保存に権限は要らない。

## 通信するファームウェアのエンドポイント（元と同じ）

| 用途 | エンドポイント |
|---|---|
| 映像 | `http://<ip>:81/stream`（multipart MJPEG。Content-Length が無いので JPEG のマーカーで切る） |
| 撮影 | `http://<ip>/capture?flash=on|off&t=YYYYMMDDHHmmss` |
| 写真一覧 | `http://<ip>/photos` |
| 写真取得 | `http://<ip>/photo?file=YYYYMM/YYYYMMDD_HHmmss.jpg` |
| 状態 | `http://<ip>/status` |

## 構成

```
app/src/main/java/app/esp32cam/
├─ MainActivity.kt / Esp32CamApp.kt   # 入口。裏に回ったら映像を止める
├─ data/
│  ├─ CameraApi.kt      # HTTP API（ポート 80）。元の logic/api.py
│  ├─ MjpegStream.kt    # MJPEG 受信（ポート 81）。元の logic/stream.py
│  ├─ HostStore.kt      # 6 台分の IP とフラッシュの保存。元の logic/settings.py
│  └─ PhotoSaver.kt     # 端末への保存
├─ vm/CamerasViewModel.kt  # 画面の状態と操作（接続・撮影・一覧・状態）
└─ ui/                  # カメラ / 写真一覧 / プレビュー / 設定（Jetpack Compose）
```

依存ライブラリは Compose・Navigation・DataStore だけ。画像のデコードは Android 標準の BitmapFactory。

## ビルド

```
set JAVA_HOME=C:\Users\datak\.jdks\jdk-17
set ANDROID_HOME=C:\Users\datak\AppData\Local\Android\Sdk
cd apps\esp32cam
gradlew.bat lintDebug assembleDebug
```

## 画面の作り（2026-10-14 に利用者の要望で詰めた）

- 題名の行は無し。上に小さなボタン 1 段（全接続 / 光（フラッシュ）/ 撮影 / 全撮影 / 状態 / 写真）。設定は下の状態行の右端の歯車。
- タイルは画面の高さいっぱいに 2 列 × 3 行（横長は 3 列 × 2 行）。見出しは「1: 192.168.0.141」。映像は 4:3 のまま中に収める。
- **起動しただけでは接続しない。** タイルの［接続］か［全接続］を押す（元の Windows 版と同じ）。
- タイルを **1 回タップで対象**（青枠）、**ダブルタップでそのカメラを全画面**。全画面は 1 回タップか戻るで元に戻る。

## 確認したこと・未確認のこと

- 実機の ESP32-CAM（192.168.0.141、800×600）で映像の表示を確認。偽サーバで撮影・一覧・プレビュー・保存・全台の失敗のまとめを確認。
- 未確認: 6 台同時受信時の負荷（端末の性能による）。

# hello-world

最初のサンプルアプリ。画面に「Hello, Android!」とボタンを表示し、タップ回数を数える。

## 構成

```
hello-world/
├─ settings.gradle.kts        # プロジェクト名と含めるモジュール
├─ build.gradle.kts           # プラグインの宣言(適用は app 側)
├─ gradle.properties          # Gradle / AndroidX の共通設定
├─ gradle/libs.versions.toml  # 使うライブラリとバージョンの一覧
├─ gradle/wrapper/            # Gradle 本体を自動取得する仕組み
└─ app/                       # アプリ本体
   ├─ build.gradle.kts        # SDK バージョン・依存ライブラリ
   └─ src/main/
      ├─ AndroidManifest.xml  # アプリの宣言(アイコン・起動画面)
      ├─ java/.../MainActivity.kt  # 画面のコード(Jetpack Compose)
      └─ res/                 # 文字列・色・アイコン
```

## 開き方

Android Studio で `apps/hello-world` フォルダを開く(`D:\github\android` ではなくアプリのフォルダを開く)。

## 注意事項のチェック

向きの固定(`android:screenOrientation`)などはエラーになるよう Lint を設定している。次で確認できる。

```
cd apps\hello-world
gradlew.bat lintDebug
```

## コマンドでビルド

Android Studio を入れて JDK 17 と Android SDK が揃ってから実行する。

```
cd apps\hello-world
gradlew.bat assembleDebug
```

APK は `app\build\outputs\apk\debug\app-debug.apk` に出る。

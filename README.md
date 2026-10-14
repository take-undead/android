# android

Android アプリを作るためのリポジトリ。アプリは `apps/` の下に 1 アプリ 1 フォルダで置く。

```
android/
├─ CLAUDE.md     # 経緯と作業記録(Claude Code が参照・追記する)
├─ README.md     # このファイル
├─ .gitignore
└─ apps/
   ├─ hello-world/   # 最初のサンプルアプリ（新しいアプリの雛形）
   └─ mt5mon/        # MT5 状況モニター + VPS の Claude とのチャット（PicoCalc の mt5mon / vpschat の Android 版）
```

## ルール

- アプリは `apps/<アプリ名>/` に置く。アプリ名は小文字とハイフン(例: `hello-world`)。
- 各アプリは独立した Gradle プロジェクト。Android Studio ではアプリのフォルダを開く。
- 各アプリのフォルダに README.md を置き、何をするアプリかと開き方を書く。

## 作る前の注意(詳細は CLAUDE.md「事前の注意事項」)

- スマホ版を先に作り、タブレット向けはあとから調整する。
- **画面の向きを固定しない**(`android:screenOrientation` を書かない)。書くと Lint がエラーにしてビルドが止まる。
- 幅・高さを固定値で決め打ちしない。状態は `rememberSaveable` か ViewModel に持つ。
- 画面の文字は `strings.xml` に置く。コードに直書きしない。
- パッケージ名(`applicationId`)は最初に本番用の名前で決める(`com.example.*` のまま作り続けない)。
- 後から直すのが大変なこと(データの保存形式、画面遷移、テーマなど)は CLAUDE.md の「後で手直しが大変になること」を先に読む。

## 必要なもの

- Android Studio(JDK 17 と Android SDK が同梱される)

## 新しいアプリを追加するとき

1. `apps/hello-world` をコピーして名前を変える。
2. 次の名前を変える。
   - `settings.gradle.kts` の `rootProject.name`
   - `app/build.gradle.kts` の `namespace` と `applicationId`
   - `app/src/main/res/values/strings.xml` の `app_name`
   - Kotlin のパッケージ名とフォルダ(`com/example/helloworld`)
3. そのアプリの README.md を書き換える。
4. `CLAUDE.md` の作業記録に 1 行追記する。

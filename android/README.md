# 作品用カメラ（Android・自分用）

iOS 版と同じ流れ（撮影 → 切り抜き → 縁の調整 → 背景 → 書き出し）の、自分で使うための Android 版。
ストアには出さず、APK を直接インストールする。

- 課金・透かしはなし。全部の背景が使える
- 背景素材は iOS 版と同じ `../Backgrounds`（manifest.json と画像）をそのまま同梱する
- 切り抜きは端末内の ML Kit（被写体セグメンテーション）。初回はモデルの取り込みに少し時間がかかる（Google Play 開発者サービスが必要）
- 合成は iOS 版と同じ「背景 × (1 − マスク) + 元写真 × マスク」の整数演算。マスク=255 の画素が元写真と完全一致することを `InvarianceTest` で確認している

## ビルド

Android Studio に入っている JDK を使う。

```sh
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

- 出力：`app/build/outputs/apk/release/app-release.apk`
- `ScreenshotTest` が各画面を `app/build/screenshots` に画像で書き出す（実機なしで見た目を確認するため）

## 署名

`keystore.properties` と `signing/sakuhin-personal.jks`（どちらも Git に入れない）で署名する。
**この2つをなくすと、同じアプリとして上書き更新できなくなる**（一度アンインストールが必要）。別の場所にもバックアップしておくこと。

## インストール

1. APK をスマホにコピーする（USB、Google ドライブなど）
2. スマホのファイルアプリから APK を開く。「この提供元のアプリを許可」を求められたらオンにする
3. 更新するときも同じ手順で上書きインストールできる

USB デバッグを有効にしているなら `adb install -r app-release.apk` でもよい。

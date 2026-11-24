# PocketForge 1.0.0 — 個人用Android版

ChatGPTに指示し、端末内のプロジェクトファイルを作成・修正するアプリです。HTML/CSS/JavaScriptのプレビューとJavaScriptのロジック実行を端末内で行います。PCや独自サーバーは不要ですが、AI利用時はインターネット接続が必要です。

## 最初に使う

1. `pocketforge-1.0.0-personal.apk` をAndroid端末にインストールします。Android 8.0以降が対象です。APKを開いたアプリに対し、Androidの画面に従ってインストールを許可します。
2. 「実行」→「プレビューを開く」で、同梱の集中タイマーをログインなしで試せます。
3. 「設定」→「ChatGPTにログイン」で外部ブラウザを開き、自分のアカウントで認証します。ChatGPTプランの利用に同意してください。認証後はPocketForgeへ戻ります。
4. 利用可能モデルを取得後、「作業」でモデルを選び、例えば「このタイマーに5分休憩のボタンを追加して」と指示します。
5. AIはプロジェクトの読込・検索・編集、JavaScript実行を繰り返します。「実行」で変更を確認します。「ファイル」から手動編集もできます。

OpenAIの公開仕様に沿ったSign in with ChatGPTを実装しています。利用枠は接続したChatGPTアカウント側の枠です。APIキーの入力欄はありません。接続できるモデルはアカウントから取得し、未取得時に固定モデルを代用しません。利用権限や枠がない場合はエラーを表示します。

## 機能と範囲

- 複数プロジェクト、会話履歴、手動ファイル編集、ファイル添付。
- AIのファイル検索・読込・書込・削除、HTML参照検査、JavaScript実行。
- 編集前スナップショット、直近12件から復元。
- 1回の指示につき最大20回のモデル応答。停止後も完了済みのファイル変更は残ります。
- HTMLプレビューは独立したWebView。プロジェクト内の画像・CSS・JavaScript・通常のフォームを利用できます。ファイル入力ではユーザーが選択したファイルを読めます。
- プレビューは外部ネットワーク、外部CDN、カメラ、位置情報を利用しません。必要なライブラリはプロジェクトに同梱してください。
- JavaScript実行は空のDOMで約10秒まで。console出力、戻り値、エラーを取得します。プロジェクト内アプリの画面全体をAIが自動操作するものではありません。
- ZIP書出し・読込み。Androidの保存先選択にGoogle Driveが出る場合は、登録なしでZIPをそこへ保存できます。
- Google Driveの通常ファイルをPCと双方向で手動同期。初回設定は `GOOGLE_DRIVE_SETUP.md` を参照してください。

この版はHTML/CSS/JavaScript向けです。Python、OSシェル、npm、Gradle、APK生成は端末内ランタイムに含みません。公式Codexの全機能を移植したアプリではありません。

1ファイル8MiB、1プロジェクト32MiB、最大2,000エントリー。会話保存は4MiBまでです。長い会話で上限に達した場合は、必要な会話をZIPに残してから会話を消去してください。

## Google Drive

Google CloudでDrive APIを有効にし、Android OAuthクライアントに下記を登録する必要があります。Google側の登録作業はこの配布物には含まれません。

- パッケージ：`jp.haruyalab.pocketforge`
- SHA-1：`09:92:EF:32:E8:A9:06:34:E1:EF:D8:6F:19:73:06:02:34:F4:D7:BC`

PCで新規作成したファイルも取得するため、Googleの承認画面ではDrive全体の権限を要求します。実装上の同期対象はこのアプリのプロジェクトフォルダーです。同期ボタンを押した時だけ同期し、会話や認証情報は同期しません。

両側で変更されたファイルは競合コピーを作って両版を残します。片側の削除は反対側に伝えず、残った側から復元します。PC側のアップロードが完了してから同期してください。

## データと認証

ChatGPTトークンはAndroid Keystoreの鍵で暗号化してアプリ専用領域へ保存します。JavaScript画面、生成されたアプリ、ZIP、Driveには渡しません。Google認可はGoogle Play開発者サービスを使い、トークンをアプリのファイルへ永続保存しません。

AIに指示すると、指示・会話・AIが読み込んだプロジェクト内容がOpenAIに送られます。Driveを接続し同期すると、プロジェクトのソースファイルが自分のDriveに送られます。独自の中継サーバー、広告、解析SDKは入れていません。

同じプロジェクトのHTMLプレビューではlocalStorageを保持します。ただし、その保存内容はソースファイルではないためZIPやDrive同期には含まれません。アプリのデータ消去・アンインストールで、端末内プロジェクトと認証情報は失われます。必要なものはZIPに書き出してください。

ログイン時はブラウザの画面を閉じずに完了させてください。通常のアプリ切替では認証を継続します。OSがアプリを終了した場合は再ログインが必要です。AI処理も端末が強制終了すれば継続しません。

## 検証済み／未検証

- Java/Android SDK 36でコンパイルし、DEX生成・APK署名・署名検証・zipalign確認を実施。
- JVMテスト：保存領域52 assertions、AI/SSE36 assertions、認証22 assertions、Drive16シナリオカテゴリが成功。
- モバイル幅のChromiumで、画面初期化・ファイル保存・下書き保持・認証状態・ストリーミング・JSエラー・Drive未接続・3画面幅の表示を模擬Nativeブリッジで検証。
- 実Android端末／エミュレーターへのインストール起動、実アカウントでのChatGPT認証・推論、Android Keystore動作、Google認可・実Drive往復同期は未検証。ユーザーの認証を代行したテスト結果ではありません。

## ソースからビルド

Android SDK Platform 36、Build Tools、JDK 17以降、Python 3を用意します。

```sh
python3 third_party/fetch_google_auth.py
bash scripts/build.sh --sdk /path/to/android-sdk --jdk /path/to/jdk
python3 scripts/test.py --sdk /path/to/android-sdk --jdk /path/to/jdk
```

APKは `build/release/pocketforge-1.0.0-personal.apk` に生成されます。初回ビルドは新しい個人用署名鍵を `signing/` に作成します。配布APKを同じパッケージのまま更新するには、別途渡した鍵バックアップを復元し、同じ鍵を使ってください。鍵とパスワードをソース公開に含めないでください。

Android Studio用Gradle構成とGradle 8.11.1 wrapperも同梱しています。実際に検証した配布ビルドは上の手動ビルドスクリプトによるものです。Gradleで更新用のreleaseを作る場合は署名設定を別途指定してください。

UI検証はNode.js、Playwright、Chromiumを使います。`tests/ui-qa.cjs` の `POCKET_CHROME` 環境変数にChromium実行ファイルを指定してください。外部サービスには接続しないUIテストです。

## 個人利用と公式資料

2026-11-05時点の公開案内では、ローカルで動作する個人プロジェクトはChatGPTプラン連携の公開対象です。有料アプリやリモートホスト型アプリを利用者へ提供する際は別途アクセス申請の対象です。この版は個人用として作成しました。

- https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developer.android.com/identity/authorization

外部依存の原著作権・ライセンス表示は `app/src/main/assets/third_party_notices/` と、アプリの「設定」→「ライセンス」に同梱しています。

## ライセンス

PocketForgeの独自コードはMITライセンスです。同梱する依存物の条件と著作権表示は `third_party/` と `app/src/main/assets/third_party_notices/` にあります。 LICENSEを参照してください。

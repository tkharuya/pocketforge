# Google Drive 接続設定 — PocketForge 個人版

Google Drive の同期コードは実装済みです。利用者の Google Cloud に Android OAuth クライアントを登録してから接続できます。この登録は ChatGPT のログインとは別です。配布 APK の中に他人の Google クライアント ID・秘密鍵・トークンは入っていません。

## この APK の登録値

| 項目 | 値 |
| --- | --- |
| アプリ種別 | Android |
| パッケージ名 | `jp.haruyalab.pocketforge` |
| 署名証明書 SHA-1 | `09:92:EF:32:E8:A9:06:34:E1:EF:D8:6F:19:73:06:02:34:F4:D7:BC` |
| Google API | Google Drive API |
| OAuth スコープ | `https://www.googleapis.com/auth/drive` |

この SHA-1 は同梱の `pocketforge-1.0.0-personal.apk` の署名証明書の値です。別の鍵で再ビルドした場合は変わります。再ビルド後は `build/release/google-oauth-android-sha1.txt`、または Android SDK の次のコマンドで確認してください。

```sh
apksigner verify --print-certs build/release/pocketforge-1.0.0-personal.apk
```

## Google Cloud での初回設定

1. 自分の Google アカウントで [Google Cloud Console](https://console.cloud.google.com/) に入り、個人検証用のプロジェクトを作ります。
2. 「API とサービス」から **Google Drive API** を有効にします。
3. 「Google Auth Platform」の Branding / Audience / Data Access を設定します。個人の Gmail を使う場合は External とし、開発中は Testing にして自分のメールアドレスをテストユーザーに追加します。実際のアプリ名・連絡先を入力します。
4. Data Access に上表の Drive スコープを追加します。
5. Clients で OAuth クライアントを作り、種類を **Android** にします。上表のパッケージ名と SHA-1 を登録します。
6. Google Play 開発者サービスが使える Android 端末に APK をインストールします。アプリの設定から「Drive に接続」を押し、登録したアカウントで同意します。
7. プロジェクトを開いて手動同期します。Drive の **マイドライブ / PocketForge / プロジェクト名 [ID]** に通常のフォルダー・ファイルができます。

Android のクライアント側認可だけを使うため、この実装に Web クライアントの秘密鍵、リダイレクト URL、API キーを入力する欄はありません。Google の Android 認可 API がパッケージと署名を識別します。設定を省略した状態では Google との接続は成功しません。

開発中アプリの同意画面、テストユーザー制限、Workspace 管理者の制限が適用される場合があります。個人で試すための設定と、不特定多数に公開するための Google の審査は別です。この個人版をそのまま商用公開可能と判断しないでください。

## PC での使い方

PC で同じ Google アカウントの [Google Drive for desktop](https://support.google.com/drive/answer/10838124?hl=ja) を使うか、Drive の Web 画面からファイルをダウンロード・更新します。通常のソースファイルとして編集し、PC 側で Drive へのアップロード完了を確認してから Android 側の同期ボタンを押してください。

このアプリは自動・常時同期を行いません。PC と Android で編集を交互に行い、編集を始める前と終えた後に同期してください。

## なぜ Drive 全体の権限を要求するのか

狭い `drive.file` スコープはアプリが作成したファイルや、ユーザーがアプリに個別共有したファイルを対象にします。フォルダーを作っただけで PC がその中に新規作成するすべての子ファイルへのアクセスまで保証される仕組みではありません。

この版は PC で新しく追加したソースも取り込むため、restricted に分類される `drive` スコープを要求します。**権限自体は Drive 全体の読み書きを許します。** 実装上の同期対象は PocketForge が作成した、選択中プロジェクトのフォルダー配下に限定しています。Google 側でプロジェクトフォルダーだけに権限が制限されるわけではありません。

Google 全体への権限を与えたくない場合は Drive を接続せず、ZIP の入出力を使ってください。一般公開に進む場合は、Google Picker を使った権限縮小や専用同期サーバー等を別途設計し、Google の審査要件を確認する必要があります。

## 同期するもの・しないもの

| 対象 | 動作 |
| --- | --- |
| HTML / CSS / JavaScript / テキスト / 画像など通常ファイル | 双方向で手動同期 |
| 空のフォルダー | 双方向で作成 |
| PC で新規作成した通常ファイル | 次回同期で Android に取り込み |
| アプリ内の会話・スナップショット・設定・認証情報 | 同期対象外 |
| Google ドキュメント / スプレッドシート / スライド / ショートカット | 対象外として報告 |
| 同名の重複ファイル、ファイルとフォルダーの名前衝突 | 同期を停止して名前変更を要求 |

1 ファイル 8 MB、1 プロジェクト 32 MB、ファイルとフォルダー合計 2,000 件がこの版の上限です。ネイティブ Android プロジェクト全体の巨大なビルド成果物を同期する用途には対応していません。

## 競合・削除・復旧

- 前回の同期内容と、現在のローカル内容・Drive 内容の MD5 を比較します。Drive の version と headRevisionId も転送前後の検証に使用します。
- 両方で異なる内容に編集された場合、Android の版を `名前.local-conflict-日時.拡張子` で残し、Drive にも別ファイルとしてアップロードします。元の名前には Drive 版を取り込みます。自動でコードをマージしません。
- 同期前にアプリ内スナップショットを作ります。保存数は ProjectStore の上限に従います。
- 片側で削除したファイルは、残っている側から復元します。削除・名前変更を自動で反対側へ伝える機能はありません。不要ファイルを完全に消すときは同期を止め、両側で削除してください。
- Drive の既存ファイルを書き換える直前に、その旧リビジョンを `keepForever` で保存します。保護に失敗した場合は上書きせず停止します。
- Google の固定リビジョンは **1 ファイル 200 版まで**で、Drive の保存容量を消費します。このアプリは固定版を自動削除しません。上限・容量に達したら、必要な版を保管したうえで Drive の版管理を整理するか、新しいファイル名で運用してください。固定版を削除する操作は取り消せないため、アプリが勝手に行うことはありません。
- 同期中の更新を検出したら停止します。ただし Drive の検証と書き込みは完全な排他トランザクションではありません。**書き込みの瞬間まで PC が同時更新する場合の無損失保証はありません。** 同時編集は避けてください。

別の Google アカウントへの誤送信を防ぐため、プロジェクトが一度同期したアカウントと異なる場合は処理を拒否します。Google の接続解除は Google の許可を取り消し、アプリ内の接続情報を消します。プロジェクトファイルや Drive のファイルは削除しません。

## 実装と検証状況

- Google Identity Services の `AuthorizationClient`、`play-services-auth:22.0.0` を使用します。承認画面は Google Play 開発者サービス経由で表示します。
- OAuth アクセストークンは JavaScript やディスクへ保存しません。Google 側のトークンキャッシュからバックグラウンドで取得し、HTTP 401 時は無効化して一度だけ再取得します。再同意が必要な場合は接続ボタンからの操作を求めます。
- 認可の後、Drive の `/about` に実際にアクセスできた場合だけ接続成功として扱います。接続後、各同期で同じ Google アカウントか再検証します。
- Drive REST API v3 を使用し、外部 SDK に依存しない `DriveSync.Transport` の差し替えにより模擬 REST テストを実行しました。新規アップロード、PC 編集の取得、PC 新規ファイル、変更なし、競合の両版保存、削除復元、アカウント不一致、401 再認可、旧版固定失敗時の上書き阻止、転送中更新、重複名、バイナリ、空フォルダーを確認しました。
- Android SDK 36 と Google の依存ライブラリでコンパイルしました。**利用者の Google Cloud 登録・実端末での Google 認可・実 Drive 上の往復同期は未検証です。** 利用者の OAuth 登録と初回同意が必要であり、別のアカウントの認証情報を代用していません。

## 公式資料

- [Android: Authorize access to Google user data](https://developer.android.com/identity/authorization)
- [Drive API のスコープ](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [OAuth 同意画面の設定](https://developers.google.com/workspace/guides/configure-oauth-consent)
- [Drive のリビジョン管理と keepForever の制限](https://developers.google.com/workspace/drive/api/guides/manage-revisions)
- [Drive ファイルのアップロード](https://developers.google.com/workspace/drive/api/guides/manage-uploads)
- [Google OAuth の公開前確認・審査](https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification)

確認日: 2026-11-05

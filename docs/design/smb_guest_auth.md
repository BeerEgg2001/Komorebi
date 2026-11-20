# SMB ゲスト接続の認証方式の不一致

SMB 共有上の動画が「VLCエンジン: 再生エラーが発生しました」で再生できない不具合の調査記録と対応方針。**2026-11-19 時点で原因は確定、恒久対応は未決。**

## 症状

- SMB サーバー上の動画を再生しようとすると `VLCエンジン: 再生エラーが発生しました` が表示され、再生が始まらない
- **ファイルの一覧表示は成功する。再生だけが失敗する**
- 再生を試みたすべてのファイルで発生（h264 の mp4、DVD-Video の iso）
- 別の利用者は「適当に再生を試していたら、いつの間にか再生できるようになった」
- アプリの再起動・キャッシュ削除・アンインストール再インストールでも変わらない
- 報告環境: Fire OS 8.1.8.0 / Komorebi 1.1.1-Stable、universal・armeabi-v7a の両方で発生

## 原因（確定）

**一覧取得と再生で、送っている SMB 認証が完全に別物だった。**

| | 実装 | ゲスト接続時に送るもの |
|---|---|---|
| 一覧 | jcifs-ng（`SmbContextBuilder`） | `withGuestCrendentials()` = **ユーザー名 `GUEST` ＋ 空パスワードの NTLMv2 認証** |
| 再生 | libVLC に `smb://` URL を渡す | **完全な匿名（NULL）セッション** |

再生側は URL に資格情報を付けていなかった。VLC の `smb2.c` は認証情報がないとき `username = "Guest"` を渡すが、同時に `password = NULL` にする。libsmb2 の `encode_ntlm_auth()` は `password == NULL` を見て `anonymous = 1` とし、**`NTLMSSP_NEGOTIATE_ANONYMOUS` を立てて user / domain / workstation フィールドごと省略する**。つまり `"Guest"` という文字列はワイヤ上に一切出ない。

jcifs 側は `isGuest()` と `isAnonymous()` を別概念として扱い、GUEST では匿名フラグを立てず、MIC/署名も意図的に外す（`NtlmContext.java`）。**この2つは SMB 的に別物**であり、サーバー側で一方だけを許可する構成はごく普通にある。

Samba では匿名トークンを発行する条件が「ユーザー名が空」のときに限られ（`source3/auth/auth_builtin.c` の `check_anonymous_security`）、共有の `guest ok = yes` は **tree connect 段の設定で session setup の認証を免除しない**。`map to guest` の既定値は `Never`。

## 環境によって成否が逆転する（これが対応を難しくしている）

**実機で確認した事実。**

| 環境 | 匿名セッション | `guest` ＋ 空パスワード |
|---|---|---|
| OpenMediaVault 8.5.12-1（Samba、**SMB1 無効**） | 再生**成功** | 再生**失敗** |
| Synology DS218+ / DSM 6.2.2-24922 / Samba 4.4.16（最大 SMB2 / 最小 SMB1） | 再生**失敗** | 再生**成功** |

**どちらか一方に固定することはできない。** 片方を直すともう片方が壊れる。

同じ Synology 環境で、同じテレビ（REGZA）から **Kodi では guest 接続で再生できている**ため、サーバー設定の問題ではなく libVLC の SMB 実装側の問題であることも確認済み。

## 「いつの間にか再生できた」の説明

`SettingsViewModel.smbServerList` は `stateIn(viewModelScope, WhileSubscribed(5000), emptyList())` で、**この StateFlow を購読しているのは再生画面だけ**（一覧は `SmbViewModel.initSmb()` が `settingsRepository.smbServerList.first()` で別に取得している）。そのため初回再生時の第1コンポジションでは必ず空リストで、認証情報が未取得のまま LibVLC が構築されうる。

`stateIn` のキャッシュ値はプロセスが生きている間保持されるため、2回目以降の再生では最初から認証情報が入る。これが「いつの間にか再生できた」の機序。

ただし**今回の報告者はゲスト接続（ユーザー名が空）であり、この経路では `vlcTargetUri` が変化しないため作り直しが起きない**。したがってこれは別の環境で起きうる独立した不具合であり、今回の直接の原因ではない。

## 否定した仮説（いずれも裏取り済み）

| 仮説 | 否定の根拠 |
|---|---|
| `setHWDecoderEnabled(true, true)` の `force` が SW フォールバックを禁止する | `Media.class` を逆アセンブルして確認。オプションは最後に `all` が付くため SW へ落ちる。`force` は「端末のデコーダ判定が UNKNOWN でも HW を試す」の意味 |
| 軽量版 libVLC で SMB モジュールが削られている | `libvlc.so` の configure 行に `--enable-smb2`、`libsmb2`（SMB2/3）と `LIBDSM`（SMB1）の両方がリンク済み |
| URL の二重エンコード | jcifs-ng 2.1.10 の `SmbFile.encodeRelativePath()` は恒等関数。`Uri.encode` は単一エンコードで正しい |
| ポート付き URL の食い違い | jcifs が省略時も必ず 445 を埋めるため常に `smb://host:445/...` で一貫。libsmb2 も `host:port` を分解する |
| Fire OS / ネットワーク / 445 到達性 / NTLM の OS 制約 | **一覧が成功している事実がその証明**。`usesCleartextTraffic` と `network_security_config` は HTTP スタックにしか効かず SMB に無関係 |
| パスワードの `@` `/` で `smb2_parse_url` が壊れる | 報告環境はユーザー名が空で資格情報を一切付けていないため無関係。**ただし認証ありの環境では実在する不具合**（下記） |

## DVD-Video の ISO は構造的に非対応（別件）

`libvlc.so` の configure は **`--disable-dvdnav`**（`--enable-dvdread` のみ）。VLC 3.0 で「ストリーム上の DVD-Video ISO」を扱えるのは `dvdnav.c` の demux サブモジュールだけで、`dvdread.c` は `access_demux` スコア0かつ shortcut 不一致のため `smb://` では選ばれない。ISO9660/UDF デマルチプレクサも不在。

**サーバーや設定の問題ではなく、ビルド構成による確定的な非対応。** 切り分けの際は iso を除外すること。対応するには libVLC の再ビルド（`--enable-dvdnav` ＋ `HAVE_DVDNAV_DEMUX`）が必要で、かつネットワーク越しのシーク性能上おすすめしない。

## 当面の回避策とその注意点

**ユーザー名欄に `guest`、パスワード欄を空**にすると、MRL が `smb://guest:@host:445/...` になり、`guest` ＋ 空パスワードの NTLMv2 認証が送られる（Synology で実機確認済み）。

**ただしこの回避策は環境によって一覧を壊す。** `SmbContextBuilder` が「ユーザー名が空でなければ USER タイプ」としているため、`guest` と入力すると jcifs が GUEST タイプから USER タイプへ切り替わり、OpenMediaVault では一覧取得が `Logon failure: unknown user name or bad password` で失敗する（実機確認済み）。

README に案内を載せる場合は、**「それで直らない、または一覧が出なくなったら空欄に戻す」**ことを併記する必要がある。

## 対応方針（未決）

| 案 | 内容 | 評価 |
|---|---|---|
| **A** | `Dialog.setCallbacks` を登録し、`onDisplay(LoginDialog)` で `postLogin("guest", "", false)` を返す。libVLC 本来の「匿名で試す → 失敗したら資格情報を要求」の2段構えを機能させる | **匿名試行が必ず先に行われるため既存環境を壊さない。** `postLogin` の空文字列が NULL に化けないことも VLC core 側で確認済み。ただし **smb2 の DENIED ループ（`-EACCES` / `-ECONNREFUSED`）に入らなければ発火しない**（未確定）。また**ループに回数制限がないため試行回数ガードが必須**（無限ダイアログループでハングする） |
| **B** | ユーザー名が空のとき無条件に `smb://guest:@host/...` を組み立てる | **採用不可。** OpenMediaVault で再生が失敗することを実機で確認（ブランチ `fix/smb-guest-auth-1.2.0-beta`） |
| **B′** | アプリ側で2段リトライ（匿名 → 失敗したら `guest:@` で1回だけ再試行） | 確実だが実装が重い。**LibVLC 再生成時の既存バグ（旧インスタンスの release 漏れ、`AndroidView` の `factory` が1回しか呼ばれず `attachViews` が張り直されない）を先に直す必要がある** |
| **C** | 設定で接続方式を選ばせる（匿名 / `guest` を明示）。あわせて `SmbContextBuilder` で `guest` を GUEST タイプ扱いにし、一覧が壊れないようにする | 実装は軽く挙動も予測可能。ただし利用者が設定を切り替える必要がある。**Mac の「ゲストとして接続」相当の UI は項目4（UI 刷新）で扱う方針** |
| **D** | jcifs で読んだバイトを libVLC に `FileDescriptor` で渡し、libVLC の SMB 実装を迂回する | **根本解決。** libVLC Android 3.x に `MediaCallback` は無いが `Media(ILibVLC, FileDescriptor)` がある。`StorageManager.openProxyFileDescriptor()`（API 26+）でシーク可能な FD を作れる。既存の `SmbDataSource.kt`（jcifs の `SmbRandomAccessFile` を使用、現在は未使用）が流用できる。**実装と検証の規模が大きく、オーバーヘッドの実測が必要** |

**次の一歩**: ブランチ `fix/smb-diagnostics-1.2.0-beta` の診断ログで、両環境がサーバーから何を返されているかを確定させる。案A が発火するかはここで決まる。

## 診断の手段

**サーバーが返した失敗理由は、既定の設定では一切記録されない。**

- smb2 モジュールの実エラーは `vlc_smb2_check_status()` の **`msg_Warn`** で出力される。VLC の `verbose` 既定値は 0 で ERROR しか logcat へ流れないため見えない。**`-vv`（`--verbose=2`）が必要**
- `Dialog.ErrorMessage` 経由では**取れない**。`vlc_smb2_mainloop()` が終了時に必ずコンテキストを破棄して `sys->smb2 = NULL` にするため、`smb2_get_error(NULL)` が空文字列を返し `vlc_dialog_display_error` が呼ばれない（実機ログでも裏付け済み）
- dsm 側の `msg_Err` 群（`Unable to connect/negotiate SMB session` / `Unable to login` / `Unable to open file with path %s (in share %s)`）は ERROR レベルなので既定でも見える
- 利用者から取る必要が出た場合はファイルロガー（`--verbose=-1 --file-logging --logmode=text --log-verbose=2 --logfile=<filesDir>/vlc_smb.log`）が使える。オプション名の実在は確認済み

### ログの読み方（紛らわしい点）

`Unable to open file with path %s (in share %s)` は **ファイルオープンの失敗を意味しない**。`modules/access/dsm/access.c:239` で **`login()` が失敗したとき**に出るメッセージで、`login()` 内部の `smb_session_login` / `smb_tree_connect` / `smb_fopen` の**どれが失敗しても同じ文面**になる。

`Unable to connect/negotiate SMB session` が出ている場合は dsm の SMB1 セッション確立の失敗で、VLC のソースに `/* FIXME: libdsm wrongly return network error when the server can't handle the SMBv1 protocol */` とあるとおり、**サーバーが SMB1 を無効にしている**ことを示す（OpenMediaVault で確認）。

## 関連して見つかった実バグ（本件とは独立）

| 内容 | 影響 |
|---|---|
| **認証情報を URL に埋めているため、パスワードに `@` `/` `;` `?` が含まれると接続できない** | VLC が `vlc_uri_compose` → `vlc_uri_decode` で URL 全体をデコードするため `Uri.encode` が無効化され、libsmb2 がホスト名を誤って切り出す。`user@domain` / `DOMAIN\user` 形式のユーザー名も同様。**`:smb-user` / `:smb-pwd` の Media オプションでは代用できない**（`var_InheritString` が空文字列を NULL に落とすため、空パスワードを渡せない） |
| ユーザー名が空でパスワードだけ設定されている場合、**パスワードが捨てられる** | `smbUser.isNotBlank()` でしか認証情報を付けていない |
| **`--network-caching=3000` が効いていない** | `setHWDecoderEnabled()` が `:network-caching=1500` を注入してインスタンスオプションを上書きする。3000 にするには `setHWDecoderEnabled` の**前**に `addOption` が必要 |
| **サーバー登録の入力検証が実質ゼロ** | trim もフォーマット検査もなく、ポートが数値かも未チェック。`SmbViewModel.initSmb()` だけが `ip.trim()` しているため、空白混入時は照合が外れてゲスト扱いになる |
| **同一ホストを複数登録すると先に見つかった方の認証情報が使われる** | 照合がホスト一致のみ |
| **チャプタ取得が常にゲスト資格**になる | `LaunchedEffect(smbItem.path)` のキーに `currentServer` がなく、認証情報が後から届いても再実行されない |
| **録画が1件も同期されていない環境では SMB 再生が一切できない** | `MainRootScreen.kt:726-735` で `recentRecordings` が空だと早期 return（別メッセージが出るので今回の症状とは異なる） |
| LibVLC を作り直したとき**旧インスタンスが release されない**、`AndroidView` の `factory` が1回しか呼ばれないため**新しい MediaPlayer に `attachViews()` が張り直されない** | B′ を実装する前に直す必要がある |
| TREE_CONNECT の UNC に `:445` が混入する（`\\host:445\share`） | Samba は許容するが、Windows 共有や一部 NAS で `STATUS_BAD_NETWORK_NAME` になる余地 |

## 参考: 同梱している libVLC の構成

- バージョン **3.0.23 (Vetinari)**（厳密には `3.0.23-2-228-g...` で 3.0.x ブランチ）
- `app/libs/libvlc-lightweight-universal.aar`（自前ビルドの軽量版、約 40MB）。`793c461`（1.1.0-beta5）で導入され以後無変更
- `armeabi-v7a` / `arm64-v8a` の両方を同梱
- SMB は **libsmb2（SMB2/3、`smb2` モジュール、score 21）** と **libdsm（SMB1、`dsm` モジュール、score 22/20）** の両方をリンク済み
- `smb://` に対する試行順は dsm(22、`--smb-force-v1` 時のみ) → smb2(21) → dsm(20)。**smb2 が `-EIO` を返すと `VLC_ETIMEOUT` でプローブが打ち切られ、SMB1 フォールバックが試されない**
- dsm は **URL のポートを無視**して 445 固定で接続する
- 無効: `dvdnav`、`nfs`、`sftp`、`chromecast` ほか

## 調査の経緯

2026-11-07 に利用者から報告。推測で3つの仮説を立てていずれも外し、複数エージェントによる一次情報（VLC 3.0.x / libsmb2 / jcifs-ng のソース、aar の実物、Samba と Microsoft の公式ドキュメント）の照合で原因を確定させた。**仕様書のない外部ライブラリの挙動は、実装を読むまで推測で判断してはいけない**という教訓は [`docs/architecture/edcb_protocol.md`](../architecture/edcb_protocol.md) と同じ。

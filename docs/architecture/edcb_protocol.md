# EDCB 制御プロトコルの実装メモ

EDCB バックエンドは HTTP ではなく **EpgTimerSrv の独自 TCP バイナリプロトコル**で通信する（`data/api/edcb/`）。仕様書が公開されていないため、実装時に踏んだ落とし穴と、照合に使える一次資料をここに残す。

## 一次資料

迷ったらこの2つを読む。推測で実装しないこと。

| 資料 | 内容 |
|---|---|
| [xtne6f/EDCB](https://github.com/xtne6f/EDCB) `Common/CtrlCmdDef.h` | コマンド番号・`CMD_VER` の定義 |
| [xtne6f/EDCB](https://github.com/xtne6f/EDCB) `Common/SendCtrlCmd.h` | クライアント側の送受信実装（C++） |
| [tsukumijima/KonomiTV](https://github.com/tsukumijima/KonomiTV) `server/app/utils/edcb/CtrlCmdUtil.py` | **同じプロトコルの Python 実装。最も読みやすく、照合の基準にできる** |

特に `CtrlCmdUtil.py` は、コマンド番号・構造体のシリアライズ・成否判定がすべて1ファイルに揃っており、Komorebi の `EdcbApi` / `EdcbByteUtils` と1対1で突き合わせられる。

## 通信形式

送受信とも `[4バイト: コマンド番号 or エラーコード][4バイト: データ長][データ]`。

`CMD2_*`（2000番台）のコマンドは、データ部の**先頭に `CMD_VER`（2バイト）**を置く。データ長は `CMD_VER` を含んだ長さ。

```
送信: [4: cmd][4: size][2: CMD_VER][本体]      size = 2 + 本体長
受信: [4: ret][4: size][本体]
```

`CMD_VER` は **5**（Komorebi / KonomiTV とも同値。`CtrlCmdDef.h` の `#define CMD_VER 5` に対応）。

## 成否判定 — 最も間違えやすい箇所

**成否はレスポンスヘッダ先頭の `ret` だけで決まる。`CMD_SUCCESS == 1`。応答本体を成否コードとして読んではいけない。**

EDCB 本体も KonomiTV も `ret` のみを見て、応答本体はデータ読み出しが必要なコマンドでのみ使う。

```python
# KonomiTV: 書き込み系は応答本体を _ で捨てている
ret, _ = await self.__sendCmd2(self.__CMD_EPG_SRV_ADD_AUTO_ADD2, ...)
return ret == self.__CMD_SUCCESS
```

Komorebi では `EdcbTcpClient.sendCommand()` が `ret != 1` のときに `null` を返すため、**非 null であれば成功**。それ以上は読まない。

### 過去の不具合（2026-09-29 修正）

書き込み系6コマンド（`1014` 予約削除 / `1033` 自動予約条件削除 / `2013` 予約追加 / `2015` 予約変更 / `2132` 自動予約条件追加 / `2134` 自動予約条件変更）が、`sendCommand` の戻り値に加えて**応答本体の先頭4バイトを成否コードとして読んでいた**。

`EdcbByteUtils.readInt()` は `remaining() < 4` のとき 0 を返す実装のため、判定は常に不一致となり、**EDCB 側では正常に処理されているのにアプリだけが失敗と報告する**状態だった。EDCB バックエンドでの予約・自動予約の追加/変更/削除がすべてこの状態にあった。

応答本体にはコマンドによって `CMD_VER` や EDCB 内部のデータが入っており、**先頭4バイトを成否コードとして読める形式ではない**。

## 構造体のシリアライズ

可変長リストは `[4バイト: 全体サイズ][4バイト: 要素数][要素...]`。構造体も先頭4バイトに自身の全体サイズを置く。

`EPG_SEARCH_KEY_INFO`（検索条件）で特に注意が要る点:

- **andKey の特殊プレフィックス**は `^!{999}`（条件無効） → `C!{999}`（大文字小文字区別） → `D!{1########}`（番組長絞り込み）の順で前置する
- **`chk_rec_no_service` は独立したフィールドではなく** `chk_rec_day` に埋め込む。`chk_rec_no_service` が真なら `chk_rec_day % 10000 + 40000` を書く
- `chk_rec_end` / `chk_rec_day` は `CMD_VER 3` 以降のフィールドで、`CMD2_*` 系（`SearchKeyInfo2`）でのみ書き込む

`AutoAddData` は `[4: サイズ][4: data_id][SearchKeyInfo2][RecSettingData][4: add_count]`。

## 「全チャンネル対象」の表現

EDCB の自動予約条件は**対象サービスを明示的に列挙する**。空のリストは「全チャンネル」ではなく「対象なし」であり、EpgTimer 側も条件追加時は全チャンネルを選択した状態から始まる。

Komorebi では `ProgramSearchCondition.serviceRanges` が `null` のとき、`EdcbDataMapper.encodeSearchKeyInfo()` が EPG キャッシュ上の全サービスへ展開する。番組表を一度も開いていない等でキャッシュが空だと展開先が無くなるため、`EdcbReserveRepository` 側で空を検知して利用者に EPG 取得を促すようにしてある。

なお KonomiTV の API では `service_ranges: null` が仕様として「全チャンネル」を意味する（`server/app/schemas.py` に明記）。**同じ `null` がバックエンドによって意味が違う**点に注意。

## KonomiTV 側のスキーマ制約

KonomiTV の `ProgramSearchCondition` には `Literal` で値が固定されたフィールドがあり、範囲外の値は **HTTP 422** で拒否される。EDCB 側は `== "FreeOnly"` のような単純比較のため未知の値でも既定動作に落ち、**エラーにならず気づきにくい**。

| フィールド | 取りうる値 |
|---|---|
| `broadcast_type` | `All` / `FreeOnly` / `PaidOnly`（放送波の種別ではなく「すべて / 無料のみ / 有料のみ」） |
| `duplicate_title_check_scope` | `None` / `SameChannelOnly` / `AllChannels` |

2026-09-29 まで、AI コンシェルジュのキーワード自動予約が `broadcast_type` に `"GR,BS,BS4K,CS,SKY"` を、`duplicate_title_check_scope` に `"SameTitle"` を渡しており、KonomiTV では 422 になっていた。

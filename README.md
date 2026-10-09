# server_status

サーバーの電源・バッテリー・CPU・メモリ・ディスクの状態を JSON で返す API です。
Java で値を集め、CGI から呼び出します。

## 使い方

```sh
javac ServerStatus.java
```

コンパイルした `ServerStatus.class` を `index.cgi` と同じフォルダに置き、CGI を実行できるようにします。

```json
{
  "power": "AC",
  "battery": null,
  "status": null,
  "remaining_hours": null,
  "cpu_percent": 12.5,
  "memory_percent": 43.2,
  "memory_used_gb": 3.4,
  "memory_total_gb": 7.8,
  "disk_percent": 61.0,
  "disk_used_gb": 140.2,
  "disk_total_gb": 229.8
}
```

- Linux の `/sys/class/power_supply/` から電源とバッテリーを読みます。無い環境(Docker など)では AC 電源として扱います
- 取得できない項目は `null` になり、処理は止まりません

## ライセンス

[MIT License](LICENSE) © 2026 ISAMI ABE

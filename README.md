# android-supply-chain-lab-lib

[android-supply-chain-lab](https://github.com/appachan/android-supply-chain-lab) のテーマ 4 の検証用のライブラリ。GitHub Packages の Maven で配信する。public のパッケージだが、取得には token が要る。

判断と検証結果は [ADR-0001](docs/adr/0001-private-library-distribution.md) にまとめている。

## リリース

`v<version>` のタグは ruleset で admin だけが作れる。タグを push すると、`.github/workflows/publish.yml` の `publish` ジョブが environment `release` の承認を待つ。承認の後に AAR に署名して公開する。続けて、シークレットのない別のジョブがタグからビルドし直し、公開された成果物とバイト単位で比較する。比較に使った SHA-256 はこの実行の job summary に残る。比較を通ると `attest` ジョブが artifact attestations を付ける。

署名の公開鍵は `docs/signing/release-key.pub.asc`、fingerprint は `6BE556226D4323DB5C972F74667E34342E2C6F66` である。`0.5.0` からこの鍵で署名する。`0.4.0` までの鍵の fingerprint は `8493022D3CF93D42B2DC1C80AD8EDA7ABCFDFAE6` である。どちらも検証用の鍵である。

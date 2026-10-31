# android-supply-chain-lab-lib

[android-supply-chain-lab](https://github.com/appachan/android-supply-chain-lab) のテーマ 4 の検証用の private ライブラリ。GitHub Packages の Maven で配信する。

判断と検証結果は [ADR-0001](docs/adr/0001-private-library-distribution.md) にまとめている。

## リリース

`v<version>` のタグを push すると、`.github/workflows/publish.yml` が AAR に署名して公開する。

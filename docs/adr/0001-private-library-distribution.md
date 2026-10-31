# 1. Private library distribution

Date: 2026-10-31

## Status

ライブラリ側の運用は Accepted。利用側の運用は Proposed で、適用はユーザーが判断する。lab と rss-reader は変更していない。

## Context

android-supply-chain-lab のテーマ 4 である。自作の Android ライブラリを private リポジトリから GitHub Packages の Maven で配信し、利用側が「正しいソースから正しい手順で作られた成果物だ」と確かめる方法を決める。

前提は lab の ADR-0001、ADR-0002、ADR-0003 と同じである。

- 依存の検証は `pgp,sha256`、keyring は armored、`<key-servers enabled="false">`
- 依存のバージョンは `gradle.lockfile` と `failOnNonReproducibleResolution()` で管理する
- Dependabot の更新を前提にする
- SNAPSHOT は使わない

このリポジトリの構成は Gradle 9.7.1、Android Gradle plugin 9.3.3、Kotlin 2.4.21、JDK 17 で、lab と同じである。

このアカウントは個人のアカウントで、組織には所属していない。`gh api user` の `plan` は null でプランを読めなかった。`GET /repos/.../rulesets` が `Upgrade to GitHub Pro or make this repository public to enable this feature.` を返すので、GitHub Pro より下のプランだと判断した。

## Decision

ライブラリ側:

- `v*` のタグの push で公開する。`GITHUB_TOKEN` の `packages: write` で公開し、PGP の署名を `.asc` として一緒に公開する
- 公開の後に、シークレットのない別のジョブがタグからビルドし直し、公開された 4 ファイル（AAR、sources、POM、`.module`）とバイト単位で比較する。比較に使った SHA-256 を job summary に残す
- `org.gradlex.reproducible-builds` は使わない
- 一度公開したバージョンは削除しない。直すときは新しいバージョンを出す。レジストリはこれを保証しないので、運用の規則である
- artifact attestations は使えないので使わない。SLSA の generator も使わない

利用側（推奨。適用はユーザーの判断）:

- `exclusiveContent` と `includeModule` で、この module の取得先を GitHub Packages だけにする
- `verification-metadata.xml` で、ライブラリの鍵の `trusted-key` をバージョン付きで持ち、さらに同じバージョンの `.aar` と `.module` の `sha256` を持つ。Gradle の再生成は新しいバージョンの `sha256` を書かないので、この行は人かワークフローが足す。値はレジストリからではなく、ライブラリ側のリビルドの job summary から取る
- `gradle.lockfile` で更新を差分に出す
- 読み取りの認証は、`Manage Actions access` が Maven のパッケージで使えるかをユーザーが確認してから決める。使えなければ classic PAT の `read:packages` を使う
- 鍵の漏えいのときは、`trusted-key` を消して `ignored-key` に移す

判断の中心は次の実験結果である。信頼する鍵で署名された差し替えは、鍵の信頼だけでは検知できない。

| 利用側の metadata | 署名を保ったままバージョンを差し替える | 攻撃者の鍵で署名する | 中身だけ改ざんする |
| --- | --- | --- | --- |
| 鍵の信頼だけ | 検知できない | 検知 | 検知 |
| ハッシュだけ | 検知 | 検知 | 検知 |
| 鍵の信頼とハッシュ（採用） | 検知 | 検知 | 検知 |

ハッシュだけの方式は既存のバージョンの差し替えを検知するが、新しいバージョンの最初の記録では誰が署名したかを確かめない。鍵の信頼はこの最初の記録を守る。役割が違うので両方持つ。

## 論点 1: 配信

### 認証

実行は [run 41205219271](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41205219271) と手元である。

| 条件 | 結果 |
| --- | --- |
| 認証なしで `maven-metadata.xml` を取得 | 401 |
| 誤った認証情報 | 401 |
| `GITHUB_TOKEN`（`packages: read`、同じリポジトリのワークフロー） | 200 |
| 手元の `gh` の token（`repo`、`read:org` などで `read:packages` なし）で同じ URL | 401 |
| 同じ token で `GET /users/appachan/packages?package_type=maven` | 403、`You need at least read:packages scope to list packages.` |
| `GITHUB_TOKEN` で存在しない module | 404 |
| `GITHUB_TOKEN` で Maven Central にしかない `junit:junit:4.13.2` | 404。レジストリは Maven Central を中継しない |
| `GITHUB_TOKEN` で packages の REST API を読む | 成功 |

`repo` スコープは `read:packages` の代わりにならない。[公式](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages)のスコープの説明と一致する。

公開は `GITHUB_TOKEN` の `packages: write` で成功した（[run 41204952581](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41204952581)）。[公式](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)は `GITHUB_TOKEN` to publish packages associated with the workflow repository と書いている。

### 上書き

同じバージョンは上書きできない。

| 操作 | 結果 | 実行 |
| --- | --- | --- |
| `0.1.0` を別の中身で公開 | 失敗。`Received status code 409 from server: Conflict`。AAR は変わらない | [run 41209709087](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41209709087)、[run 41206741255](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41206741255) |
| `0.1.0` を同じ中身で公開 | 失敗（409）。同じ中身でも再実行はできない | run 41206741255 |

### 削除と再公開

削除すれば同じバージョンを別の中身で出し直せる。手順と結果は [run 41206741255](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41206741255) である。テスト用の `0.0.1` で確かめた。

```sh
gh api -X DELETE "/users/appachan/packages/maven/com.github.appachan.android-supply-chain-lab-lib/versions/$ID"
```

| 順序 | 結果 |
| --- | --- |
| `0.0.1` を公開 | 成功。AAR は `fc6f0cfd2985e05e`、version id は 70350655 |
| `GITHUB_TOKEN`（`packages: write`）で削除 | 204。AAR の取得は 404 |
| 別の中身を `0.0.1` として公開 | 成功。AAR は `f60cad98d17ddfda`、version id は 70350663 |

[公式の手順](https://docs.github.com/en/packages/learn-github-packages/deleting-and-restoring-a-package)が挙げるのは `delete:packages` を持つ classic PAT だが、Actions の `GITHUB_TOKEN` の `packages: write` だけで削除できた。ワークフローを実行できる人は、人の削除の操作なしで公開済みのバージョンを差し替えられる。残る痕跡は version id と `created_at` の変化だけで、履歴は見えない。

利用側の検知は実際の GitHub Packages で確かめた（[run 41208394369](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41208394369) の `replaced` のジョブ。このジョブは成功し、同じ実行の別のジョブが後述の理由で失敗している）。

| 利用側の metadata | 差し替えられた `0.0.1` |
| --- | --- |
| 鍵の信頼だけ | `BUILD SUCCESSFUL`。差し替えを検知できない |
| 鍵の信頼と、差し替え前の `sha256` | `2 artifacts failed verification`（AAR と `.module`） |

同じ実行で、ライブラリ側のリビルドの比較も差し替えを検知した。公開されている AAR は `f60cad98...`、リビルドは `fc6f0cfd...` である。

`gradle.lockfile` はバージョンしか持たないので、この差し替えを検知しない。テスト用の `0.0.1` は確認の後に削除した。残っているバージョンは `0.1.0` と `0.2.0` である。

## 論点 2: 成果物の完全性

### .asc の公開と取得

Gradle の signing plugin で署名し、GitHub Packages で公開と取得ができた。4 種類の成果物それぞれに `.asc`、`.md5`、`.sha1`、`.sha256`、`.sha512` があり、すべて 200 で取得できた（[run 41205219271](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41205219271)）。取得した 4 件の `.asc` は、手元で公開鍵 `8493022D3CF93D42B2DC1C80AD8EDA7ABCFDFAE6` により検証できた。

`gpg` が手元にないので、鍵の生成と検証用の署名は Gradle 9.7.1 が同梱する BouncyCastle で行った。鍵は検証用の RSA 3072 bit で、公開鍵を `docs/signing/release-key.pub.asc` に置いた。ローテーションと改ざんの確認に使った他の鍵と、すべての秘密鍵のファイルは終わった後に消した。`SIGNING_KEY` と `SIGNING_PASSWORD` の Actions シークレットは公開のために残している。

### 利用側の検知の比較

手元の最小の利用側プロジェクトで、Python の HTTP サーバーを偽の GitHub Packages にして実行した。実験ごとに `GRADLE_USER_HOME` を空にした。Gradle 9.7.1、JDK 17、検証の strict モードである。

| 配信されるもの | ハッシュだけ | 鍵の信頼だけ | 鍵の信頼とハッシュ |
| --- | --- | --- | --- |
| 本物（対照） | 成功 | 成功 | 成功 |
| AAR に 1 byte 追加、`.asc` はそのまま | 失敗 | 失敗 | 失敗 |
| AAR に 1 byte 追加、AAR の `.asc` を削除 | 失敗 | 失敗 | 失敗 |
| 本物から全ての `.asc` を削除 | 成功 | 失敗 | 成功 |
| 中身を差し替えて同じ鍵で署名 | 失敗 | 成功（検知できない） | 失敗 |
| 中身を差し替えて攻撃者の鍵で署名 | 失敗 | 失敗 | 失敗 |

- ハッシュだけのモードは、鍵が keyring になく鍵サーバーにもない状態で再生成して作った。`<ignored-key id="AD8EDA7ABCFDFAE6" reason="Key couldn't be downloaded from any key server"/>` と `sha256` が書かれた
- 鍵の信頼とハッシュは、鍵の信頼のモードに手で `sha256` を足して作った。署名が有効でも `sha256` は検査される
- `.asc` を消しても、ハッシュが一致するなら中身は同じなので、成功は問題にならない

ハッシュの出所が大事である。レジストリの `.sha256` は成果物と同じ場所にあるので、差し替えと一緒に変わる。独立の値としてリビルドの job summary を使う。

### 鍵の信頼の範囲

再生成が書く `trusted-key` はバージョンを含む。ライブラリの新しいバージョンのたびに `trusting` の行が増えるので、PR の差分に出る。

```xml
<trusted-key id="8493022D3CF93D42B2DC1C80AD8EDA7ABCFDFAE6">
   <trusting group="com.github.appachan" name="android-supply-chain-lab-lib" version="0.1.0"/>
   <trusting group="com.github.appachan" name="android-supply-chain-lab-lib" version="0.2.0"/>
</trusted-key>
```

`version` を消すと信頼が広がり、新しいバージョンが差分なしで通る。消さない。

### 鍵の保管

検証用の鍵は Actions シークレットに 1 つ置いた。運用の鍵では次のようにする。ここは実行していない。

- 主鍵はオフラインに置き、署名用の副鍵だけを Actions シークレットに入れる。副鍵を持つ鍵を Gradle の `trusted-key` でどう扱うかは未確認である
- 公開の workflow を変更できる人は `SIGNING_KEY` を使える。ブランチの保護と environment で絞るのが一般的だが、このプランの private リポジトリでは rulesets を使えない。environment の保護の規則が使えるかは確認していない
- `publish.yml` は `--no-configuration-cache` で実行し、署名鍵が configuration cache のエントリに入らないようにする

`publish.yml` の公開のジョブは鍵を持つが、リビルドのジョブは鍵を持たない。鍵を持つジョブに不正なコードが混ざった場合でも、タグのソースからの結果と一致しなければ失敗する。

### ローテーション

手元で確かめた。鍵 A で `0.1.0`、鍵 B で `0.2.0` を署名した。

| 順序 | 結果 |
| --- | --- |
| 鍵 B が keyring になく更新する | `Key servers are disabled` で失敗する |
| その状態で再生成する | 成功する。鍵サーバーに鍵がないので `ignored-key` と `sha256` に落ちる。`verify` は成功し、検査のスクリプトの `Ignored keys` に出る |
| 鍵 B を keyring に足して再生成する | `<trusted-key id="21BC7574...1F1F" group="..." name="..." version="0.2.0"/>` が追加される |
| ローテーションの後に `0.1.0` を解決する | 成功。鍵 A の信頼は `0.1.0` に残る |

運用の手順は次のとおりである。

1. ライブラリ側が新しい鍵で最初のバージョンを出す前に、利用側の keyring に公開鍵を足す PR を人が作る。fingerprint は別の経路で確認する
2. 利用側の再生成の結果で、新しい鍵の信頼が新しいバージョンに限られることを確認する

順序が逆だと `ignored-key` になり、署名を確かめないまま受け入れることになる。

### 失効

Gradle は鍵の失効と期限切れを見ない。この点は公式の説明に見当たらず、実験と実装を根拠にする。

| 条件 | 結果 |
| --- | --- |
| keyring の鍵 A を失効の署名付きに置き換える（BouncyCastle で `hasRevocation=true` を確認） | 成功 |
| 同じ keyring で、失効の後に鍵 A で署名する | 成功 |
| 有効期限 1 秒の鍵で、期限の後に署名して信頼する（`validSeconds=1` を確認） | 成功 |

[`DefaultSignatureVerificationServiceFactory`](https://github.com/gradle/gradle/blob/v9.7.1/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/verification/signatures/DefaultSignatureVerificationServiceFactory.java) の検証は、署名の照合と fingerprint の信頼の判定だけで、失効と期限の判定がない。

失効の意味は、利用側の metadata を変更して初めて生じる。`<key-servers enabled="false">` なので、鍵サーバーで失効しても `verify` には届かない。鍵が漏えいしたときの手順を確かめた結果は次のとおりである。

| 利用側の変更（ハッシュあり） | 本物の `0.1.0` | 同じ鍵で署名した差し替え |
| --- | --- | --- |
| `trusted-key` を消す。鍵は keyring に残す | 失敗 | 実行していない |
| `trusted-key` を消し、鍵も keyring から消す | 失敗 | 実行していない |
| `trusted-key` を消し、`ignored-keys` に fingerprint を足す | 成功 | 失敗 |
| 鍵の信頼だけで `trusted-key` を消す | 失敗 | 実行していない |

漏えいの手順は次のとおりである。ハッシュがないと過去のバージョンが取得できなくなるので、この点でもハッシュを持つ意味がある。

1. `SIGNING_KEY` を新しい鍵に変える
2. 利用側で `trusted-key` を消し、同じ fingerprint を `ignored-key` に足す。過去のバージョンは `sha256` で検証される
3. 新しい鍵で修正のバージョンを出す。ローテーションの手順に従う
4. `ignored-key` の `reason` に理由を書く

## 論点 3: provenance

### GitHub の artifact attestations

使えない。

[公式](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations)の記述は `To use artifact attestations in private or internal repositories, you must be on a GitHub Enterprise Cloud plan.` である。

実行の結果も同じで、`actions/attest-build-provenance` v4.2.2 は次のエラーで失敗した（[run 41205219271](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41205219271) の `attest` のジョブ）。

```text
Failed to persist attestation: Feature not available for user-owned private repositories. To enable this feature, please make this repository public.
```

Enterprise Cloud への変更と、リポジトリの public への変更は、今回の制約の外である。したがって `publish.yml` に attestation の step はない。

### 代案の比較

| 案 | 判定 | 理由 |
| --- | --- | --- |
| artifact attestations | 不可 | 上記。public にするか Enterprise Cloud にすれば使える |
| [SLSA の generic generator](https://github.com/slsa-framework/slsa-github-generator/blob/main/internal/builders/generic/README.md) | 不採用（実行していない） | 下記 |
| タグからのリビルドの比較 | 採用 | シークレットなしで動き、論点 4 の結果で成立する |
| 同じ鍵で provenance の JSON に署名する | 不採用（実行していない） | 鍵を持つジョブが自己申告するだけで、`.asc` の署名を超える情報がない |

SLSA の generator を実行しなかった理由は次のとおりである。

- README に `Currently all builds generate and post a new entry in the public` Rekor instance とあり、private リポジトリの名前が公開の Rekor に永続的に残る。`private-repository: true` はこれを受け入れるフラグである。取り消せないので、許可の範囲を超えると判断した
- 同じ README が `This project is no longer actively maintained.` と書き、新しい利用に artifact attestations を勧めている
- 再利用可能なワークフローは `@vX.Y.Z` のタグの参照が必須で、commit SHA で固定できない。lab の Action の固定の方針と合わない

### 採用した検証と、検証する人

リビルドの比較が示すのは、タグのソースから同じ成果物が作れることである。リビルドのジョブも同じリポジトリの定義で動くので、ワークフローの改ざんには耐えない。artifact attestations の代わりに、GitHub が署名する証明は得られない。

| 工程 | 誰が | 何を |
| --- | --- | --- |
| タグの公開 | `publish.yml` の `verify-reproducible` | 公開された 4 ファイルとリビルドを比較する |
| ライブラリの更新の PR | PR を確認する人 | job summary の SHA-256 を利用側の metadata に記録し、`trusting` の差分がバージョンの追加だけであることを確認する |
| すべてのビルド | Gradle | 記録した鍵とハッシュを検証する |

public の利用側の CI と Dependabot は private のソースを読めないので、リビルドは利用側では行わない。

将来 artifact attestations が使えるようになったら、ライブラリの更新の PR を確認する人が、更新前に次を実行する。public の利用側の CI には private のリポジトリの attestation を読む権限を足さない。

```sh
gh attestation verify android-supply-chain-lab-lib-<version>.aar --repo appachan/android-supply-chain-lab-lib
```

## 論点 4: 再現可能なビルド

AAR はバイト単位で一致した。差分はないので `org.gradlex.reproducible-builds` は要らない。

手元では `--no-build-cache --no-configuration-cache` で build ディレクトリを消して実行した。Actions は Linux の別の VM である。

| 条件 | AAR | sources | POM | `.module` |
| --- | --- | --- | --- | --- |
| 手元、JDK 17、UTC | `fc6f0cfd` | `8972ca6b` | `dcca5971` | `72beb227` |
| 同じ条件でもう 1 回 | 同じ | 同じ | 同じ | 同じ |
| 別のパスの clone | 同じ | 同じ | 同じ | 同じ |
| `TZ=Asia/Tokyo` | 同じ | 同じ | 同じ | 同じ |
| JBR 21 | 同じ | 同じ | 同じ | 同じ |
| GitHub Packages の `0.1.0`（Linux の Actions でビルド、JDK 17） | 同じ | 同じ | 同じ | 同じ |

`.asc` は作成時刻を含むので比較の対象にしない。`0.2.0` の公開でも `verify-reproducible` が成功した（[run 41207904384](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41207904384)）。

AAR の中のエントリの時刻は `1980-02-01 00:00`、`classes.jar` の中は `1981-01-01 01:01` に固定されていて、ビルドの時刻は入らない。

この結論の範囲は、このライブラリの構成である。Kotlin のソースだけで、リソース、NDK、コード生成、`BuildConfig` がない。これらを足すときは `verify-reproducible` が失敗して気づける。失敗したら原因を調べ、そのときに `org.gradlex.reproducible-builds` を見直す。追加の plugin は検証の対象を増やすので、必要になるまで足さない。

## 論点 5: 利用側の検証

lab の main（f737f94）の clone に次の変更を入れた。lab には push していない。

- `settings.gradle.kts` に `exclusiveContent`（論点 8）
- catalog に `com.github.appachan:android-supply-chain-lab-lib:0.1.0`
- `:shared:ui` に `implementation` とライブラリを呼ぶ 1 ファイル
- keyring に公開鍵を追加

手元で空の環境の再生成を 2 回実行した。偽の GitHub Packages に実物の `0.1.0` を置いた。

| 項目 | 結果 |
| --- | --- |
| `verification-metadata.xml` | +1 行。`trusted-key` の 1 行だけ |
| `gradle.lockfile` | `:app`、`:feature:greeting`、`:shared:ui` が各 +1 行 |
| `verification-keyring.keys` | +15 行 |
| 別の環境の検証の strict モードの `build` | 成功、265 タスク |

手元の別の環境は、前の daemon のファイルが一部残っていて完全に空ではなかった。完全に空の環境の確認は次の Actions の実行である。

同じ差分を public の lab の checkout に適用し、実際の GitHub Packages から取得した（[run 41208728219](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41208728219)）。このリポジトリの `GITHUB_TOKEN` を使ったので、同じリポジトリのパッケージを読む場合の確認である。

| 操作 | 結果 |
| --- | --- |
| 空の環境で検証の strict モードの `build` | 成功。265 タスク。`.aar`、`.module`、`.pom` と `.asc` を取得した |
| token なしで依存を解決 | 失敗。`Could not HEAD ...`、401 |
| catalog を `0.2.0` に更新して `build` | 失敗。`One artifact failed verification: android-supply-chain-lab-lib-0.2.0.module` |
| 再生成を 2 回 | 成功 |
| キャッシュを消して検証の strict モードの `build` | 成功 |

新しいバージョンの公開で起きることは次のとおりである。

- 最小の利用側プロジェクトでは `Cannot find a version` で lock が止め、`--write-locks` だけでは検証が止めた。どちらかが必ず止まる。公開だけでは利用側は何も変わらない
- `gradle.lockfile` は module ごとに 1 行変わる
- `verification-metadata.xml` は `trusting` の行が増える。ハッシュだけのモードなら `component` の行が増える
- lab の `review_verification_metadata.py` は `Trust granted to keys` に新しいバージョンを出す

この実行の再生成は、更新前のビルドが残る環境で実行したので、`guava-parent` などの POM の行が 6 件混ざった。ADR-0002 が書く、検証のビルドに使った環境で再生成すると不要な行が増える現象と同じ形である。`0.1.0` を手元の空の環境で再生成したときはこの行は出ていない。ADR-0002 のとおり空の環境で実行する。

鍵の信頼とハッシュの運用の確認（手元）:

| 確認 | 結果 |
| --- | --- |
| 手で足した `sha256` が再生成で残るか | 残る |
| 再生成が新しいバージョンの `sha256` を書くか | 書かない。`trusting` の行だけが増える |
| `0.2.0` の `sha256` を手で足して検査のスクリプトを実行 | exit 0。`Artifacts accepted by hash or pinned key` に 2 件出る。`[group has trusted keys: check for a dropped signature]` が付くが、この場合は署名が残っているので誤検知である |
| 本物の `0.2.0` | 成功 |
| AAR を差し替えて同じ鍵で署名し直した `0.2.0` | `One artifact failed verification` で失敗 |

## 論点 6: Dependabot

実際の動作は未確認である。以下は公式の情報と dependabot-core の実装の読み取りで、Dependabot を動かしていない。

Dependabot が GitHub Packages の private ライブラリを読む方法は 2 つある。

1. 自動の認証。[2026-09-08 の changelog](https://github.blog/changelog/2026-09-08-automatic-dependabot-access-to-github-hosted-registries) によれば、Dependabot の job は `*.pkg.github.com` と `ghcr.io` に `GITHUB_TOKEN` を送り、`Manage Actions access` で利用側のリポジトリに Read を与えたパッケージが受け入れる。`dependabot.yml` の `registries` は要らない。[公式](https://docs.github.com/en/code-security/dependabot/working-with-dependabot/configuring-access-to-private-registries-for-dependabot)も同じ説明で、Dependabot が対応する全ての GitHub Packages のエコシステムが対象だと書く。Maven は名指しされていない。この機能は 2026-06-23 に一度取り消され、再度有効になった
2. `registries` の設定。[公式の例](https://docs.github.com/en/code-security/how-tos/secure-your-supply-chain/manage-your-dependency-security/guidance-for-the-configuration-of-private-registries-for-dependabot)は GitHub の個人のアカウントなら personal access token を password に使えると書く。スコープの記載はない。論点 1 の結果から `read:packages` が要る

```yaml
registries:
  supply-chain-lab-lib:
    type: maven-repository
    url: https://maven.pkg.github.com/appachan/android-supply-chain-lab-lib
    username: appachan
    password: ${{secrets.SUPPLY_CHAIN_LAB_LIB_TOKEN}}

updates:
  - package-ecosystem: gradle
    directory: /
    registries:
      - supply-chain-lab-lib
```

`replaces-base` は書かない。公式の例は `true` だが、このレジストリは 1 つのライブラリしか持たず、論点 1 のとおり Maven Central の成果物を返さない。実際の動作は確認していない。

シークレットは Dependabot の secret store に入れる。ADR-0002 のとおり、Dependabot の PR の `pull_request` は Dependabot のシークレットを使うので、CI にも同じ値が要る。

`exclusiveContent` の URL は文字列のリテラルで書く。[`RepositoriesFinder`](https://github.com/dependabot/dependabot-core/blob/85379652e4ef784fdd38bf4598b0b347832f805b/gradle/lib/dependabot/gradle/file_parser/repositories_finder.rb) の正規表現を手元で実行した結果は次のとおりである。この実装は `settings.gradle.kts` の `dependencyResolutionManagement` も読む。

| 書き方 | 拾われる URL |
| --- | --- |
| `url = uri("https://maven.pkg.github.com/...")` | 正しい URL |
| `url = uri(providers.gradleProperty("libRepositoryUrl").get())` | `libRepositoryUrl`（誤り） |

ライブラリの更新が PR になっても、Dependabot は metadata を更新しない。ADR-0002 の再生成のワークフローが要る。ただし再生成のジョブには問題が 2 つある。

- 新しいライブラリの `sha256` を足す処理が要る（論点 5）
- 再生成のジョブは検証なしで依存のコードを実行するので、そこに読み取りの token を渡すと、依存のコードから読める。ADR-0002 が `Regenerate` にシークレットを渡さないと決めた理由と衝突する

2 つ目の対処の候補は、token のある step でライブラリを取得してファイルのリポジトリに置き、token のない step で Gradle を実行することである。手元の偽のリポジトリの実験は同じ形だが、ワークフローとしては確認していない。

## 論点 7: 認証の選択肢

public の利用側のリポジトリの CI と Dependabot が読む方法の比較である。PAT は作っていない。別のリポジトリから読む実験はできていないので、以下は公式の情報と論点 1 の結果による。

| 方法 | 権限の範囲 | 個人への紐づき | 失効の手間 | 確認の状態 |
| --- | --- | --- | --- | --- |
| `Manage Actions access` と `GITHUB_TOKEN` | パッケージごとに利用側のリポジトリを指定 | なし | パッケージの設定から外す | Maven で使えるか不明 |
| classic PAT の `read:packages` | その人が読める全てのパッケージ。fine-grained PAT は使えない | 個人に紐づく | 手で失効し、Actions と Dependabot の 2 か所の secret を更新する。有効期限のたびに同じ | [公式](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)が示す方法。未実行 |
| 機械用のアカウントの classic PAT | そのアカウントに read を与えたリポジトリのパッケージだけ | 機械用のアカウント | PAT と同じ。アカウントの管理が増える | 未実行 |
| GitHub App の installation token | 不明 | なし | 自動で短期 | 未確認。下記 |

`Manage Actions access` については公式の記述が食い違う。

- [About permissions for GitHub Packages](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages) は Apache Maven registry を repository-scoped permissions だけの registry に挙げる。[Configuring a package's access control and visibility](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility) は、その種類のパッケージは常に linked repository の権限を継承すると書く
- 一方、[Maven registry のページ](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)は、`GITHUB_TOKEN` can be used if the repository is granted read access to the package と書く

2023 年の [community discussion](https://github.com/orgs/community/discussions/57603) には、`GITHUB_TOKEN` では同じ組織の別のリポジトリの Maven パッケージを読めないという報告がある。現在の状態は確認できていない。

GitHub App の token については、[公式](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages)が `GitHub Packages only supports authentication using a personal access token (classic).` と書く一方で、同じページが `GITHUB_TOKEN` を認めている。`GITHUB_TOKEN` も GitHub App の installation access token である。[github/docs の issue 9547](https://github.com/github/docs/issues/9547) は installation token が Maven で使えないという利用者の報告で、2022 年に閉じられている。公式の回答は確認していない。

推奨は次の順序である。

1. ユーザーが GitHub Packages のパッケージの設定を開き、`Manage Actions access` があるかを確認する。あれば lab に Read を与えて実際の CI で確かめる。シークレットが増えず、最も狭い
2. なければ、classic PAT の `read:packages` を最も短い運用可能な有効期限で作り、Actions と Dependabot の secret に入れる。漏えいの影響は読み取りだけだが、そのアカウントが読める全ての private パッケージに及ぶ。ライブラリのパッケージが増えるなら機械用のアカウントに移る

public のリポジトリの利用側には、認証の方法によらず次の影響がある。

- fork の PR にはシークレットが渡らないので、依存の解決で失敗する。手元の確認では `credentials` の値がないと `Username must not be null!`、Actions の確認では 401 である
- シークレットのない第三者はリポジトリをビルドできない

## 論点 8: dependency confusion

最小の利用側プロジェクトで、偽の Central に同じ group、artifact、バージョンの別物（`8be1e444...`）を置いた。本物の AAR は `fc6f0cfd...` である。どちらを取得したかを見るために `--dependency-verification lenient` で実行した。

| 設定 | GitHub Packages の状態 | 取得した AAR | 偽の Central への module の要求 |
| --- | --- | --- | --- |
| フィルターなし、偽の Central が先 | あり | 偽物 | 5 回 |
| フィルターなし、GitHub Packages が先 | あり | 本物 | 0 回 |
| フィルターなし、GitHub Packages が先 | バージョンなし | 偽物 | 5 回 |
| GitHub Packages の `content { includeModule }` だけ | バージョンなし | 偽物 | 5 回 |
| `exclusiveContent`、偽の Central が先 | バージョンなし | 取得できず失敗（`Could not find`） | 0 回 |
| `exclusiveContent`、偽の Central が先 | あり | 本物 | 0 回 |
| フィルターなし、GitHub Packages が 401（偽のサーバー） | | 偽物 | 5 回 |

- `content { includeModule }` はそのリポジトリの中の絞り込みで、他のリポジトリから取得することを止めない。[公式](https://docs.gradle.org/9.7.1/userguide/filtering_repository_content.html)の説明のとおり、止めるのは `exclusiveContent` である
- 401 でも次のリポジトリに進む。token の期限切れは攻撃者の成果物の取得につながる。[`RepositoryChainComponentMetaDataResolver`](https://github.com/gradle/gradle/blob/v9.7.1/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/ivyservice/ivyresolve/RepositoryChainComponentMetaDataResolver.java) は他のリポジトリで解決できると失敗を `Discarding resolve failure.` として捨てる
- 実際の GitHub Packages の 401（[run 41208394369](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41208394369)）では `Could not GET` で失敗した。Maven Central にこの module がないので解決先がなかったためで、上の結果と矛盾しない
- 検証の strict モードでは、フィルターなしで偽物を取得しても、鍵の信頼の metadata で失敗する（バージョンなしの場合で確認）。`exclusiveContent` と metadata は別の層で、取得の要求そのものを止めるのは `exclusiveContent` だけである

採用する設定は次のとおりである。`exclusiveContent` は宣言の順序に依存しない。偽の Central を先に宣言した上の 2 行でも、偽の Central に要求は届かなかった。

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { /* lab と同じ */ }
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/appachan/android-supply-chain-lab-lib")
                    credentials {
                        username = providers.environmentVariable("GITHUB_ACTOR").orNull
                        password = providers.environmentVariable("GITHUB_TOKEN").orNull
                    }
                }
            }
            filter { includeModule("com.github.appachan", "android-supply-chain-lab-lib") }
        }
    }
}
```

`includeGroup` ではなく `includeModule` にする。`com.github.appachan` の他の module が Maven Central にあるかもしれないからである。ライブラリが増えたら `includeModule` を足す。

`pluginManagement` の `exclusiveContent` は確認していない。ライブラリを plugin として配信しない前提である。

group の `com.github.appachan` は Maven Central で予約していない。予約していなくても `exclusiveContent` で防げるが、group の所有は別の問題として残る。

## 再現手順

ライブラリの再生成:

```sh
GRADLE_USER_HOME="$(mktemp -d)" sh -c '
  ./gradlew --write-verification-metadata pgp,sha256 --export-keys --write-locks build
  ./gradlew --write-verification-metadata pgp,sha256 --export-keys --write-locks build
'
```

リビルド:

```sh
./gradlew --no-build-cache --no-configuration-cache -PreleaseVersion=0.1.0 \
  :lib:assembleRelease :lib:sourceReleaseJar \
  :lib:generateMetadataFileForReleasePublication :lib:generatePomFileForReleasePublication
shasum -a 256 lib/build/outputs/aar/lib-release.aar
```

確認用のワークフローは一時ブランチ `probe/packages`、`probe/republish`、`probe/consumer`、`probe/status` に置き、確認の後に消した。実行の記録は Actions に残る。

## rss-reader に適用するときの差分

rss-reader は読むだけで、適用していない。

- `settings.gradle.kts` に論点 8 の `exclusiveContent` を足す。`pluginManagement` の repositories には足さない
- catalog にライブラリを足し、公開鍵を `gradle/verification-keyring.keys` に足す
- 再生成の後に、ライブラリの `sha256` を `verification-metadata.xml` に足す
- `Regenerate` のジョブの token の扱いを決める（論点 6）
- CI のジョブと Dependabot の secret store の両方に認証情報を入れる
- rss-reader は `setup-gradle` を使っている。token の環境変数と configuration cache の組み合わせは確認していない。署名鍵と同じ理由で、token が configuration cache に保存されないことを確認する
- fork の PR の CI の失敗をどう扱うかを決める

## Consequences

- 公開の workflow が失敗すると、リビルドの不一致が分かる。ただし公開は先に完了しているので、不一致のバージョンは利用側に届く状態で残る
- 利用側は、ハッシュと鍵の信頼の両方が必要になる。ライブラリの更新のたびに `sha256` を足す手間が増える
- `verify-reproducible` はリソースや NDK を足すと失敗するかもしれない
- 検証の鍵はシークレットに 1 つだけある。運用の鍵は別に作り直す
- レジストリは不変ではない。`packages: write` で動くワークフローは公開済みのバージョンを差し替えられる
- GitHub が署名する provenance はない。リビルドの比較はワークフローの改ざんには耐えない

## 未解決

- `Manage Actions access` が Maven のパッケージで使えるか
- Dependabot の実際の動作。`Manage Actions access` と `registries` のどちらも、自動の認証の Maven への適用も確認していない
- 別のリポジトリから読む classic PAT の動作。PAT を作っていない
- GitHub App の token で読めるか
- `Regenerate` に token を渡さない形のワークフロー
- 副鍵を使う鍵の `trusted-key` の扱い
- このプランの private リポジトリで environment の保護の規則が使えるか
- Gradle の失効と期限切れの扱いは実験と 9.7.1 の実装による。公式の説明と issue は見つけられなかった
- job summary の保存期間を過ぎたハッシュの出所。利用側の metadata に記録した値が残る

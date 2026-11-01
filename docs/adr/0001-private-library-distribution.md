# 1. Private library distribution

Date: 2026-10-31（2026-11-01 に追加の確認と public への変更を反映）

## Status

ライブラリ側の運用は Accepted。lab が依存してよいこと、鍵の信頼と `sha256` の併用、`sha256` のスクリプトでの追記、`.aar` の値の人の照合は、ユーザーが決めた。lab には [PR #24](https://github.com/appachan/android-supply-chain-lab/pull/24) で組み込んだ。rss-reader には恒常的には導入しない。

このリポジトリは 2026-11-01 にユーザーの指示で public になった。名前の private は当初の検証の条件を表していて、以下の private の記述のうち、結果を変えたものは「public にした後の確認」に書いた。

## Context

android-supply-chain-lab のテーマ 4 である。自作の Android ライブラリを private リポジトリから GitHub Packages の Maven で配信し、利用側が「正しいソースから正しい手順で作られた成果物だ」と確かめる方法を決める。

前提は lab の ADR-0001、ADR-0002、ADR-0003 と同じである。

- 依存の検証は `pgp,sha256`、keyring は armored、`<key-servers enabled="false">`
- 依存のバージョンは `gradle.lockfile` と `failOnNonReproducibleResolution()` で管理する
- Dependabot の更新を前提にする
- SNAPSHOT は使わない

このリポジトリの構成は Gradle 9.7.1、Android Gradle plugin 9.3.3、Kotlin 2.4.21、JDK 17 で、lab と同じである。

このアカウントは個人のアカウントで、組織には所属していない。`gh api user` の `plan` は null でプランを読めなかった。`GET /repos/.../rulesets` が `Upgrade to GitHub Pro or make this repository public to enable this feature.` を返すので、GitHub Pro より下のプランだと判断した。public にした後は rulesets と environment の `required reviewers` を使える。

## Decision

ライブラリ側:

- `v*` のタグの push で公開する。`GITHUB_TOKEN` の `packages: write` で公開し、PGP の署名を `.asc` として一緒に公開する
- 公開の後に、シークレットのない別のジョブがタグからビルドし直し、公開された 4 ファイル（AAR、sources、POM、`.module`）とバイト単位で比較する。比較に使った SHA-256 を job summary に残す
- `org.gradlex.reproducible-builds` は使わない
- 一度公開したバージョンは削除しない。直すときは新しいバージョンを出す。レジストリはこれを保証しないので、運用の規則である
- 比較を通った 4 ファイルに、シークレットと Gradle のないジョブ `attest` が artifact attestations を付ける。SLSA の generator は使わない
- `v*` のタグは ruleset `release-tags` で repository admin 以外の作成、更新、削除を止める
- `publish` ジョブは environment `release` で動き、`required reviewers` の承認を待つ。`SIGNING_KEY` と `SIGNING_PASSWORD` は environment のシークレットにする

利用側（lab に適用済み。rss-reader には恒常的には導入しない）:

- `exclusiveContent` と `includeModule` で、この module の取得先を GitHub Packages だけにする
- `verification-metadata.xml` で、ライブラリの鍵の `trusted-key` をバージョン付きで持ち、さらに同じバージョンの `.aar` と `.module` の `sha256` を持つ。Gradle の再生成は新しいバージョンの `sha256` を書かないので、検査つきのスクリプトで足す。スクリプトだけでは防げない脅威が残るので、人が `.aar` の値をリビルドの job summary と照合する工程は残す（「追加の確認」の 1）
- `gradle.lockfile` で更新を差分に出す
- lab の Actions の読み取りは `GITHUB_TOKEN` で足りる（「public にした後の確認」の 1）。PAT は要らない。匿名では読めない
- org の private ライブラリの読み取りの認証は検証の対象から外す。想定している org ではすでに内部の依存を解決できているので、認証はある前提にする
- 鍵の漏えいのときは、`trusted-key` を消して `ignored-key` に移す
- `regenerate` ジョブには読み取りの token を渡さない。ライブラリの取得は別のジョブで行い、artifact で渡す。同じジョブの中で step を分けるだけでは境界にならない（「追加の確認」の 2）

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

検証用の鍵は environment のシークレットに 1 つ置いた。運用の鍵では次のようにする。ここは実行していない。

- 主鍵はオフラインに置き、主鍵の秘密部分を除いた鍵輪と副鍵の鍵 ID を Actions シークレットに入れる。副鍵での署名は実際に確認した。ただし Gradle の信頼は副鍵の fingerprint に結びつくので、副鍵の交換は利用側には鍵の交換と同じに見える（「追加の確認」の 4）
- `SIGNING_KEY` は environment `release` のシークレットである。environment を指定しないジョブには渡らず、`release` は `v*` のタグと承認を要求する（「public にした後の確認」の 4）
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

public にした後は使える。結果は「public にした後の確認」の 2 に書いた。以下は private のときの記録である。

[公式](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations)の記述は `To use artifact attestations in private or internal repositories, you must be on a GitHub Enterprise Cloud plan.` である。

実行の結果も同じで、`actions/attest-build-provenance` v4.2.2 は次のエラーで失敗した（[run 41205219271](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41205219271) の `attest` のジョブ）。

```text
Failed to persist attestation: Feature not available for user-owned private repositories. To enable this feature, please make this repository public.
```


### 代案の比較

| 案 | 判定 | 理由 |
| --- | --- | --- |
| artifact attestations | 採用（public にした後） | 上記 |
| [SLSA の generic generator](https://github.com/slsa-framework/slsa-github-generator/blob/main/internal/builders/generic/README.md) | 不採用（実行していない） | 下記 |
| タグからのリビルドの比較 | 採用 | シークレットなしで動き、論点 4 の結果で成立する |
| 同じ鍵で provenance の JSON に署名する | 不採用（実行していない） | 鍵を持つジョブが自己申告するだけで、`.asc` の署名を超える情報がない |

SLSA の generator を実行しなかった理由は次のとおりである。

- README に `Currently all builds generate and post a new entry in the public` Rekor instance とあり、private リポジトリの名前が公開の Rekor に永続的に残る。`private-repository: true` はこれを受け入れるフラグである。取り消せないので、許可の範囲を超えると判断した
- 同じ README が `This project is no longer actively maintained.` と書き、新しい利用に artifact attestations を勧めている
- 再利用可能なワークフローは `@vX.Y.Z` のタグの参照が必須で、commit SHA で固定できない。lab の Action の固定の方針と合わない

### 採用した検証と、検証する人

リビルドの比較が示すのは、タグのソースから同じ成果物が作れることである。リビルドのジョブも同じリポジトリの定義で動くので、ワークフローの改ざんには耐えない。attestation はこの結果に GitHub の OIDC の証明書で署名を足すが、これも同じ定義で動くので、追跡の材料であって止める手段ではない。

| 工程 | 誰が | 何を |
| --- | --- | --- |
| タグの公開 | `publish.yml` の `verify-reproducible` | 公開された 4 ファイルとリビルドを比較する |
| ライブラリの更新の PR | スクリプトと、PR を確認する人 | スクリプトが検査のうえで `sha256` を追記する。人は `.aar` の値を job summary と照合し、`trusting` の差分がバージョンの追加だけであることを確認する |
| すべてのビルド | Gradle | 記録した鍵とハッシュを検証する |

ライブラリの更新の PR を確認する人は、更新前に次を実行する。`--source-ref` を省くと、同じ中身の別のバージョンでも成功する。

```sh
gh attestation verify android-supply-chain-lab-lib-<version>.aar \
  --repo appachan/android-supply-chain-lab-lib \
  --signer-workflow appachan/android-supply-chain-lab-lib/.github/workflows/publish.yml \
  --source-ref refs/tags/v<version>
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

- 新しいライブラリの `sha256` を足す処理が要る（論点 5）。手順は「追加の確認」の 1 に書く
- lab の `Regenerate verification metadata` ワークフロー（`.github/workflows/regenerate-verification-metadata.yml`）の `regenerate` ジョブは検証なしで依存のコードを実行するので、そこに読み取りの token を渡すと、依存のコードから読める。ADR-0002 がこのジョブにシークレットを渡さないと決めた理由と衝突する

2 つ目の対処は、取得のジョブと `regenerate` ジョブを分けることである。Actions で確認した結果は「追加の確認」の 2 に書く。

## 論点 7: 認証の選択肢

public の利用側のリポジトリの CI と Dependabot が読む方法の比較である。PAT は作っていない。この節は private のパッケージを別のリポジトリから読む場合の記録である。ユーザーの判断で検証の対象から外した。`Manage Actions access` はパッケージの settings になかった（ユーザーがスクリーンショットで確認。Options は Danger Zone だけ）。public のパッケージの読み取りの結果は「public にした後の確認」の 1 に書いた。

| 方法 | 権限の範囲 | 個人への紐づき | 失効の手間 | 確認の状態 |
| --- | --- | --- | --- | --- |
| `Manage Actions access` と `GITHUB_TOKEN` | パッケージごとに利用側のリポジトリを指定 | なし | パッケージの設定から外す | Maven のパッケージの settings に存在しない |
| classic PAT の `read:packages` | その人が読める全てのパッケージ。fine-grained PAT は使えない | 個人に紐づく | 手で失効し、Actions と Dependabot の 2 か所の secret を更新する。有効期限のたびに同じ | [公式](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)が示す方法。未実行 |
| 機械用のアカウントの classic PAT | そのアカウントに read を与えたリポジトリのパッケージだけ | 機械用のアカウント | PAT と同じ。アカウントの管理が増える | 未実行 |
| GitHub App の installation token | 不明 | なし | 自動で短期 | 未確認。下記 |

`Manage Actions access` については公式の記述が食い違う。

- [About permissions for GitHub Packages](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages) は Apache Maven registry を repository-scoped permissions だけの registry に挙げる。[Configuring a package's access control and visibility](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility) は、その種類のパッケージは常に linked repository の権限を継承すると書く
- 一方、[Maven registry のページ](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)は、`GITHUB_TOKEN` can be used if the repository is granted read access to the package と書く

2023 年の [community discussion](https://github.com/orgs/community/discussions/57603) には、`GITHUB_TOKEN` では同じ組織の別のリポジトリの Maven パッケージを読めないという報告がある。現在の状態は確認できていない。

GitHub App の token については、[公式](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages)が `GitHub Packages only supports authentication using a personal access token (classic).` と書く一方で、同じページが `GITHUB_TOKEN` を認めている。`GITHUB_TOKEN` も GitHub App の installation access token である。[github/docs の issue 9547](https://github.com/github/docs/issues/9547) は installation token が Maven で使えないという利用者の報告で、2022 年に閉じられている。公式の回答は確認していない。

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

## 追加の確認

2026-11-01 に、未解決のうちユーザーの判断を待たずに確かめられる 4 点を確認した。確認に使った鍵、environment、一時ブランチ、タグは終わった後に消した。

### 1. sha256 の追記の自動化

結論: `sha256` の追記は自動にできるが、人が job summary と照合する工程はなくせない。ハッシュを記録した後の差し替えはスクリプトで防げる。記録する前の差し替えは防げない。

依頼の前提と異なる点がある。再生成の直後のキャッシュは、署名を検証して取得したものではない。`--write-verification-metadata` の実行は署名の失敗で止まらない。AAR に 1 byte 足し、元の `.asc` のままにした `0.2.0` では、再生成も、同じ環境の検証の strict モードのビルドも成功し、再生成は次の行を書いた。

```xml
<artifact name="android-supply-chain-lab-lib-0.2.0.aar">
   <ignored-keys>
      <ignored-key id="8493022D3CF93D42B2DC1C80AD8EDA7ABCFDFAE6" reason="PGP verification failed"/>
   </ignored-keys>
   <sha256 value="d83eaf0ec17f..." origin="Generated by Gradle" reason="PGP signature verification failed!"/>
</artifact>
```

この metadata なら空の環境の `verify` も成功する。したがって、スクリプトはキャッシュが検証済みだと仮定できない。再生成が書いた metadata を読み、署名を検証できた証拠を確かめる。

次の順序で実行する。

1. 空の `GRADLE_USER_HOME` で再生成を 1 回実行する（lab の ADR-0002 の方式）。この節の確認のときは 2 回実行した
2. 同じ環境で検証の strict モードのビルドを実行し、失敗したら止まる
3. `pin_library_hashes.py` を実行する。期待する fingerprint は PR で変更できない場所に置く

実装は lab の [`.github/scripts/pin_library_hashes.py`](https://github.com/appachan/android-supply-chain-lab/blob/main/.github/scripts/pin_library_hashes.py) にある。この節の確認のときから 3 点を変えた。`pull_request` の workflow は PR で変更できるので、`Commit verification metadata`（main の定義）で実行する。このジョブに Gradle のキャッシュはないので、キャッシュではなく `library-repository` の artifact を読む。Gradle の並びの位置に `component` を挿入し、次の再生成の差分をなくす。

手元の結果は次のとおりである。最小の利用側プロジェクトと偽の GitHub Packages を使い、`0.1.0` はハッシュ付き、公開の対象は `0.2.0` である。

| シナリオ | 再生成 | 同じ環境の strict のビルド | スクリプト | 空の環境の `verify` |
| --- | --- | --- | --- | --- |
| 本物 | 成功 | 成功 | 1 件を追記 | 成功 |
| 上の後に再度スクリプトを実行 | | | 0 件、差分なし | |
| 追記の後に、同じ鍵で AAR を差し替え | | | | 失敗（`One artifact failed verification`） |
| AAR に 1 byte 追加、`.asc` はそのまま | 成功 | 成功 | 拒否（`signature verification failed`） | 成功。再生成が書いたハッシュが使われる |
| keyring にない鍵で署名 | 成功 | 成功 | 拒否（`not covered by trusted key`） | 実行していない |
| keyring にある別の鍵で署名 | 成功。その鍵を `trusted-key` に足す | 成功 | 拒否（`not covered by trusted key`） | 実行していない |
| 既存の記録に対して、同じ鍵の差し替えで再生成 | 成功。記録は変わらない | 失敗 | 0 件 | 失敗 |
| 記録の前に、同じ鍵で AAR を差し替え | 成功 | 成功 | 1 件を追記（`f60cad98...`） | 成功（検知できない） |

スクリプトが拒否しても、再生成のハッシュは metadata に残る。空の環境の `verify` は成功するので、スクリプトの失敗で後続を止める必要がある。lab の `review_verification_metadata.py` は署名の失敗の行を `exit=1` で拒否した。既存の記録に対する差し替えでは、このスクリプトは exit 0 で、止めたのは検証の strict モードのビルドである。

keyring にある別の鍵で署名した場合、fingerprint の検査だけが防いだ。攻撃者の fingerprint を渡すと 1 件を追記した。鍵サーバーに攻撃者の鍵があれば、再生成はこの状態を自動で作る（ADR-0002）。

人の照合をなくせるかを脅威ごとに評価した。

| 脅威 | スクリプト | job summary との照合 |
| --- | --- | --- |
| 記録の後に、同じ鍵で差し替え | 防げる。記録したハッシュで失敗する | 不要 |
| 署名の壊れた成果物、中身だけの改ざん | 防げる | 不要 |
| 別の鍵で署名した成果物 | 防げる。fingerprint を PR で変更できないことが条件 | 不要 |
| 公開から記録までの間に、同じ鍵で差し替え | 防げない。差し替え後の値を記録する | 検知する |
| `regenerate` ジョブの依存のコードが metadata を書き換える | 防げない。`artifact` は信頼しないデータである（ADR-0002） | 検知する |
| 署名鍵と公開のワークフローの侵害。最初から不正なバージョンを公開 | 防げない | リビルドの値も同じワークフローが書くので防げない。ソースのレビューの問題である |
| タグのソースに不正なコードが入る | 防げない | 防げない。リビルドが一致する |

したがって、照合は残す。記録する人の手間は `sha256` の追記の分だけ減り、PR で見るのは `.aar` の 1 つの値だけになる。

照合を自動にする案は実行していない。public にした後は attestation の検証を CI に置ける可能性があるが、ユーザーの判断は人の照合である。

### 2. token を渡さない再生成

結論: Gradle の step の環境から token はなくせるが、同じジョブの step の分離は境界にならない。取得と再生成はジョブを分ける。

実行は [run 41213142855](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41213142855) である。lab の main（f737f94）に論点 5 の差分を適用し、catalog を `0.2.0` に変えて、ADR-0002 と同じコマンドを 2 回実行した。読み取りの token には `GITHUB_TOKEN` を使った。

| 確認 | 同じジョブの step の分離（`step-separated`） | ジョブの分離（`fetch-library` と `regenerate-isolated`） |
| --- | --- | --- |
| Gradle の前の step の環境 | token の形の値 0 件、名前に `TOKEN` などを含む変数 0 件 | 同じ |
| Gradle のプロセスの環境（init script で確認） | 名前 0 件、token の形の値 0 件 | 同じ |
| `.git/config` の `extraheader` と `ghs_` | 0 件 | 0 件 |
| 再生成 2 回 | 成功 | 成功 |
| metadata の差分 | `trusting` の 2 行を含む +4 行 | `trusting` の 2 行 |
| 検証の strict モードの `build` | 成功 | 実行していない |
| ジョブの token でパッケージを読む | 読める（取得に使った） | 403 |

次に、同じジョブの別の step から `sudo` で `Runner.Worker` と `Runner.Listener` のメモリを走査した。値は出力せず、件数とハッシュの一致だけを出した。

最初の 2 回の走査は 0 件だった。token は 377 文字で、私のパターンの文字種が足りなかったためである。token を直接探す陽性対照で 61 件以上見つかったので、パターンを直して再度実行した（[run 41213896476](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41213896476)、[run 41214012666](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41214012666)）。

| 条件（run 41214012666） | 結果 |
| --- | --- |
| token を step の環境に持たない step | `job_token_in_memory=True` |
| `SIGNING_KEY` を受け取った step の後の、受け取らない step | `pgp_private_key_headers=31` |
| `SIGNING_KEY` を参照しないジョブ | `pgp_private_key_headers=0` |

GitHub がホストするランナーでは step が `sudo` を使える。token のない step でも、同じジョブで参照されたシークレットはメモリから読める。[tj-actions の侵害](https://www.stepsecurity.io/blog/harden-runner-detection-tj-actions-changed-files-action-is-compromised)で使われた経路である。

Gradle が依存のコードを実行する以上、守る境界はジョブである。`regenerate` ジョブの `GITHUB_TOKEN` も同じ VM のメモリにあるが、権限は `contents: read` で、パッケージは 403 だった。

ワークフローの形は次のとおりである。`fetch-library` は PR のコードを実行しない。バージョンは PR の catalog の値なので、形式を検査してから URL に使う。catalog からの読み取りと形式の検査は `step-separated` で確認した。`fetch-library` の確認の実行では `0.2.0` を固定した。

```yaml
jobs:
  fetch-library:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: read
    steps:
      - name: Fetch the library
        env:
          LIB_TOKEN: ${{ github.token }} # 別のリポジトリなら Dependabot のシークレットにする
        run: |
          [[ "$version" =~ ^[0-9]+[.][0-9]+[.][0-9]+$ ]]
          mkdir -p "lib-repo/$P/$version"
          for file in "$A-$version".{aar,module,pom}{,.asc}; do
            curl -fsSL -u "$GITHUB_ACTOR:$LIB_TOKEN" -o "lib-repo/$P/$version/$file" "$BASE/$P/$version/$file"
          done
      - uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7.0.2
        with:
          name: library-repository
          path: lib-repo
  regenerate:
    needs: fetch-library
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      # checkout、wrapper validation、JDK は lab と同じ
      - uses: actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333 # v8.0.2
        with:
          name: library-repository
          path: ${{ runner.temp }}/lib-repo
      - run: ./gradlew -PlibRepositoryDirectory="file://$RUNNER_TEMP/lib-repo" --write-verification-metadata pgp,sha256 --export-keys --write-locks build
```

settings は、URL をリテラルのまま残す。論点 6 のとおり Dependabot の `RepositoriesFinder` がリテラルを読むからである。`setUrl` で切り替えると、正規表現の結果は `https://maven.pkg.github.com/appachan/android-supply-chain-lab-lib` のままだった。

```kotlin
dependencyResolutionManagement {
    val libRepositoryDirectory = providers.gradleProperty("libRepositoryDirectory")
    // ...
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/appachan/android-supply-chain-lab-lib")
        if (libRepositoryDirectory.isPresent) {
            setUrl(libRepositoryDirectory.get())
        } else {
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}
```

CI の `verify` ジョブも依存のコードを実行するので、同じ形にできる。Dependabot の PR の実行では確認していない。

### 3. environment の保護の規則

結論: `required reviewers` と `wait timer` は使えない。environment の作成、シークレット、ブランチとタグのポリシーは API で設定でき、ポリシーは実際に効いた。ただし公式の説明とは異なる動作である。

| 設定（`PUT /repos/.../environments/...`） | 結果 |
| --- | --- |
| 空の environment | 成功 |
| `reviewers` | 失敗。`Please ensure the billing plan supports the required reviewers protection rule.` |
| `reviewers` と `prevent_self_review` | 同じ失敗 |
| `wait_timer` | 失敗。`Please ensure the billing plan supports the wait timer protection rule.` |
| `deployment_branch_policy`（`protected_branches`） | 成功 |
| `deployment_branch_policy`（`custom_branch_policies`） | 成功 |
| environment のシークレット | 成功 |

実効性はワークフローで確かめた。

| ポリシー | 実行した ref | 結果 | 実行 |
| --- | --- | --- | --- |
| `custom_branch_policies` で許可の登録が 0 件 | `probe/regenerate` | 実行され、シークレットを受け取った | [run 41213142855](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41213142855) |
| `main` のブランチだけを許可 | `probe/regenerate` | 拒否。`Branch "probe/regenerate" is not allowed to deploy to probe-protection due to environment protection rules.` | [run 41213896476](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41213896476) |
| `probe-env-*` のタグだけを許可 | `probe/regenerate` | 拒否 | [run 41214182777](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41214182777) |
| `probe-env-*` のタグだけを許可 | タグ `probe-env-1` | 実行され、シークレットを受け取った | [run 41214186669](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41214186669) |

許可の登録が 0 件だと制限にならない。必ず 1 件以上登録する。

[公式](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)は `Users with GitHub Free plans can only configure environments for public repositories.` と書いていて、実際の動作と異なる。このアカウントのプランは API で読めないが、上の 2 つの失敗の応答とは整合する。公式の説明と異なるので、この動作を唯一の防御にしない。

この確認は private のときのものである。public にした後の設定と結果は「public にした後の確認」の 4 に書いた。

### 4. 副鍵

結論: 主鍵の秘密部分を持たない鍵輪から、Gradle の signing plugin で署名できる。利用側の Gradle の信頼は署名した副鍵の fingerprint に結びつく。主鍵の fingerprint は副鍵を覆わないので、副鍵の交換は鍵の交換と同じ手順になる。

主鍵（certify のみ）`384851563DAE158BF0BC9A008D0D17E41181BC70`、副鍵 1 `DA0610F51A041B40D6F7F18CC79D2055392140D9`、副鍵 2 `80F62965428C1EC7C53FA16F1F24CD7D7DAEA94A` で手元で確認した。主鍵の秘密部分は GNU dummy の形で除いた。

署名の側の結果は次のとおりである。

| `useInMemoryPgpKeys` の呼び方 | 鍵輪 | 結果 |
| --- | --- | --- |
| 鍵 ID なし（`lib/build.gradle.kts` が今使っている 2 引数の形） | 主鍵と副鍵の秘密部分あり | 成功するが、certify のみの主鍵で署名する（`keyId=8d0d17e41181bc70`） |
| 鍵 ID なし | 主鍵の秘密部分なし | 失敗。`Cannot query the value of this provider because it has no value available.` |
| 鍵 ID 16 桁 | 主鍵の秘密部分なし | 失敗。`Error while evaluating property 'signatory.keyId'` |
| 鍵 ID 8 桁 | 主鍵の秘密部分なし | 成功。副鍵で署名する（`keyId=c79d2055392140d9`） |

副鍵で運用するなら `lib/build.gradle.kts` を 3 引数の形にして、副鍵の fingerprint の末尾 8 桁を渡す。

利用側の結果は次のとおりである。

| 確認 | 結果 |
| --- | --- |
| keyring に主鍵と副鍵 1、`0.3.0` を再生成 | `trusted-key` は副鍵 1 の fingerprint。keyring は主鍵と副鍵が 1 つの鍵輪で残る |
| 上の metadata で検証の strict モード | 成功 |
| `trusted-key` を主鍵の fingerprint に変える | 失敗（`2 artifacts failed verification`） |
| `trusted-key` を副鍵の 16 桁の鍵 ID に変える | 失敗。原因は確認していない |
| 副鍵 2 で署名した `0.4.0`、keyring は古いまま | 失敗。`Key servers are disabled` |
| 同じ条件で、副鍵 1 の信頼から `version` を外す | 失敗 |
| 同じ条件で、主鍵の信頼から `version` を外す | 失敗 |
| keyring を更新し、主鍵の信頼で `version` なし | 失敗 |
| keyring を更新し、副鍵 1 の信頼で `version` なし | 失敗 |
| keyring を更新せず再生成 | `ignored-key id="1F24CD7D7DAEA94A"` と `sha256` に落ちる |
| keyring を更新して再生成 | `<trusted-key id="80F62965...A94A" ... version="0.4.0"/>` が追加され、検証の strict モードが成功 |
| 交換の後の `0.3.0` | 成功。副鍵 1 の信頼が残る |
| `pin_library_hashes.py` に副鍵 2 の fingerprint | 1 件を追記 |
| `pin_library_hashes.py` に主鍵の fingerprint | 拒否（`not covered by trusted key`） |

副鍵の利点は、Gradle の検証では得られない。主鍵をオフラインに保てる利点は残るが、利用側は副鍵ごとに信頼を更新する。主鍵を人の確認に使う場合は、新しい副鍵が主鍵に結びついていることを別のツールで確かめる必要がある。Gradle はこの確認をしない。

スクリプトに渡す期待の fingerprint は副鍵のものである。副鍵の交換のたびに更新する。

## public にした後の確認

2026-11-01 にリポジトリとパッケージが public になった後の確認である。`gh repo view` の `visibility` と、`GET /users/appachan/packages/maven/...` の `visibility` はどちらも public だった。

公開の前に tree と全履歴に加えて、public で読めるようになった 16 件の run のログ、2 件の artifact、ブランチを消した probe の commit（SHA で読める）を、秘密鍵と token のパターンで検索した。一致は 0 件である。`SIGNING_KEY` と `SIGNING_PASSWORD` の値はログにマスクされている。

### 1. public のパッケージの読み取り

lab の一時ブランチの workflow で確かめた（[lab の run 41264320118](https://github.com/appachan/android-supply-chain-lab/actions/runs/41264320118)）。lab の main は変更していない。

| 条件 | 結果 |
| --- | --- |
| lab の `GITHUB_TOKEN`（`packages: read`）で `maven-metadata.xml`、POM、AAR、`.asc` | すべて 200。AAR は `fc6f0cfd...` で job summary の値と一致 |
| 同じ token で Gradle の解決 | `BUILD SUCCESSFUL` |
| lab の `GITHUB_TOKEN`（`contents: read` だけ） | 200。`packages: read` は要らない |
| Actions から匿名 | 401 |
| 手元から匿名（POM、AAR、`maven-metadata.xml`） | 401 |
| 手元の `gh` の token（`read:packages` なし）で AAR | 401。public でも変わらない |

public のパッケージでも GitHub Packages の Maven は認証を要求する。別のリポジトリの `GITHUB_TOKEN` は通るので、lab の CI に追加のシークレットは要らない。fork の PR にも読み取りの `GITHUB_TOKEN` は渡るが、確認していない。外部の人が手元でビルドするには `read:packages` の token が要る。

### 2. artifact attestations

`publish.yml` に `attest` ジョブを足し、`0.3.0` を公開した（[run 41264504900](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41264504900)、[attestation 62240678](https://github.com/appachan/android-supply-chain-lab-lib/attestations/62240678)）。`actions/attest` v4.2.2 を使う。`actions/attest-build-provenance` は `actions/attest` の wrapper で、新しい利用には `actions/attest` が勧められている。

`attest` ジョブは `verify-reproducible` の後に動き、公開物をダウンロードして 4 ファイルを証明する。「追加の確認」の 2 のとおり step の分離は境界にならないので、OIDC の token（`id-token: write`）は Gradle も `SIGNING_KEY` もないジョブにだけ渡す。

手元の `gh` の token ではパッケージを読めないので、タグのコミットを手元で（macOS、Temurin 17）ビルドした。4 ファイルの SHA-256 はすべて公開物と一致した。OS をまたいでも再現できたことになる。

| `gh attestation verify` の入力 | 結果 |
| --- | --- |
| 本物の 4 ファイル、`--repo`、`--signer-workflow .../publish.yml`、`--source-ref refs/tags/v0.3.0` | 成功 |
| AAR に 1 バイト追加 | 失敗（exit 1、この digest の attestation が 404） |
| POM のバージョンを書き換え | 失敗（exit 1、同じ） |
| 本物の AAR、`--repo` が lab | 失敗（exit 1） |
| 本物の AAR、`--source-ref refs/tags/v9.9.9` | 失敗。`expected SourceRepositoryRef to be refs/tags/v9.9.9, got refs/tags/v0.3.0` |
| 本物の AAR、`--signer-workflow .../ci.yml` | 失敗（exit 1） |
| `0.2.0` の AAR、`--source-ref refs/tags/v0.2.0` | 失敗。`0.3.0` の attestation を見つける |
| `0.2.0` の AAR、`--source-ref refs/tags/v0.3.0` | 成功 |

最後の 2 行は、attestation が digest に結びつくことを示す。AAR にはバージョンが入らず、`0.1.0` から `0.3.0` まで AAR は同じ `fc6f0cfd...` である。`--repo` だけの検証は「どこかのタグでこの中身が作られた」としか言わない。`--source-ref` と `--signer-workflow` を指定する。検証に使う API の `GET /repos/.../attestations/sha256:...` は匿名でも 200 だった。

attestation は公開の Rekor の透明性ログにも残る（logIndex 3617804663）。public のリポジトリなので問題にしない。

### 3. タグの ruleset

ruleset `release-tags`（id 26296413）を active にした。対象は `refs/tags/v*`、規則は `creation`、`update`、`deletion`、`non_fast_forward` である。確認には workflow を含まない commit に付けた使い捨てのタグを使い、終わった後に消した。

このリポジトリの人は admin の 1 人だけで、write role の人はいない。write role の代わりに、`contents: write` の `GITHUB_TOKEN` を admin ではない書き込みの actor として使った（[run 41265259811](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41265259811)、[run 41265341951](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41265341951)）。

| 実行者 | 操作 | bypass なし | admin を bypass |
| --- | --- | --- | --- |
| admin（SSH の push） | `v*` の作成 | 拒否。`Cannot create ref due to creations being restricted.` | 成功 |
| admin（SSH の push） | `v*` の移動 | 拒否。`Cannot update this protected ref.` | 未確認 |
| admin（SSH の push） | `v*` の削除 | 拒否。`Cannot delete this tag` | 成功 |
| admin（REST API） | `v*` の作成 | 拒否（422） | 未確認 |
| `GITHUB_TOKEN` | `v*` の作成（API、git push） | 拒否 | 拒否 |
| `GITHUB_TOKEN` | `v*` の削除 | 拒否 | 拒否 |
| `GITHUB_TOKEN` | `v*` 以外のタグの作成と削除（対照） | 成功 | 成功 |
| `GITHUB_TOKEN` | ruleset の無効化 | 403 | 403 |

bypass の扱いは次のとおりである。

- 既定は bypass なしで、admin も止まる。admin を bypass にしないと誰も `v*` を作れない
- admin は ruleset を変更できる。止まるのは操作の誤りと、admin でない人である。admin の 1 人が決めれば外せる
- API が受け付けた bypass: `RepositoryRole` の admin（5）、maintain（2）、write（4）、`DeployKey`、`User`
- API が拒否した bypass: `Integration` の GitHub Actions（15368。`must be part of the ruleset source or owner organization`）、`OrganizationAdmin`（`ruleset source must be in an organization`）、`pull_request` の mode（タグでは不可）
- write role（4）を足しても `GITHUB_TOKEN` は拒否された。この role の bypass は Actions の token には効かない
- `write` の bypass は、守りたいものを外す設定なので使わない。最終の設定は admin（5）だけである

ここで確かめられないこと: 実際の write role の人が拒否されるか。`GITHUB_TOKEN` は role を持つ人ではないので、同じ結果になるのは公式の説明からの推定である。組織の `OrganizationAdmin`、Team、GitHub App の bypass は個人のリポジトリでは確かめられない。Actions の token は組織のリポジトリなら bypass にできる可能性がある。

### 4. environment の `required reviewers`

environment `release` を作った。`required_reviewers` は `appachan`、`prevent_self_review` は false、タグのポリシーは `v*` である。`prevent_self_review` は API で受け付けたが、true のままだと 1 人のリポジトリではタグを push した本人が承認できないと見込まれるので、false にした。動作は確認していない。

`publish.yml` の `publish` ジョブに `environment: release` を足し、`v0.4.0` を push した。

| 確認 | 結果 | 実行 |
| --- | --- | --- |
| `v0.4.0` の `publish` ジョブ | `waiting`。step は 0 件。`0.4.0` は公開されていない | [run 41265435254](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41265435254) |
| 承認者の表示 | `pending_deployments` は `appachan` を返す | 同上 |
| タグ `probe-env-gate-1` から `environment: release` を指定 | 拒否。`Tag "probe-env-gate-1" is not allowed to deploy to release due to environment protection rules.` | [run 41265437870](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41265437870) |
| 同じ実行で environment を指定しないジョブ | repository の secret は読める。environment の secret（確認用のダミー）は読めない | 同上 |

Claude は `v0.4.0` を承認していない。`gh` の token はユーザー本人のものなので API では承認できるが、人の承認を確かめる検証で Claude が承認すると意味がなくなる。ユーザーが画面で承認した後、23 分待っていた同じ run が再開した（[run 41265435254](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41265435254)）。`publish`、`verify-reproducible`、`attest` はすべて成功し、`0.4.0` が公開された。承認の記録は `GET /actions/runs/{id}/approvals` で `approved`、`appachan` である。

承認は鍵の移動より前だったので、environment にシークレットがなく、`0.4.0` は repository の `SIGNING_KEY`（旧鍵）で署名された。`.aar.asc` の鍵 ID は `0.3.0` と同じ `AD8EDA7ABCFDFAE6` である。environment に同じ名前のシークレットがなければ repository のものが渡る。

GitHub のシークレットの値は読めず、旧鍵のファイルは消してあるので、新しい検証用の鍵に置き換えた。

| 項目 | 値 |
| --- | --- |
| 生成 | Gradle 9.7.1 が同梱する BouncyCastle 1.85、RSA 3072、期限なし |
| user ID | `android-supply-chain-lab-lib release key C (test)` |
| fingerprint | `6BE556226D4323DB5C972F74667E34342E2C6F66` |
| 鍵サーバーへの登録 | しない。利用側は keyring に鍵を足す |
| 秘密鍵の保管 | environment `release` のシークレットだけ。作業用のファイルは消した |

検証用の鍵なので、作業用のファイルを残していない。もう一度移す必要が出たら、鍵を作り直す。

| 確認 | 結果 | 実行 |
| --- | --- | --- |
| environment を指定しないジョブの `SIGNING_KEY`、`SIGNING_PASSWORD` | どちらも空 | [run 41267216779](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41267216779) |
| repository のシークレット | 0 件 | API |
| environment `release` のシークレット | `SIGNING_KEY`、`SIGNING_PASSWORD` | API |

移動の作業では、私のスクリプトの誤りで、environment への設定の前に repository のシークレットを消した。公開の実行はなかったので影響はない。

環境のシークレットで署名できることは `0.5.0` の承認の後に確かめる。鍵の交換の実例も、`0.4.0` ではなく `0.5.0` で確かめる。

ここで確かめられないこと: `required reviewers` の承認者でない write role の人が承認できないこと、`prevent_self_review` の実際の効果。

### 5. ruleset と `required reviewers` が止めないもの

write role の 1 人でリリースできる問題に対して、ruleset と `required reviewers` は `v*` のタグと署名鍵を止める。バージョンの公開そのものは止めない。

[run 41265259811](https://github.com/appachan/android-supply-chain-lab-lib/actions/runs/41265259811) は `v*` ではないタグ `probe-write-actor-1` の workflow である。

| 確認 | 結果 |
| --- | --- |
| `SIGNING_KEY` を読めるか（repository の secret のとき） | 読めた。値は出力していない。移動の後は読めない |
| `packages: write` の `GITHUB_TOKEN` で `0.0.2-probe` を PUT | POM、AAR とも 200。`.asc` はなく、ルールと承認を通っていない |
| 同じ token で `0.0.2-probe` を削除 | 成功。確認の後に残っていない |

`packages: write` は workflow の宣言で得られるので、シークレットを移しても、write role の人は `v*` 以外の ref の workflow から未署名のバージョンを公開し、公開済みのバージョンを消せる。止めるのは利用側である。

| 利用側の検査 | この公開に対して |
| --- | --- |
| 鍵の信頼（`pgp`） | `.asc` がないと `trusting` に新しいバージョンが足されないので、`pin_library_hashes.py` が拒否する。lab の [run 41268379086](https://github.com/appachan/android-supply-chain-lab/actions/runs/41268379086) の `update-unsigned` で確認した |
| `sha256` の記録 | 公開済みのバージョンの差し替えを検知する |
| `gh attestation verify` の `--signer-workflow` と `--source-ref` | この公開には attestation がない。別の ref の workflow が attestation を作っても、`--source-ref` が一致しない |

`SIGNING_KEY` は environment に移したので、未署名のバージョンは利用側で止まり、署名には承認が要る。

### 6. lab への組み込み

[PR #24](https://github.com/appachan/android-supply-chain-lab/pull/24) で lab の `:shared:ui` が `0.3.0` に依存した。merge commit は `564ae08`、PR の `verify` は [run 41269841663](https://github.com/appachan/android-supply-chain-lab/actions/runs/41269841663)、main の CI は [run 41270136751](https://github.com/appachan/android-supply-chain-lab/actions/runs/41270136751) で、どちらも成功した。実装、差し替えの検知の表、鍵の交換の手順は lab の ADR-0002 の「GitHub Packages のライブラリ」に書いた。

| 差し替えの確認（lab の構成、空の環境） | 結果 |
| --- | --- |
| 実物の `0.3.0` | 成功 |
| 同じ鍵の有効な署名を持つ差し替え | `sha256` で失敗 |
| 同じ差し替え、`sha256` なしの metadata | 成功。鍵の信頼だけでは検知できない |
| 中身の改ざん、攻撃者の鍵 | 失敗 |

GitHub Packages の `0.3.0` は変更していないので、差し替えは `-PlibRepositoryDirectory` のディレクトリで再現した。Actions のキャッシュが効いたビルドでの差し替えは確認していない。

鍵の交換の実例は `0.5.0` で確かめる。`0.5.0` は environment `release` の新しい鍵で署名される。lab は先に新しい鍵の公開鍵を keyring に、fingerprint を `LIBRARY_SIGNING_KEYS` に足す。

## 再現手順

ライブラリの再生成:

```sh
GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --write-verification-metadata pgp,sha256 --export-keys --write-locks build
```

リビルド:

```sh
./gradlew --no-build-cache --no-configuration-cache -PreleaseVersion=0.1.0 \
  :lib:assembleRelease :lib:sourceReleaseJar \
  :lib:generateMetadataFileForReleasePublication :lib:generatePomFileForReleasePublication
shasum -a 256 lib/build/outputs/aar/lib-release.aar
```

確認用のワークフローは一時ブランチ `probe/packages`、`probe/republish`、`probe/consumer`、`probe/status`、タグ `probe-write-actor-1`、`probe-write-actor-2`、`probe-env-gate-1`、lab の一時ブランチ `probe/lib-read` に置き、確認の後に消した。実行の記録は Actions に残る。

## rss-reader に適用するときの差分

rss-reader にはこのライブラリを恒常的に導入しない。依存は検証のための一時的なものに限る。rss-reader は読むだけで、適用していない。一時的に試すときの差分は次のとおりである。

- `settings.gradle.kts` に論点 8 の `exclusiveContent` を足す。`pluginManagement` の repositories には足さない
- catalog にライブラリを足し、公開鍵を `gradle/verification-keyring.keys` に足す
- 再生成の後に、ライブラリの `sha256` を `verification-metadata.xml` に足す
- lab の `Regenerate verification metadata` ワークフロー（`.github/workflows/regenerate-verification-metadata.yml`）の `regenerate` ジョブの前に取得のジョブを足し、`regenerate` ジョブには token を渡さない。settings は「追加の確認」の 2 の形にする
- 再生成と検証の strict モードのビルドの後に、`Commit verification metadata` で `pin_library_hashes.py` を実行する。期待する fingerprint は main の定義に置く
- CI のジョブは `GITHUB_TOKEN` で読める。Dependabot の認証は未確認
- rss-reader は `setup-gradle` を使っている。token の環境変数と configuration cache の組み合わせは確認していない。署名鍵と同じ理由で、token が configuration cache に保存されないことを確認する
- fork の PR の CI の失敗をどう扱うかを決める

## Consequences

- 公開の workflow が失敗すると、リビルドの不一致が分かる。ただし公開は先に完了しているので、不一致のバージョンは利用側に届く状態で残る
- 利用側は、ハッシュと鍵の信頼の両方が必要になる。`sha256` の追記は自動にできるが、job summary との照合は人に残る
- 副鍵を使っても、副鍵の交換のたびに利用側の keyring と metadata の更新が要る
- `verify-reproducible` はリソースや NDK を足すと失敗するかもしれない
- 検証の鍵は environment のシークレットに 1 つだけある。運用の鍵は別に作り直す
- `0.1.0` から `0.4.0` は旧鍵 `8493022D3CF93D42B2DC1C80AD8EDA7ABCFDFAE6`、`0.5.0` からは `6BE556226D4323DB5C972F74667E34342E2C6F66` で署名する。旧鍵の公開鍵は commit `503eb2a` の `docs/signing/release-key.pub.asc` にある
- レジストリは不変ではない。`packages: write` で動くワークフローは公開済みのバージョンを差し替えられる
- attestation は追跡の材料で、公開を止めない。リビルドの比較と同じく、ワークフローの改ざんには耐えない
- `v*` のリリースには、admin のタグの push と承認の 2 つの操作が要る。admin が 1 人なら、どちらも同じ人である
- `0.1.0` から `0.3.0` まで AAR が同じなので、AAR の attestation は `--source-ref` なしではバージョンを区別しない
- `packages: write` は ruleset と environment を通らない。未署名のバージョンの公開と公開済みのバージョンの削除は、利用側の検査で扱う

## 未解決

- environment のシークレットでの署名と公開。`0.5.0` の承認の後に確かめる
- 実際の write role の人に対する ruleset の拒否と、承認者でない人の承認の拒否。人が 1 人なので確かめられない
- `prevent_self_review` の 1 人のリポジトリでの動作
- Dependabot の実際の動作。`registries` も、自動の認証の Maven への適用も確認していない
- fork の PR の `GITHUB_TOKEN` で読めるか
- 実際の Dependabot の PR で、取得のジョブから `regenerate` ジョブへ渡す形が動くか。この確認は `push` の実行で、`GITHUB_TOKEN` を読み取りの token の代わりに使った
- `ignored-key` に副鍵の fingerprint を書いたときの動作と、副鍵の失効の扱い
- `trusted-key` に 16 桁の鍵 ID を書いたときの失敗の原因。`useInMemoryPgpKeys` が 16 桁の鍵 ID で失敗した原因
- Gradle の失効と期限切れの扱いは実験と 9.7.1 の実装による。公式の説明と issue は見つけられなかった
- job summary の保存期間を過ぎたハッシュの出所。利用側の metadata に記録した値が残る

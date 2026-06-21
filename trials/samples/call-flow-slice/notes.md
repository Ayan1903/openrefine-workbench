# sample-call-flow-slice

## goal

`bin/run-trial` から最小の call-flow slicing を実行し、
`foo-call-flow.tsv` に root メソッドからの呼び出し骨格を出力する。

この sample は `slice-call-flow` 自体の最小例であり、
`depth` / `parent-method` / `call-file` / `call-line` を使って
メソッド間の呼び出し構造を追う用途に向いている。

`source-lines-enriched.tsv` を主表にしてコード行から読む用途は、
別 sample の `trials/samples/call-flow-source-lines/` を使う。

## expected output

起点 `FooController/foo` を含む行と、
その呼び出し先メソッド群が `foo-call-flow.tsv` に出力される。

root 行では、`FooController/foo` の定義位置として
`method-file` と `method-start-line` が埋まる。

子ノード行では、次の 2 種類の位置情報が入る。

- `method-file` / `method-start-line`
  そのメソッド自身の定義位置
- `call-file` / `call-line`
  親メソッドからそのメソッドを呼んでいる場所

主要列:

- `root-method`
- `method-id`
- `depth`
- `parent-method`
- `method-file`
- `method-start-line`
- `method-end-line`
- `call-file`
- `call-line`
- `edge-kind`

## OpenRefine guide

この sample では、`foo-call-flow.tsv` を 1 表だけ OpenRefine に取り込み、
骨格表として読む。

### expected usage

- `depth` facet で root から何ホップ先まで広がっているかを見る
- `parent-method` で直接の呼出元を追う
- `method-file` / `method-start-line` で各メソッドの定義位置を確認する
- `call-file` / `call-line` でどこから呼ばれているかを見る
- `edge-kind` で root 行と call 行を見分ける

### reading flow

1. `depth = 0` で root 行を確認する
2. `depth = 1` で最初の呼び出し先を確認する
3. 気になる `method-id` があれば、その `call-file` / `call-line` を確認する
4. さらに深掘りしたい箇所があれば、別途 `source-lines` 主表の sample でコード行へ降りる

## run

```bash
bin/run-trial trials/samples/call-flow-slice/trial.edn
```

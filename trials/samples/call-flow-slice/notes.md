# sample-call-flow-slice

## goal

`bin/run-trial` から最小の call-flow slicing を実行し、
`slice-results` の出力を `exports/foo-call-flow.tsv` に保存する。

## expected output

起点 `FooController/foo` を含む行と、
深さ 1 の `BarService/bar` を含む行が TSV に出力される。

root 行では、`FooController/foo` の定義位置として
`method-file` と `method-start-line` が埋まることを期待する。

子ノード行では、

- `method-file` / `method-start-line`
  そのメソッド自身の定義位置
- `call-file` / `call-line`
  親メソッドからそのメソッドを呼んでいる場所

が両方入ることを期待する。

主要列:

- `root-method`
- `method-id`
- `depth`
- `parent-method`
- `method-file`
- `method-start-line`
- `call-file`
- `call-line`

## run

```bash
bin/run-trial trials/samples/call-flow-slice/trial.edn
```

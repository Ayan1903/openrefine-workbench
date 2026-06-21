# sample-call-flow-source-lines

## goal

`bin/run-trial` から `source-lines-enriched.tsv` を主表として生成し、
OpenRefine 上でコード行単位に読み進めるための基本セットを作る。

この sample は、

- 普段は `source-lines-enriched.tsv` を主画面としてコードを読む
- 気になる箇所が見つかったら、別途 `call-flow-slice` を実行して結果を重ねる
- さらに別の箇所で同じことを繰り返す

という通常フローの最小例である。

## expected output

`foo-source-lines-enriched.tsv` は package scope に含めた全ソース行に対して、
所属メソッドと呼び出し情報を付加した主表になる。

主要列:

- `file`
- `package`
- `file-class`
- `line`
- `text`
- `blank-line?`
- `class`
- `method-id`
- `method-start-line`
- `method-end-line`
- `call-count`
- `call-to`

必要になった時点で `:slice {:root ... :depth ...}` を export phase に加えると、
次の slice 注釈列も追加できる。

- `slice-root-method`
- `slice-depth`
- `slice-parent`
- `in-slice-method?`

補助表:

- `foo-method-spans.tsv`
  - method-id 単位の定義位置・シグネチャ確認用

## OpenRefine guide

この sample では、`foo-source-lines-enriched.tsv` を主表として読み、
`foo-method-spans.tsv` を参照表として使う。

対象範囲は
` :scope {:package-prefixes ["com.example"]}`
で決めている。

### import order

1. `foo-source-lines-enriched.tsv`
2. `foo-method-spans.tsv`

### intended roles

- `foo-source-lines-enriched.tsv`
  - 行単位でコードを読む主表
- `foo-method-spans.tsv`
  - `method-id` / `call-to` から定義位置を引く参照表

### scenario

1. `foo-source-lines-enriched.tsv` を root file に絞ってコードを読む
2. `call-count > 0` の行を見て、どこで別メソッドを呼んでいるか確認する
3. `call-to` を使って `foo-method-spans.tsv` から呼び出し先の定義位置を引く
4. さらに深掘りしたくなったら、別途 `trials/samples/call-flow-slice/` を実行して `foo-call-flow.tsv` を得る
5. その `foo-call-flow.tsv` を OpenRefine に取り込み、気になる `method-id` の call-flow を重ねて見る
6. また主表へ戻り、別の気になる行を起点に読み進める

### concrete operations

#### 1. root file を起点にする

`foo-source-lines-enriched.tsv` を開き、
`file = src/main/java/com/example/FooController.java`
に絞る。

`file-class = FooController` でも近い見方はできるが、
将来 1 ファイル複数 class のケースでは `file` の方が厳密である。

#### 2. 呼び出し行だけを見る

1. `call-count` 列で `Text filter` を開く
2. `^[1-9]` を入れる
3. 必要なら `blank-line? = false` も併用する

#### 3. 呼び出し先メソッドの定義位置を引く

`call-to` 列を選び、
`Edit column` → `Add column based on this column...` を実行する。

- 新しい列名: `called-method-span`
- GREL:

```grel
if(
  isBlank(value),
  null,
  with(
    cell.cross("foo-method-spans", "method-id")[0],
    r,
    r.cells["file"].value + ":" + r.cells["method-start-line"].value + "-" + r.cells["method-end-line"].value
  )
)
```

- Clojure:

```clojure
(if
  (or (nil? value) (= "" value))
  nil
  (let [r (-> (cross value "foo-method-spans" "method-id")
              (nth 0))]
    (str (get-in r ["cells" "file" "value"])
         ":"
         (get-in r ["cells" "method-start-line" "value"])
         "-"
         (get-in r ["cells" "method-end-line" "value"]))))
```

#### 4. 深掘りしたいときは call-flow-slice を重ねる

主表で気になる `method-id` や `call-to` が見つかったら、
別途 `trials/samples/call-flow-slice/trial.edn` を実行して
`foo-call-flow.tsv` を作る。

そのうえで OpenRefine に取り込み、

- `depth`
- `parent-method`
- `call-file`
- `call-line`

を見ると、その箇所の call-flow を骨格表として追える。

## run

```bash
bin/run-trial trials/samples/call-flow-source-lines/trial.edn
```

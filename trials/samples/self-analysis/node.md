# node / XTDB メモ

## `:java-compile-errors` の覗き方

`compile-errors-to-xtdb!` が書いている内容は、専用 helper を増やさなくても `core/q` で確認できる。

最小例:

```clojure
(core/start! {:persist? false})
(core/compile-errors-dir! "trials/samples/self-analysis")
(core/q '(from :java-compile-errors [*]))
(core/stop!)
```

必要な列だけ見る例:

```clojure
(core/q
 '(from :java-compile-errors
    [{:file/path path
      :java/compile-error? err?
      :java/compile-errors errs}]))
```

エラーがあるものだけ見る例:

```clojure
(->> (core/q '(from :java-compile-errors [*]))
     (filter :java/compile-error?))
```

## 今の判断

今すぐ公開 API に専用 helper を追加するより、まずは `core/q` で peep できることをメモとして残す。

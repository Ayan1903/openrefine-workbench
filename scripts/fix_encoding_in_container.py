import json, sys
p = "/app/one_embedding.txt"
b = open(p, "rb").read()
s = None
for enc in ("utf-8", "utf-8-sig", "utf-16", "utf-16-le", "utf-16-be", "latin-1"):
    try:
        s = b.decode(enc)
        if s and s.strip():
            json.loads(s)   # проверка, что это валидный JSON
            break
    except Exception:
        s = None
if not s:
    print("cannot decode file inside container", file=sys.stderr)
    sys.exit(2)
open(p, "w", encoding="utf-8").write(s)
print("rewritten as utf-8, length =", len(json.loads(s)))

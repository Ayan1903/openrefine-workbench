import csv
rows = [["query","score"],["example",0.0]]
with open("bench_ci.csv","w",newline="",encoding="utf-8") as f:
    writer = csv.writer(f)
    writer.writerows(rows)
print("Created bench_ci.csv (placeholder)")

import psycopg2

conn = psycopg2.connect(
    dbname="semanticdb",
    user="postgres",
    password="123",
    host="localhost",
    port="5432"
)

cur = conn.cursor()
cur.execute("SELECT COUNT(*) FROM clinics_raw;")
count = cur.fetchone()[0]

print("Строк в clinics_raw:", count)

cur.close()
conn.close()

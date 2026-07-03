import psycopg2

conn = psycopg2.connect(
    dbname="semanticdb",
    user="postgres",
    password="123",
    host="localhost",
    port="5432"
)

cur = conn.cursor()

cur.execute("SELECT id, website FROM clinics_raw;")
rows = cur.fetchall()

def insert_site(clinic_id, site):
    if site is None or site.strip() == "":
        return
    cur.execute("""
        INSERT INTO clinic_websites (clinic_id, website)
        VALUES (%s, %s);
    """, (clinic_id, site))
    print("Добавлен сайт:", clinic_id, site)

for clinic_id, site in rows:
    insert_site(clinic_id, site)

conn.commit()

print("Все сайты успешно перенесены!")

cur.close()
conn.close()

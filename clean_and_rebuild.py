import psycopg2
import json

PASSWORD = "123"

conn = psycopg2.connect(
    dbname="semanticdb",
    user="postgres",
    password=PASSWORD,
    host="localhost",
    port="5432"
)

cur = conn.cursor()

print("=== ШАГ 1: Очистка связанных таблиц ===")

tables = [
    "clinic_phones",
    "clinic_websites",
    "clinic_emails",
    "clinic_hours",
    "clinic_comments",
    "clinics_json"
]

for t in tables:
    cur.execute(f"DELETE FROM {t};")
    print(f"Очищено: {t}")

conn.commit()


print("\n=== ШАГ 2: Массовый перенос телефонов ===")
cur.execute("SELECT id, phone1, phone2 FROM clinics_raw;")
rows = cur.fetchall()

for clinic_id, phone1, phone2 in rows:
    if phone1 and phone1.strip():
        cur.execute("INSERT INTO clinic_phones (clinic_id, phone) VALUES (%s, %s);", (clinic_id, phone1))
    if phone2 and phone2.strip():
        cur.execute("INSERT INTO clinic_phones (clinic_id, phone) VALUES (%s, %s);", (clinic_id, phone2))

conn.commit()


print("\n=== ШАГ 3: Массовый перенос сайтов ===")
cur.execute("SELECT id, website FROM clinics_raw;")
rows = cur.fetchall()

for clinic_id, site in rows:
    if site and site.strip():
        cur.execute("INSERT INTO clinic_websites (clinic_id, website) VALUES (%s, %s);", (clinic_id, site))

conn.commit()


print("\n=== ШАГ 4: Массовый перенос email ===")
cur.execute("SELECT id, email FROM clinics_raw;")
rows = cur.fetchall()

for clinic_id, email in rows:
    if email and email.strip():
        cur.execute("INSERT INTO clinic_emails (clinic_id, email) VALUES (%s, %s);", (clinic_id, email))

conn.commit()


print("\n=== ШАГ 5: Массовый перенос часов работы ===")
cur.execute("SELECT id, working_hours FROM clinics_raw;")
rows = cur.fetchall()

for clinic_id, hours in rows:
    if hours and hours.strip():
        cur.execute("INSERT INTO clinic_hours (clinic_id, hours) VALUES (%s, %s);", (clinic_id, hours))

conn.commit()


print("\n=== ШАГ 6: Массовый перенос комментариев ===")
cur.execute("SELECT id, comment FROM clinics_raw;")
rows = cur.fetchall()

for clinic_id, comment_raw in rows:
    if comment_raw:
        comments = [c.strip() for c in comment_raw.split("|")]
        for c in comments:
            if c:
                cur.execute("INSERT INTO clinic_comments (clinic_id, comment) VALUES (%s, %s);", (clinic_id, c))

conn.commit()


print("\n=== ШАГ 7: Пересборка JSON‑слоя ===")

cur.execute("SELECT id, name, type, address, district_id, source_id, status_id, date_checked FROM clinics;")
clinics = cur.fetchall()

for (clinic_id, name, type_org, address, district_id, source_id, status_id, date_checked) in clinics:

    cur.execute("SELECT phone FROM clinic_phones WHERE clinic_id = %s;", (clinic_id,))
    phones = [r[0] for r in cur.fetchall()]

    cur.execute("SELECT website FROM clinic_websites WHERE clinic_id = %s;", (clinic_id,))
    websites = [r[0] for r in cur.fetchall()]

    cur.execute("SELECT email FROM clinic_emails WHERE clinic_id = %s;", (clinic_id,))
    emails = [r[0] for r in cur.fetchall()]

    cur.execute("SELECT hours FROM clinic_hours WHERE clinic_id = %s;", (clinic_id,))
    hours = [r[0] for r in cur.fetchall()]

    cur.execute("SELECT comment FROM clinic_comments WHERE clinic_id = %s;", (clinic_id,))
    comments = [r[0] for r in cur.fetchall()]

    data = {
        "id": clinic_id,
        "name": name,
        "type": type_org,
        "address": address,
        "district_id": district_id,
        "source_id": source_id,
        "status_id": status_id,
        "date_checked": str(date_checked),
        "phones": phones,
        "websites": websites,
        "emails": emails,
        "hours": hours,
        "comments": comments
    }

    cur.execute("""
        INSERT INTO clinics_json (clinic_id, data)
        VALUES (%s, %s)
        ON CONFLICT (clinic_id) DO UPDATE SET data = EXCLUDED.data;
    """, (clinic_id, json.dumps(data)))

conn.commit()

print("\n=== ГОТОВО: Полная очистка и пересборка завершена ===")

cur.close()
conn.close()

import psycopg2
import json

conn = psycopg2.connect(
    dbname="semanticdb",
    user="postgres",
    password="123",
    host="localhost",
    port="5432"
)

cur = conn.cursor()

# Берём все клиники
cur.execute("SELECT id, name, type, address, district_id, source_id, status_id, date_checked FROM clinics;")
clinics = cur.fetchall()

def fetch_list(table, clinic_id):
    cur.execute(f"SELECT * FROM {table} WHERE clinic_id = %s;", (clinic_id,))
    rows = cur.fetchall()
    return rows

for (clinic_id, name, type_org, address, district_id, source_id, status_id, date_checked) in clinics:

    # телефоны
    cur.execute("SELECT phone FROM clinic_phones WHERE clinic_id = %s;", (clinic_id,))
    phones = [r[0] for r in cur.fetchall()]

    # сайты
    cur.execute("SELECT website FROM clinic_websites WHERE clinic_id = %s;", (clinic_id,))
    websites = [r[0] for r in cur.fetchall()]

    # email
    cur.execute("SELECT email FROM clinic_emails WHERE clinic_id = %s;", (clinic_id,))
    emails = [r[0] for r in cur.fetchall()]

    # часы работы
    cur.execute("SELECT hours FROM clinic_hours WHERE clinic_id = %s;", (clinic_id,))
    hours = [r[0] for r in cur.fetchall()]

    # комментарии
    cur.execute("SELECT comment FROM clinic_comments WHERE clinic_id = %s;", (clinic_id,))
    comments = [r[0] for r in cur.fetchall()]

    # собираем JSON
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

    print("JSON собран для клиники:", clinic_id)

conn.commit()
cur.close()
conn.close()

print("Все JSON успешно собраны!")

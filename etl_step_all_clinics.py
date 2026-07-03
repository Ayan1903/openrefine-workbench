import psycopg2

conn = psycopg2.connect(
    dbname="semanticdb",
    user="postgres",
    password="123",
    host="localhost",
    port="5432"
)

cur = conn.cursor()

# Берём все строки из clinics_raw
cur.execute("SELECT * FROM clinics_raw;")
rows = cur.fetchall()

def get_id(table, value):
    cur.execute(f"SELECT id FROM {table} WHERE name = %s;", (value,))
    result = cur.fetchone()
    return result[0] if result else None

for row in rows:
    (
        id,
        name,
        type_org,
        address,
        phone1,
        phone2,
        website,
        email,
        working_hours,
        district,
        source,
        date_checked,
        status,
        comment
    ) = row

    district_id = get_id("districts", district)
    source_id = get_id("sources", source)
    status_id = get_id("statuses", status)

    cur.execute("""
        INSERT INTO clinics (id, name, type, address, district_id, source_id, status_id, date_checked)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (id) DO NOTHING;
    """, (id, name, type_org, address, district_id, source_id, status_id, date_checked))

    print(f"Перенесена клиника ID {id}: {name}")

conn.commit()

print("Все клиники успешно перенесены!")

cur.close()
conn.close()

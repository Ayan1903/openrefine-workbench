import psycopg2

conn = psycopg2.connect(
    dbname="semanticdb",
    user="postgres",
    password="123",
    host="localhost",
    port="5432"
)

cur = conn.cursor()

# 1. Берём первую строку из clinics_raw
cur.execute("SELECT * FROM clinics_raw LIMIT 1;")
row = cur.fetchone()

print("Первая строка из clinics_raw:")
print(row)

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

# 2. Получаем ID справочников
def get_id(table, value):
    cur.execute(f"SELECT id FROM {table} WHERE name = %s;", (value,))
    result = cur.fetchone()
    print(f"{table}: {value} -> {result}")
    return result[0] if result else None

district_id = get_id("districts", district)
source_id = get_id("sources", source)
status_id = get_id("statuses", status)

print("IDs:", district_id, source_id, status_id)

# 3. Вставляем в clinics
cur.execute("""
    INSERT INTO clinics (id, name, type, address, district_id, source_id, status_id, date_checked)
    VALUES (%s, %s, %s, %s, %s, %s, %s, %s);
""", (id, name, type_org, address, district_id, source_id, status_id, date_checked))

conn.commit()

print("Первая строка успешно перенесена!")

cur.close()
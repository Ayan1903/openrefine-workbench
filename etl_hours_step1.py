import psycopg2

conn = psycopg2.connect(
    dbname="semanticdb",
    user="postgres",
    password="123",
    host="localhost",
    port="5432"
)

cur = conn.cursor()

# Берём первую строку
cur.execute("SELECT id, working_hours FROM clinics_raw LIMIT 1;")
clinic_id, hours = cur.fetchone()

print("clinic_id:", clinic_id)
print("hours:", hours)

def insert_hours(clinic_id, hours):
    if hours is None or hours.strip() == "":
        return
    cur.execute("""
        INSERT INTO clinic_hours (clinic_id, hours)
        VALUES (%s, %s);
    """, (clinic_id, hours))
    print("Добавлены часы работы:", hours)

insert_hours(clinic_id, hours)

conn.commit()

print("Часы работы успешно перенесены!")

cur.close()
conn.close()

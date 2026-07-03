import psycopg2

conn = psycopg2.connect(
    dbname="semanticdb",
    user="postgres",
    password="123",
    host="localhost",
    port="5432"
)

cur = conn.cursor()

cur.execute("SELECT id, working_hours FROM clinics_raw;")
rows = cur.fetchall()

def insert_hours(clinic_id, hours):
    if hours is None or hours.strip() == "":
        return
    cur.execute("""
        INSERT INTO clinic_hours (clinic_id, hours)
        VALUES (%s, %s);
    """, (clinic_id, hours))
    print("Добавлены часы:", clinic_id, hours)

for clinic_id, hours in rows:
    insert_hours(clinic_id, hours)

conn.commit()

print("Все часы работы успешно перенесены!")

cur.close()
conn.close()

import psycopg2

conn = psycopg2.connect(
    dbname="semanticdb",
    user="postgres",
    password="123",
    host="localhost",
    port="5432"
)

cur = conn.cursor()

cur.execute("SELECT id, email FROM clinics_raw;")
rows = cur.fetchall()

def insert_email(clinic_id, email):
    if email is None or email.strip() == "":
        return
    cur.execute("""
        INSERT INTO clinic_emails (clinic_id, email)
        VALUES (%s, %s);
    """, (clinic_id, email))
    print("Добавлен email:", clinic_id, email)

for clinic_id, email in rows:
    insert_email(clinic_id, email)

conn.commit()

print("Все email успешно перенесены!")

cur.close()
conn.close()

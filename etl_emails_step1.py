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
cur.execute("SELECT id, email FROM clinics_raw LIMIT 1;")
clinic_id, email = cur.fetchone()

print("clinic_id:", clinic_id)
print("email:", email)

def insert_email(clinic_id, email):
    if email is None or email == "":
        return
    cur.execute("""
        INSERT INTO clinic_emails (clinic_id, email)
        VALUES (%s, %s);
    """, (clinic_id, email))
    print("Добавлен email:", email)

insert_email(clinic_id, email)

conn.commit()

print("Email успешно перенесён!")

cur.close()
conn.close()

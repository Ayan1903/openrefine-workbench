import psycopg2

conn = psycopg2.connect(
    dbname="semanticdb",
    user="postgres",
    password="123",
    host="localhost",
    port="5432"
)

cur = conn.cursor()

cur.execute("SELECT id, phone1, phone2 FROM clinics_raw;")
rows = cur.fetchall()

def insert_phone(clinic_id, phone):
    if phone is None or phone.strip() == "":
        return
    cur.execute("""
        INSERT INTO clinic_phones (clinic_id, phone)
        VALUES (%s, %s);
    """, (clinic_id, phone))
    print("Добавлен телефон:", clinic_id, phone)

for clinic_id, phone1, phone2 in rows:
    insert_phone(clinic_id, phone1)
    insert_phone(clinic_id, phone2)

conn.commit()

print("Все телефоны успешно перенесены!")

cur.close()
conn.close()

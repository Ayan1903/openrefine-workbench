import psycopg2

conn = psycopg2.connect(
    dbname="semanticdb",
    user="postgres",
    password="123",
    host="localhost",
    port="5432"
)

cur = conn.cursor()

# Берём первую строку из clinics_raw
cur.execute("SELECT id, phone1, phone2 FROM clinics_raw LIMIT 1;")
clinic_id, phone1, phone2 = cur.fetchone()

print("Клиника ID:", clinic_id)
print("phone1:", phone1)
print("phone2:", phone2)

def insert_phone(clinic_id, phone):
    if phone is None or phone == "":
        return
    cur.execute("""
        INSERT INTO clinic_phones (clinic_id, phone)
        VALUES (%s, %s);
    """, (clinic_id, phone))
    print("Добавлен телефон:", phone)

insert_phone(clinic_id, phone1)
insert_phone(clinic_id, phone2)

conn.commit()

print("Телефоны успешно перенесены!")

cur.close()
conn.close()

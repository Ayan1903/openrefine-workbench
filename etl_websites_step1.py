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
cur.execute("SELECT id, website FROM clinics_raw LIMIT 1;")
clinic_id, website = cur.fetchone()

print("clinic_id:", clinic_id)
print("website:", website)

def insert_website(clinic_id, website):
    if website is None or website == "":
        return
    cur.execute("""
        INSERT INTO clinic_websites (clinic_id, website)
        VALUES (%s, %s);
    """, (clinic_id, website))
    print("Добавлен сайт:", website)

insert_website(clinic_id, website)

conn.commit()

print("Сайт успешно перенесён!")

cur.close()
conn.close()

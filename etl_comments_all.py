import psycopg2

conn = psycopg2.connect(
    dbname="semanticdb",
    user="postgres",
    password="123",
    host="localhost",
    port="5432"
)

cur = conn.cursor()

cur.execute("SELECT id, comment FROM clinics_raw;")
rows = cur.fetchall()

def insert_comment(clinic_id, comment):
    if comment is None or comment.strip() == "":
        return
    cur.execute("""
        INSERT INTO clinic_comments (clinic_id, comment)
        VALUES (%s, %s);
    """, (clinic_id, comment))
    print("Добавлен комментарий:", clinic_id, comment)

for clinic_id, comment_raw in rows:
    if comment_raw:
        comments = [c.strip() for c in comment_raw.split("|")]
        for c in comments:
            insert_comment(clinic_id, c)

conn.commit()

print("Все комментарии успешно перенесены!")

cur.close()
conn.close()

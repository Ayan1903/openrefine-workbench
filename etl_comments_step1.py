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
cur.execute("SELECT id, comment FROM clinics_raw LIMIT 1;")
clinic_id, comment_raw = cur.fetchone()

print("clinic_id:", clinic_id)
print("raw comment:", comment_raw)

def insert_comment(clinic_id, comment):
    if comment is None or comment.strip() == "":
        return
    cur.execute("""
        INSERT INTO clinic_comments (clinic_id, comment)
        VALUES (%s, %s);
    """, (clinic_id, comment))
    print("Добавлен комментарий:", comment)

# Разбиваем строку по |
if comment_raw:
    comments = [c.strip() for c in comment_raw.split("|")]
    for c in comments:
        insert_comment(clinic_id, c)

conn.commit()

print("Комментарии успешно перенесены!")

cur.close()
conn.close()

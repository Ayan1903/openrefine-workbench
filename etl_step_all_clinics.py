import json
from datetime import datetime
from app.db.session import SessionLocal
from app.db.models import Clinic

if __name__ == "__main__":
    db = SessionLocal()

    # Загружаем данные из JSON
    with open("data/clinics.json", "r", encoding="utf-8") as f:
        rows = json.load(f)

    for row in rows:
        clinic = Clinic(
            id=row["id"],
            name=row["name"],
            type=row["type_org"],
            address=row["address"],
            district_id=row["district"],
            source_id=row["source"],
            status_id=row["status"],
            date_checked=datetime.strptime(row["date_checked"], "%Y-%m-%d")
        )

        db.add(clinic)
        print(f"Перенесена клиника ID {row['id']}: {row['name']}")

    db.commit()
    db.close()

    print("Все клиники успешно перенесены!")

📘 semanticdb‑etl
ETL‑конвейер для нормализации данных клиник и сборки финального JSON‑слоя для API, админки и фронтенда.

🔍 Описание проекта
Этот проект предназначен для:

извлечения данных из сырых таблиц

нормализации данных по сущностям (телефоны, сайты, часы, комментарии, e‑mail)

сборки единого JSON‑объекта для каждой клиники

сохранения результата в таблицу clinics_json

автоматической пересборки данных через единый скрипт

Проект построен по принципам профессионального ETL:
чистые таблицы → нормализация → агрегация → JSON‑слой.

🧱 Структура проекта
Код
project1/
│
├── etl/
│   ├── etl_phones_all.py
│   ├── etl_websites_all.py
│   ├── etl_emails_all.py
│   ├── etl_comments_all.py
│   ├── etl_hours_all.py
│   ├── etl_step_all_clinics.py
│   └── clean_and_rebuild.py
│
├── sql/
│   ├── create_tables.sql
│   └── drop_tables.sql
│
├── db_test.py
├── etl_json_all.py
└── README.md
⚙️ Основной процесс ETL
1) Сырые данные
Сначала данные попадают в таблицу clinics_raw.

2) Нормализация
Каждая сущность переносится в свою таблицу:

clinic_phones

clinic_websites

clinic_emails

clinic_hours

clinic_comments

clinics (основная таблица)

3) Сборка JSON‑слоя
Скрипт clean_and_rebuild.py:

очищает старые данные

собирает все сущности по clinic_id

формирует единый JSON

сохраняет в таблицу clinics_json

🧩 Пример JSON‑объекта
json
{
  "id": 12,
  "name": "Май Дент",
  "type": "Стоматология",
  "address": "ул. Ленина, 15",
  "district_id": 3,
  "source_id": 1,
  "status_id": 2,
  "date_checked": "2024-06-15",
  "phones": ["8-923-456-78-90"],
  "websites": ["maydent.ru"],
  "emails": ["info@maydent.ru"],
  "hours": ["Пн-Пт 09:00–18:00"],
  "comments": ["Хорошие отзывы"]
}
🚀 Как пересобрать данные
Выполнить:

Код
python clean_and_rebuild.py
После выполнения таблица clinics_json будет полностью обновлена.

🗄 Подключение к PostgreSQL
Файл .env:

Код
DB_NAME=semanticdb
DB_USER=postgres
DB_PASSWORD=123
DB_HOST=localhost
DB_PORT=5432
📌 Планы развития
добавить API на FastAPI

добавить поиск клиник

добавить фильтры по районам

добавить админку на Django

добавить фронтенд

🧑‍💻 Автор
Аян — разработчик, создающий собственную платформу для медицинских клиник.

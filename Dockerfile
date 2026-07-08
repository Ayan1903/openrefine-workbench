FROM python:3.10-slim
WORKDIR /app
COPY requirements.txt /app/requirements.txt
RUN if [ -f requirements.txt ]; then pip install --no-cache-dir -r requirements.txt; fi
COPY . /app
EXPOSE 8000
CMD ["uvicorn", "your_app_module:app", "--host", "0.0.0.0", "--port", "8000"]



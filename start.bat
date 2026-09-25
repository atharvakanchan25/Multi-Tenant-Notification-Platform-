@echo off

if not exist .env (
    echo [ERROR] .env file not found. Copy .env.example to .env and fill in the values.
    exit /b 1
)

docker compose up --build

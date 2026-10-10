@echo off
REM 启动 MT5 本地服务（使用项目 venv 的 Python）
set PY=C:\Users\笨猫\Desktop\MT4-Ai\venv\Scripts\python.exe
cd /d "%~dp0"
"%PY%" -m uvicorn server:app --host 127.0.0.1 --port 8765
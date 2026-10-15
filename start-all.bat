@echo off
setlocal enabledelayedexpansion
title Quanta 一键启动

set "ROOT=%~dp0"
set "PY=%USERPROFILE%\Desktop\MT4-Ai\venv\Scripts\python.exe"
set "MVN=E:\maven\apache-maven-3.6.3-bin\apache-maven-3.6.3\bin\mvn.cmd"
set "JAVA=E:\BMW\jdk-21.0.1\bin\java.exe"
set "JAR=%ROOT%quanta-backend\target\quanta-backend-1.0.0.jar"
set "BRIDGE_PORT=8765"
set "APP_PORT=8080"
rem 自动交易唤醒：命中后自动唤醒 AI 研判下单（模拟盘观察用；改 false 即恢复默认关）
set "QUANTA_AUTO_WAKE_ENABLED=true"
rem 演练模式：允许把未过门禁的高频策略置 DRILL，仅供验证 AI 能否真实下单（禁止实盘资金）
set "QUANTA_DRILL_ENABLED=true"

echo.
echo ============================================================
echo    Quanta 一键启动    桥接 %BRIDGE_PORT%   后端 %APP_PORT%
echo    （用法：双击本文件；要强制重新打包用  start-all.bat rebuild）
echo    自动交易唤醒 = %QUANTA_AUTO_WAKE_ENABLED%   true=AI 命中后自动研判下单
echo ============================================================
echo.

rem ================= 前置检查 =================
if not exist "%PY%"   ( echo [错误] 找不到 Python 解释器: %PY%  & goto :fail )
if not exist "%JAVA%" ( echo [错误] 找不到 Java: %JAVA% & goto :fail )

call :isup 3307
if errorlevel 1 echo [警告] 3307 端口未监听 —— MySQL 8.4 可能没启动，请用管理员执行: net start MySQL84

tasklist /fi "imagename eq terminal64.exe" | find /i "terminal64.exe" >nul
if errorlevel 1 echo [警告] 未检测到 MT5 终端 terminal64.exe —— 桥接登录可能失败，请先打开 MT5

rem ================= 1) 启动 MT5 桥接 =================
call :isup %BRIDGE_PORT%
if not errorlevel 1 goto :bridge_ready
echo [1/2] 启动 MT5 桥接（%BRIDGE_PORT%）...
start "Quanta-MT5-Bridge" /D "%ROOT%mt5_service" cmd /k %PY% -m uvicorn server:app --host 127.0.0.1 --port %BRIDGE_PORT%
call :waitport %BRIDGE_PORT% 60
if errorlevel 1 echo [错误] 桥接未在 60s 内就绪，请看 "Quanta-MT5-Bridge" 窗口的日志 & goto :fail
echo       桥接就绪: http://127.0.0.1:%BRIDGE_PORT%
goto :build
:bridge_ready
echo [1/2] 桥接已在运行，跳过启动。

rem ================= 打包后端 =================
:build
if /i "%~1"=="rebuild" goto :do_build
if not exist "%JAR%" goto :do_build
goto :start_app
:do_build
echo       打包后端（首次较慢，-DskipTests）...
call "%MVN%" -q -pl quanta-backend -am package "-DskipTests"
if errorlevel 1 echo [错误] 打包失败，请看上面的 Maven 输出 & goto :fail

rem ================= 2) 启动后端 =================
:start_app
call :isup %APP_PORT%
if not errorlevel 1 goto :app_ready
echo [2/2] 启动后端（%APP_PORT%）...
start "Quanta-Backend" /D "%ROOT%" cmd /k %JAVA% -jar "%JAR%"
call :waitport %APP_PORT% 90
if errorlevel 1 echo [错误] 后端未在 90s 内就绪，请看 "Quanta-Backend" 窗口的日志 & goto :fail
echo       后端就绪: http://127.0.0.1:%APP_PORT%
goto :verify
:app_ready
echo [2/2] 后端已在运行，跳过启动。

rem ================= 验证 =================
:verify
echo.
echo ---- 健康检查 ----
curl -s http://127.0.0.1:%APP_PORT%/actuator/health
echo.
echo ---- 账户资金 ----
curl -s http://127.0.0.1:%APP_PORT%/api/mt5/account
echo.
echo.
echo 启动完成。关闭本窗口不影响服务；停止服务请关掉 "Quanta-MT5-Bridge" / "Quanta-Backend" 两个窗口（或各按 Ctrl+C）。
pause
exit /b 0

:fail
echo.
echo 启动中止，请按上面提示排查后重试。
pause
exit /b 1

rem ================= 子过程 =================
:isup
powershell -NoProfile -Command "$c=New-Object Net.Sockets.TcpClient; try{ $c.Connect('127.0.0.1',%1); $c.Close(); exit 0 }catch{ exit 1 }"
exit /b %errorlevel%

:waitport
powershell -NoProfile -Command "for($i=0;$i -lt %2;$i++){ $c=New-Object Net.Sockets.TcpClient; try{ $c.Connect('127.0.0.1',%1); $c.Close(); exit 0 }catch{ Start-Sleep -Milliseconds 500 } }; exit 1"
exit /b %errorlevel%

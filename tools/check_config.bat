@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo ============================================
echo    doc-agent 配置自检
echo ============================================
echo.

rem ---------------------------------------------------------------
rem  自动找一个"真正能跑"的 Python
rem
rem  为什么需要这段：很多 Windows 上 `python` 命令指向的是
rem  C:\Users\xxx\AppData\Local\Microsoft\WindowsApps\python.exe
rem  那是 Microsoft Store 的占位转发器（0 字节的程序），
rem  运行它会尝试打开商店、或者什么都不做就退出 —— 表现为
rem  「敲了命令一行输出都没有」。所以这里不信任 PATH 里的 python。
rem ---------------------------------------------------------------

set "PY="

rem 1) WorkBuddy 自带的 Python（最稳，版本固定）
if exist "%USERPROFILE%\.workbuddy\binaries\python\versions\3.13.12\python.exe" (
    set "PY=%USERPROFILE%\.workbuddy\binaries\python\versions\3.13.12\python.exe"
)

rem 2) Anaconda 常见安装位置
if not defined PY if exist "%USERPROFILE%\anaconda3\python.exe"         set "PY=%USERPROFILE%\anaconda3\python.exe"
if not defined PY if exist "C:\ProgramData\anaconda3\python.exe"        set "PY=C:\ProgramData\anaconda3\python.exe"
if not defined PY if exist "%USERPROFILE%\AppData\Local\conda\python.exe" set "PY=%USERPROFILE%\AppData\Local\conda\python.exe"
if not defined PY if exist "%USERPROFILE%\Miniconda3\python.exe"        set "PY=%USERPROFILE%\Miniconda3\python.exe"

rem 3) 官方安装器装的 Python（排除 Store 占位那个）
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python313\python.exe"
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"

if not defined PY (
    echo [失败] 没找到可用的 Python
    echo.
    echo 你的 python 命令大概率指向 Microsoft Store 的占位程序
    echo ^(C:\Users\...\AppData\Local\Microsoft\WindowsApps\python.exe^)，它不是真的 Python。
    echo.
    echo 解决办法三选一：
    echo   1. 用 PowerShell 版脚本（Windows 自带，不需要 Python）：
    echo        powershell -ExecutionPolicy Bypass -File tools\check-config.ps1
    echo   2. 去 python.org 下载安装 Python，安装时勾选 "Add to PATH"
    echo   3. 或者手动把上面的地址直接填进 IDEA 的环境变量
    echo.
    pause
    exit /b 1
)

echo 使用的 Python: %PY%
echo.

"%PY%" tools\check_config.py
set "RC=%ERRORLEVEL%"

echo.
if not "%RC%"=="0" (
    echo 脚本返回码 %RC% ^(非 0 表示有失败项^)
)
pause

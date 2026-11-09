@echo off
chcp 65001 >nul
cd /d "%~dp0"
where node >nul 2>nul
if %errorlevel%==0 (
  echo 使用 Node 启动...
  node serve.js
) else (
  where python >nul 2>nul
  if %errorlevel%==0 (
    echo 未找到 Node，使用 Python 启动...
    python -m http.server 8765 --bind 127.0.0.1
  ) else (
    echo 需要安装 Node.js 或 Python 之一才能启动本地站点。
    pause
  )
)

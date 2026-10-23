@echo off
REM ===================================================================
REM  run_site_health_check.bat
REM  Launcher for site_health_check.py on Windows.
REM  Keep this .bat in the SAME folder as site_health_check.py.
REM ===================================================================

setlocal
cd /d "%~dp0"

echo ===================================================================
echo   Site Health ^& Resilience Checker  (for sites you own)
echo ===================================================================
echo.

REM --- Find a Python launcher ---------------------------------------
set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY (
    where python >nul 2>nul && set "PY=python"
)
if not defined PY (
    echo [ERROR] Python 3 was not found on this system.
    echo         Install it from https://www.python.org/downloads/
    echo         and make sure "Add Python to PATH" is checked.
    echo.
    pause
    exit /b 1
)

REM --- Make sure the script is here ---------------------------------
if not exist "%~dp0site_health_check.py" (
    echo [ERROR] site_health_check.py was not found next to this .bat file.
    echo         Put both files in the same folder and try again.
    echo.
    pause
    exit /b 1
)

REM --- Optional: set a default site to run hands-off on double-click. ---
REM  Leave SITE_URL blank to be prompted for the URL interactively.
REM  If set, the script runs with no prompts (--yes) against this URL.
set "SITE_URL=https://orthoautomate.online"

REM --- Run it. Any extra args you pass to the .bat are forwarded. ----
if "%~1"=="" (
    if defined SITE_URL (
        %PY% "%~dp0site_health_check.py" --url "%SITE_URL%" --yes
    ) else (
        %PY% "%~dp0site_health_check.py"
    )
) else (
    %PY% "%~dp0site_health_check.py" %*
)

echo.
echo ===================================================================
pause
endlocal

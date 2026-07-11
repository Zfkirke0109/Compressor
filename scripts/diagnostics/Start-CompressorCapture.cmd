@echo off
setlocal EnableExtensions
rem One-click entry point for the autonomous Galaxy Compressor Secure Folder batch capture.
rem It launches the PowerShell supervisor, which records the whole batch to disk and parses it
rem afterwards WITHOUT any Claude / Codex / ChatGPT session needing to stay open.

set "SCRIPT_DIR=%~dp0"
set "SUPERVISOR=%SCRIPT_DIR%Start-CompressorBatchCapture.ps1"

echo ================================================================
echo   Galaxy Compressor - autonomous Secure Folder batch capture
echo ================================================================
echo.
echo   1. Enable Wireless Debugging on the S23 Ultra.
echo   2. Wait until this window prints:  CAPTURE ARMED
echo   3. Start the batch inside Galaxy Compressor (Secure Folder).
echo   4. Close Claude, Codex, ChatGPT and any AI browser tabs.
echo      Capture, reconnection, parsing and the manifest all keep
echo      running on this PC with no AI session and no screenshots.
echo   5. Come back later: results land under validation\captures\.
echo.

rem Prefer PowerShell 7 (pwsh) when present, otherwise use Windows PowerShell 5.1.
where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (set "PS=pwsh") else (set "PS=powershell")

"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%SUPERVISOR%" %*
set "RC=%ERRORLEVEL%"

echo.
echo ----------------------------------------------------------------
if "%RC%"=="0" (
  echo CAPTURE COMPLETE. See manifest.json in the validation folder above.
) else (
  echo CAPTURE PARTIAL / FAILED ^(exit %RC%^). Open manifest.json for the reason.
)
echo ----------------------------------------------------------------
echo.
pause
endlocal

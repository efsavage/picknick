@echo off
REM Build (incremental) and launch Picknick. Run from anywhere by double-clicking
REM or:  .\run.cmd
cd /d "%~dp0"
call "%~dp0mvnw.cmd" compile javafx:run

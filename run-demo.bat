@echo off
cd /d "%~dp0"
java -Duser.timezone=UTC -jar release\campus-activity.jar
pause

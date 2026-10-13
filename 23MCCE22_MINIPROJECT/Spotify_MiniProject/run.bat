@echo off
rem usage: run.bat build | web | terminal | backend
if "%1"=="build" (
  if not exist server\out mkdir server\out
  javac -d server\out server\src\simplestream\*.java client\src\simplestream\*.java && echo Build OK
) else if "%1"=="web" (
  rem starts the 3 backend nodes in a second window, then the website here
  start "SimpleStream Backend" java -cp server\out simplestream.MusicServer
  timeout /t 3 /nobreak >nul
  java -cp server\out simplestream.SpotifyWeb
) else if "%1"=="terminal" (
  java -cp server\out simplestream.SpotifyTerminal
) else if "%1"=="backend" (
  java -cp server\out simplestream.MusicServer
) else (
  echo usage: run.bat build^|web^|terminal^|backend
)
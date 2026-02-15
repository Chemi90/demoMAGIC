@echo off
setlocal EnableExtensions

cd /d "%~dp0"

echo [INFO] Arrancando frontend en puerto 5500...
start "DemoMagic Front" cmd /k "cd /d ""%~dp0front"" && python -m http.server 5500"

echo [INFO] Arrancando backend con Spring Boot...
start "DemoMagic Back" cmd /k "cd /d ""%~dp0back"" && mvn spring-boot:run"

echo [INFO] Servicios iniciados.
echo [INFO] Front: http://localhost:5500/
echo [INFO] Back:  http://localhost:8080/
echo [INFO] Abriendo frontend en el navegador...
timeout /t 2 /nobreak >nul
start "" "http://localhost:5500/"
echo [INFO] Para detener, cierra las ventanas "DemoMagic Front" y "DemoMagic Back".

exit /b 0

@echo off
echo 🔄 Forçando sincronização de permissões do Shizuku
echo ===============================================

echo.
echo 📱 Parando aplicativos...
adb shell am force-stop com.splitscreen.inputbridge
adb shell am force-stop moe.shizuku.privileged.api

echo.
echo 🔧 Reiniciando Shizuku...
adb shell am start -n moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity >nul 2>&1
timeout /t 3 /nobreak >nul

echo.
echo 🔧 Reiniciando SplitScreen Input Bridge...
adb shell am start -n com.splitscreen.inputbridge/.MainActivity >nul 2>&1
timeout /t 2 /nobreak >nul

echo.
echo 🔐 Verificando permissões...
echo ------------------------------
adb shell dumpsys package com.splitscreen.inputbridge | findstr -A 5 "runtime permissions"

echo.
echo 📊 Verificando serviços...
echo ------------------------------
adb shell dumpsys activity services | findstr -i "inputbridge" | findstr -m 5 ".*"

echo.
echo ✅ Processo de sincronização concluído!
echo.
echo Agora verifique o aplicativo. Se ainda mostrar que precisa conceder permissão:
echo 1. Abra o app Shizuku manualmente
echo 2. Toque em 'Conceder permissão' para SplitScreen Input Bridge
echo 3. Volte ao aplicativo e verifique se o status mudou
pause
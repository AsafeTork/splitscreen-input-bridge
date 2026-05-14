@echo off
echo 🔧 Script de correção do Shizuku via ADB
echo ======================================

echo.
echo 📱 Verificando dispositivo conectado...
adb devices | findstr -v "List" | findstr -v "^$"
if %errorlevel% neq 0 (
    echo ❌ Nenhum dispositivo conectado!
    echo Por favor, conecte seu dispositivo Android via USB e habilite a depuração USB.
    pause
    exit /b 1
)

echo.
echo ✅ Dispositivo conectado!

echo.
echo 🔧 Concedendo permissão WRITE_SECURE_SETTINGS...
adb shell pm grant com.splitscreen.inputbridge android.permission.WRITE_SECURE_SETTINGS
if %errorlevel% equ 0 (
    echo ✅ Permissão concedida com sucesso!
) else (
    echo ⚠️ Falha ao conceder permissão via ADB
)

echo.
echo 🔧 Forçando reinicialização do Shizuku...
adb shell am force-stop dev.rikka.shizuku
adb shell am start -n dev.rikka.shizuku/.MainActivity
if %errorlevel% equ 0 (
    echo ✅ Shizuku reiniciado com sucesso!
) else (
    echo ⚠️ Falha ao reiniciar Shizuku
)

echo.
echo 🔧 Verificando status do Shizuku...
adb shell dumpsys package dev.rikka.shizuku | findstr "enabled\|version\|granted"

echo.
echo 🔧 Verificando permissões do aplicativo...
adb shell dumpsys package com.splitscreen.inputbridge | findstr "requested permissions" -A 20

echo.
echo ✅ Processo de correção concluído!
echo.
echo Se o problema persistir:
echo 1. Reinicie o dispositivo
echo 2. Revogue e conceda novamente a permissão no app Shizuku
echo 3. Verifique se o Shizuku está atualizado
echo 4. Tente reinstalar o aplicativo
pause
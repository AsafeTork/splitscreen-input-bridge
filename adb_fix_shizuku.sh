#!/bin/bash

# Script de correção do Shizuku via ADB
# Execute este script com o dispositivo conectado via USB

echo "🔧 Script de correção do Shizuku via ADB"
echo "======================================="

echo
echo "📱 Verificando dispositivo conectado..."
adb devices | grep -v "List" | grep -v "^$"
if [ $(adb devices | grep -v "List" | grep -v "^$" | wc -l) -eq 0 ]; then
    echo "❌ Nenhum dispositivo conectado!"
    echo "Por favor, conecte seu dispositivo Android via USB e habilite a depuração USB."
    exit 1
fi

echo
echo "✅ Dispositivo conectado!"

echo
echo "🔧 Concedendo permissão WRITE_SECURE_SETTINGS..."
adb shell pm grant com.splitscreen.inputbridge android.permission.WRITE_SECURE_SETTINGS
if [ $? -eq 0 ]; then
    echo "✅ Permissão concedida com sucesso!"
else
    echo "⚠️ Falha ao conceder permissão via ADB"
fi

echo
echo "🔧 Forçando reinicialização do Shizuku..."
adb shell am force-stop dev.rikka.shizuku
adb shell am start -n dev.rikka.shizuku/.MainActivity
if [ $? -eq 0 ]; then
    echo "✅ Shizuku reiniciado com sucesso!"
else
    echo "⚠️ Falha ao reiniciar Shizuku"
fi

echo
echo "🔧 Verificando status do Shizuku..."
adb shell dumpsys package dev.rikka.shizuku | grep -E "enabled|version|granted"

echo
echo "🔧 Verificando permissões do aplicativo..."
adb shell dumpsys package com.splitscreen.inputbridge | grep -A 20 "requested permissions"

echo
echo "✅ Processo de correção concluído!"
echo
echo "Se o problema persistir:"
echo "1. Reinicie o dispositivo"
echo "2. Revogue e conceda novamente a permissão no app Shizuku"
echo "3. Verifique se o Shizuku está atualizado"
echo "4. Tente reinstalar o aplicativo"
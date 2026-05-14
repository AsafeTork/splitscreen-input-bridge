#!/bin/bash

# Script de diagnóstico detalhado do Shizuku
# Execute este script com o dispositivo conectado via ADB

echo "🔍 Diagnóstico Detalhado do Shizuku"
echo "==================================="

# Verificar se o dispositivo está conectado
echo "📱 Verificando dispositivo conectado..."
adb devices | grep -v "List" | grep -v "^$"
if [ $(adb devices | grep -v "List" | grep -v "^$" | wc -l) -eq 0 ]; then
    echo "❌ Nenhum dispositivo conectado!"
    echo "Por favor, conecte seu dispositivo Android via USB e habilite a depuração USB."
    exit 1
fi

echo
echo "✅ Dispositivo conectado!"

# Verificar status do Shizuku
echo
echo "🔧 Verificando status do Shizuku..."
echo "-----------------------------------"
adb shell dumpsys package dev.rikka.shizuku | grep -E "enabled|version|granted"

# Verificar permissões do aplicativo
echo
echo "🔓 Verificando permissões do aplicativo..."
echo "------------------------------------------"
adb shell dumpsys package com.splitscreen.inputbridge | grep -A 20 "requested permissions"

# Verificar se o Shizuku está rodando
echo
echo "🏃 Verificando se Shizuku está rodando..."
echo "-----------------------------------------"
adb shell ps | grep shizuku

# Verificar status das permissões específicas
echo
echo "🔐 Verificando permissões específicas..."
echo "----------------------------------------"
adb shell pm list permissions -d -g | grep -A 5 -B 5 "shizuku\|Shizuku"

# Verificar se o serviço está ativo
echo
echo "サービ Verificando serviços ativos..."
echo "-------------------------------------"
adb shell dumpsys activity services | grep -i "shizuku"

# Verificar logs do Shizuku
echo
echo "📋 Verificando logs do Shizuku..."
echo "---------------------------------"
adb logcat -T 100 | grep -i "shizuku\|Shizuku" | tail -10

echo
echo "✅ Diagnóstico concluído!"
echo
echo "Se o problema persistir, tente:"
echo "1. Reiniciar o app Shizuku"
echo "2. Revogar e conceder novamente a permissão"
echo "3. Reiniciar o dispositivo"
echo "4. Verificar se o Shizuku está atualizado"
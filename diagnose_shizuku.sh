#!/bin/bash

# Script para diagnosticar problemas com permissões do Shizuku

echo "🔍 Diagnosticando problemas com permissões do Shizuku..."
echo "===================================================="

echo
echo "📱 Verificando status do Shizuku:"
echo "--------------------------------"
adb shell dumpsys package dev.rikka.shizuku | grep -E "enabled|version"

echo
echo "🔧 Verificando permissões do aplicativo:"
echo "----------------------------------------"
adb shell dumpsys package com.splitscreen.inputbridge | grep -A 10 "requested permissions"

echo
echo "⚙️ Verificando status do serviço:"
echo "---------------------------------"
adb shell dumpsys activity services | grep -i "inputbridge"

echo
echo "📋 Verificando logs do aplicativo:"
echo "----------------------------------"
adb logcat -T 100 | grep -i "shizuku\|inputbridge" | tail -20

echo
echo "✅ Diagnóstico concluído!"
#!/bin/bash

# Script para forçar sincronização de permissões do Shizuku
# Use este script quando o aplicativo não reconhecer permissões concedidas

echo "🔄 Forçando sincronização de permissões do Shizuku"
echo "==============================================="

echo
echo "📱 Parando aplicativos..."
adb shell am force-stop com.splitscreen.inputbridge
adb shell am force-stop moe.shizuku.privileged.api

echo
echo "🔧 Reiniciando Shizuku..."
adb shell am start -n moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity > /dev/null 2>&1
sleep 3

echo
echo "🔧 Reiniciando SplitScreen Input Bridge..."
adb shell am start -n com.splitscreen.inputbridge/.MainActivity > /dev/null 2>&1
sleep 2

echo
echo "🔐 Verificando permissões..."
echo "------------------------------"
adb shell dumpsys package com.splitscreen.inputbridge | grep -A 5 "runtime permissions"

echo
echo "サービ Verificando serviços..."
echo "------------------------------"
adb shell dumpsys activity services | grep -i "inputbridge" | head -5

echo
echo "✅ Processo de sincronização concluído!"
echo
echo "Agora verifique o aplicativo. Se ainda mostrar que precisa conceder permissão:"
echo "1. Abra o app Shizuku manualmente"
echo "2. Toque em 'Conceder permissão' para SplitScreen Input Bridge"
echo "3. Volte ao aplicativo e verifique se o status mudou"
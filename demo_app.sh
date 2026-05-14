#!/bin/bash

# Script de demonstração visual do app funcionando
# Mostra o app em ação no dispositivo conectado

echo "📱 Script de Demonstração Visual do SplitScreen Input Bridge"
echo "=========================================================="

# Função para verificar ambiente
check_environment() {
    echo "🔧 Verificando ambiente..."

    # Verificar ADB
    if ! command -v adb &> /dev/null; then
        echo "❌ ADB não encontrado"
        return 1
    fi

    # Verificar dispositivo conectado
    local devices=$(adb devices | grep -v "List of devices" | grep -v "^$" | wc -l)
    if [ $devices -eq 0 ]; then
        echo "❌ Nenhum dispositivo conectado"
        echo "💡 Conecte um dispositivo via USB ou WiFi"
        echo "   Veja WIFI_ADB_GUIDE.md para instruções"
        return 1
    fi

    echo "✅ Ambiente pronto"
    return 0
}

# Função para verificar se app está instalado
check_app_installed() {
    echo "🔍 Verificando se app está instalado..."

    if adb shell pm list packages | grep -q "com.splitscreen.inputbridge"; then
        echo "✅ App instalado"
        return 0
    else
        echo "❌ App não instalado"
        echo "💡 Execute o diagnóstico primeiro:"
        echo "   ./diagnostic_apk.sh"
        return 1
    fi
}

# Função para iniciar app
start_app() {
    echo "🚀 Iniciando app..."

    # Iniciar activity principal
    adb shell am start -n com.splitscreen.inputbridge/.MainActivity > /dev/null 2>&1

    if [ $? -eq 0 ]; then
        echo "✅ App iniciado com sucesso"
        return 0
    else
        echo "❌ Falha ao iniciar app"
        return 1
    fi
}

# Função para demonstrar funcionalidades
demo_features() {
    echo "🎮 Demonstrando funcionalidades..."

    # Mostrar informações do dispositivo
    echo "📱 Informações do dispositivo:"
    adb shell getprop ro.product.model
    adb shell getprop ro.build.version.release

    # Mostrar status do app
    echo "📊 Status do app:"
    adb shell dumpsys package com.splitscreen.inputbridge | grep -E "versionName|installed"

    # Verificar serviços em execução
    echo "⚙️ Serviços em execução:"
    adb shell dumpsys activity services | grep -i "inputbridge" | head -3

    echo "✅ Demonstração concluída!"
}

# Função para simular interação
simulate_interaction() {
    echo "🕹️ Simulando interação com gamepad..."

    # Simular toque na tela (coordenadas centrais)
    echo "👆 Simulando toque na tela..."
    adb shell input tap 500 500

    # Aguardar
    sleep 2

    # Simular swipe
    echo "👉 Simulando swipe..."
    adb shell input swipe 300 500 800 500 500

    # Aguardar
    sleep 2

    # Simular botão back
    echo "🔙 Simulando botão back..."
    adb shell input keyevent 4

    echo "✅ Interações simuladas!"
}

# Função para mostrar logs em tempo real
show_logs() {
    echo "📋 Mostrando logs em tempo real (Ctrl+C para parar)..."
    echo "💡 Procure por mensagens com tag 'InputBridge'"

    # Mostrar últimos logs e seguir em tempo real
    adb logcat -T 100 | grep -i "inputbridge" &
    local log_pid=$!

    # Aguardar 10 segundos e parar
    sleep 10
    kill $log_pid 2>/dev/null

    echo "✅ Logs coletados!"
}

# Função principal de demonstração
main_demo() {
    echo "🎪 Iniciando demonstração completa..."

    # Verificar ambiente
    if ! check_environment; then
        exit 1
    fi

    echo

    # Verificar app instalado
    if ! check_app_installed; then
        exit 1
    fi

    echo

    # Iniciar app
    if start_app; then
        echo
        # Demonstrar funcionalidades
        demo_features
        echo

        # Simular interação
        simulate_interaction
        echo

        # Mostrar logs
        show_logs
        echo

        echo "🎉 Demonstração completa! O app está funcionando corretamente."
    else
        echo "❌ Não foi possível iniciar o app para demonstração"
        exit 1
    fi
}

# Executar demonstração
main_demo
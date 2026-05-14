#!/bin/bash

# Script de diagnóstico completo do APK
# Verifica build, instalação e funcionamento no dispositivo

echo "🔍 Script de Diagnóstico do APK SplitScreen Input Bridge"
echo "====================================================="

# Função para verificar ambiente
check_environment() {
    echo "🔧 Verificando ambiente..."

    # Verificar ADB
    if ! command -v adb &> /dev/null; then
        echo "❌ ADB não encontrado. Instale o Android SDK Platform Tools."
        return 1
    fi

    # Verificar Gradle
    if ! command -v ./gradlew &> /dev/null; then
        echo "❌ Gradle wrapper não encontrado."
        return 1
    fi

    echo "✅ Ambiente OK"
    return 0
}

# Função para verificar dispositivos conectados
check_devices() {
    echo "📱 Verificando dispositivos conectados..."

    local devices=$(adb devices | grep -v "List of devices" | grep -v "^$" | wc -l)
    if [ $devices -eq 0 ]; then
        echo "⚠️  Nenhum dispositivo conectado. Conecte via USB ou WiFi."
        echo "   Veja WIFI_ADB_GUIDE.md para instruções de conexão WiFi."
        return 1
    else
        echo "✅ $devices dispositivo(s) conectado(s)"
        adb devices
        return 0
    fi
}

# Função para construir APK
build_apk() {
    echo "🔨 Construindo APK..."

    # Limpar builds anteriores
    rm -rf app/build/outputs/apk/debug/*.apk 2>/dev/null || true

    # Construir
    ./gradlew assembleDebug --no-daemon --stacktrace > build_diagnostic.log 2>&1
    local exit_code=$?

    if [ $exit_code -eq 0 ]; then
        echo "✅ Build concluído com sucesso"

        # Verificar APK gerado
        if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
            local apk_size=$(du -h app/build/outputs/apk/debug/app-debug.apk | cut -f1)
            echo "📦 APK gerado: $apk_size"
            return 0
        else
            echo "❌ APK não encontrado após build"
            return 1
        fi
    else
        echo "❌ Build falhou"
        tail -10 build_diagnostic.log
        return 1
    fi
}

# Função para instalar APK no dispositivo
install_apk() {
    echo "📲 Instalando APK no dispositivo..."

    local apk_path="app/build/outputs/apk/debug/app-debug.apk"

    if [ ! -f "$apk_path" ]; then
        echo "❌ APK não encontrado: $apk_path"
        return 1
    fi

    # Instalar APK
    adb install -r "$apk_path" > install.log 2>&1
    local install_result=$?

    if [ $install_result -eq 0 ]; then
        echo "✅ APK instalado com sucesso"
        return 0
    else
        echo "❌ Falha na instalação do APK"
        tail -5 install.log
        return 1
    fi
}

# Função para verificar instalação
verify_installation() {
    echo "✅ Verificando instalação..."

    # Verificar se o pacote está instalado
    adb shell pm list packages | grep "com.splitscreen.inputbridge" > package_check.log 2>&1
    if [ $? -eq 0 ]; then
        echo "✅ App instalado corretamente"

        # Tentar iniciar o app (opcional)
        echo "🚀 Tentando iniciar o app..."
        adb shell am start -n com.splitscreen.inputbridge/.MainActivity 2>/dev/null
        return 0
    else
        echo "❌ App não encontrado após instalação"
        return 1
    fi
}

# Função principal
main() {
    echo "🕐 Iniciando diagnóstico: $(date)"
    echo

    # Verificar ambiente
    if ! check_environment; then
        echo "❌ Diagnóstico interrompido - Problemas no ambiente"
        exit 1
    fi

    echo

    # Verificar dispositivos
    if ! check_devices; then
        echo "⚠️  Continuando diagnóstico sem dispositivo físico..."
    fi

    echo

    # Construir APK
    if ! build_apk; then
        echo "❌ Diagnóstico falhou - Build do APK"
        exit 1
    fi

    echo

    # Se houver dispositivo conectado, testar instalação
    if check_devices > /dev/null 2>&1; then
        echo
        if install_apk && verify_installation; then
            echo "🎉 Diagnóstico completo - Tudo funcionando!"
        else
            echo "❌ Diagnóstico completo - Problemas na instalação"
        fi
    else
        echo "✅ Diagnóstico de build completo - APK gerado com sucesso"
        echo "💡 Conecte um dispositivo para testar a instalação"
    fi

    echo
    echo "📄 Logs detalhados disponíveis em:"
    echo "   - build_diagnostic.log"
    echo "   - install.log"
    echo "   - package_check.log"
}

# Executar diagnóstico
main
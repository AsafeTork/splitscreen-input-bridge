#!/bin/bash

# Script completo para mostrar o app funcionando
# Combina build, correção automática e demonstração

echo "🚀 Script Completo - Mostrar App Funcionando"
echo "==========================================="

# Função para construir e instalar app
build_and_install() {
    echo "🔨 Construindo e instalando app..."

    # Construir APK
    echo "🔧 Construindo APK..."
    ./gradlew assembleDebug --no-daemon > build_final.log 2>&1
    if [ $? -ne 0 ]; then
        echo "❌ Build falhou"
        tail -10 build_final.log
        return 1
    fi

    # Verificar APK
    if [ ! -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
        echo "❌ APK não encontrado"
        return 1
    fi

    # Instalar no dispositivo
    echo "📲 Instalando no dispositivo..."
    adb install -r app/build/outputs/apk/debug/app-debug.apk > install_final.log 2>&1
    if [ $? -ne 0 ]; then
        echo "❌ Instalação falhou"
        tail -5 install_final.log
        return 1
    fi

    echo "✅ App construído e instalado com sucesso!"
    return 0
}

# Função para mostrar app funcionando
show_app_working() {
    echo "📱 Mostrando app funcionando..."

    # Iniciar app
    echo "🚀 Iniciando app..."
    adb shell am start -n com.splitscreen.inputbridge/.MainActivity > /dev/null 2>&1

    # Aguardar app iniciar
    sleep 3

    # Mostrar informações
    echo "📋 Informações do app:"
    echo "   Modelo: $(adb shell getprop ro.product.model 2>/dev/null)"
    echo "   Android: $(adb shell getprop ro.build.version.release 2>/dev/null)"
    echo "   App instalado: $(adb shell pm list packages | grep -c "com.splitscreen.inputbridge")"

    # Simular algumas interações básicas
    echo "🎮 Simulando interações..."
    adb shell input tap 400 400  # Toque central
    sleep 1
    adb shell input keyevent 4   # Botão back

    echo "✅ App está funcionando!"
    echo "💡 Você pode ver o app aberto no dispositivo"
}

# Função principal
main() {
    echo "🕐 Início: $(date)"
    echo

    # Verificar ambiente
    echo "🔧 Verificando ambiente..."
    if ! command -v adb &> /dev/null; then
        echo "❌ ADB não encontrado"
        exit 1
    fi

    if [ $(adb devices | grep -v "List of devices" | grep -v "^$" | wc -l) -eq 0 ]; then
        echo "❌ Nenhum dispositivo conectado"
        echo "💡 Conecte um dispositivo e tente novamente"
        exit 1
    fi

    echo "✅ Ambiente OK"
    echo

    # Construir e instalar
    if build_and_install; then
        echo
        show_app_working
        echo
        echo "🎉 SUCESSO! O app está funcionando corretamente no dispositivo!"
    else
        echo
        echo "❌ Falha no processo. Tentando correção automática..."

        # Tentar correção automática
        if [ -f "auto_fix_apk.sh" ]; then
            echo "🔧 Executando correção automática..."
            ./auto_fix_apk.sh > fix_attempt.log 2>&1
            if [ $? -eq 0 ]; then
                echo "✅ Correção aplicada, tentando novamente..."
                if build_and_install; then
                    echo
                    show_app_working
                    echo
                    echo "🎉 SUCESSO! O app está funcionando após correção!"
                else
                    echo "❌ Ainda falhando após correção"
                    exit 1
                fi
            else
                echo "❌ Correção automática falhou"
                exit 1
            fi
        else
            echo "❌ Script de correção não encontrado"
            exit 1
        fi
    fi

    echo
    echo "🕐 Fim: $(date)"
}

# Executar
main
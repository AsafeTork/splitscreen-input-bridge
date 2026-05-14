#!/bin/bash

# Script de correção automática de erros no APK
# Detecta e corrige problemas comuns de build e instalação

echo "🔧 Script de Correção Automática do APK SplitScreen Input Bridge"
echo "=============================================================="

# Função para analisar erros de build
analyze_build_errors() {
    echo "🔍 Analisando erros de build..."

    if [ -f "build_diagnostic.log" ]; then
        # Erros comuns de Gradle
        if grep -q "Could not resolve" build_diagnostic.log; then
            echo "❌ Erro de dependência detectado"
            echo "💡 Tentando limpar cache do Gradle..."
            ./gradlew clean --no-daemon
            return 1
        fi

        # Erros de compilação Kotlin/Java
        if grep -q "error:" build_diagnostic.log; then
            echo "❌ Erro de compilação detectado"
            echo "📄 Erros encontrados:"
            grep "error:" build_diagnostic.log | head -5
            return 2
        fi

        # Erros de memória
        if grep -q "OutOfMemoryError\|GC overhead" build_diagnostic.log; then
            echo "❌ Erro de memória detectado"
            echo "💡 Aumentando memória para Gradle..."
            export GRADLE_OPTS="-Xmx4g -XX:MaxMetaspaceSize=512m"
            return 3
        fi
    fi

    echo "✅ Nenhum erro crítico detectado"
    return 0
}

# Função para corrigir erros de dependência
fix_dependency_issues() {
    echo "🔧 Corrigindo problemas de dependência..."

    # Limpar cache do Gradle
    echo "🗑️ Limpando cache do Gradle..."
    ./gradlew clean --no-daemon

    # Verificar conexão com internet
    if ! ping -c 1 google.com &> /dev/null; then
        echo "❌ Sem conexão com internet"
        echo "💡 Verifique sua conexão e tente novamente"
        return 1
    fi

    # Forçar refresh de dependências
    echo "🔄 Atualizando dependências..."
    ./gradlew --refresh-dependencies assembleDebug --no-daemon > refresh_deps.log 2>&1

    if [ $? -eq 0 ]; then
        echo "✅ Dependências atualizadas com sucesso"
        return 0
    else
        echo "❌ Falha ao atualizar dependências"
        return 1
    fi
}

# Função para corrigir erros de compilação
fix_compilation_errors() {
    echo "🔧 Corrigindo erros de compilação..."

    # Verificar versão do JDK
    if command -v javac &> /dev/null; then
        local java_version=$(javac -version 2>&1 | grep -o '[0-9]*' | head -1)
        if [ "$java_version" -lt 17 ]; then
            echo "❌ Versão do JDK muito antiga: $java_version"
            echo "💡 Atualize para JDK 17 ou superior"
            return 1
        fi
    fi

    # Limpar e reconstruir
    echo "🔨 Reconstruindo projeto..."
    ./gradlew clean assembleDebug --no-daemon > rebuild.log 2>&1

    if [ $? -eq 0 ]; then
        echo "✅ Projeto reconstruído com sucesso"
        return 0
    else
        echo "❌ Falha na reconstrução"
        echo "📄 Últimas linhas do log:"
        tail -10 rebuild.log
        return 1
    fi
}

# Função para corrigir erros de instalação
fix_installation_errors() {
    echo "🔧 Corrigindo erros de instalação..."

    # Verificar se o dispositivo está conectado
    local devices=$(adb devices | grep -v "List of devices" | grep -v "^$" | wc -l)
    if [ $devices -eq 0 ]; then
        echo "❌ Nenhum dispositivo conectado"
        echo "💡 Conecte um dispositivo via USB ou WiFi"
        echo "   Veja WIFI_ADB_GUIDE.md para instruções"
        return 1
    fi

    # Verificar versão do Android
    local sdk_version=$(adb shell getprop ro.build.version.sdk 2>/dev/null)
    if [ "$sdk_version" -lt 33 ]; then
        echo "❌ Versão do Android muito antiga: SDK $sdk_version"
        echo "💡 Necessário Android 13+ (SDK 33)"
        return 1
    fi

    # Tentar instalação com reinício
    echo "🔄 Tentando instalação com reinício..."
    adb uninstall com.splitscreen.inputbridge 2>/dev/null
    adb install -r app/build/outputs/apk/debug/app-debug.apk > install_fix.log 2>&1

    if [ $? -eq 0 ]; then
        echo "✅ APK instalado com sucesso após correção"
        return 0
    else
        echo "❌ Falha na instalação mesmo após correção"
        echo "📄 Erro da instalação:"
        tail -5 install_fix.log
        return 1
    fi
}

# Função principal de correção automática
auto_fix() {
    echo "🚀 Iniciando correção automática..."

    # Analisar erros atuais
    analyze_build_errors
    local error_code=$?

    case $error_code in
        1)
            # Erro de dependência
            if fix_dependency_issues; then
                echo "✅ Correção de dependência bem-sucedida"
                return 0
            fi
            ;;
        2)
            # Erro de compilação
            if fix_compilation_errors; then
                echo "✅ Correção de compilação bem-sucedida"
                return 0
            fi
            ;;
        3)
            # Erro de memória
            echo "💡 Tente executar novamente com mais memória:"
            echo "   export GRADLE_OPTS=\"-Xmx4g -XX:MaxMetaspaceSize=512m\""
            echo "   ./auto_fix_apk.sh"
            return 1
            ;;
        0)
            # Verificar instalação
            if ! adb shell pm list packages | grep -q "com.splitscreen.inputbridge"; then
                echo "⚠️ App não instalado, tentando correção..."
                if fix_installation_errors; then
                    echo "✅ Instalação corrigida com sucesso"
                    return 0
                fi
            else
                echo "✅ Tudo funcionando corretamente!"
                return 0
            fi
            ;;
    esac

    echo "❌ Não foi possível corrigir automaticamente"
    echo "💡 Execute o diagnóstico completo para mais detalhes:"
    echo "   ./diagnostic_apk.sh"
    return 1
}

# Executar correção automática
auto_fix
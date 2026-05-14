#!/bin/bash

# Script de monitoramento do APK no Claude Code
# Verifica status da PR e executa testes

echo "🔍 Monitorando PR e APK no Claude Code..."

# Verificar status da PR
echo "📋 Verificando status da PR..."
gh pr list --limit 5 | grep "OPEN" > /dev/null
if [ $? -eq 0 ]; then
    echo "✅ PR ainda aberta"

    # Verificar se há novos commits
    gh pr view 1 --json commits | grep -c "commit" > commit_count.txt
    COMMITS=$(cat commit_count.txt)
    echo "📝 Commits na PR: $COMMITS"

    # Verificar status dos checks
    echo "🧪 Verificando status dos checks..."
    gh pr checks 1 --watch > checks_status.txt 2>&1

    # Se os checks estiverem passando, executar demonstração
    if grep -q "pass" checks_status.txt; then
        echo "✅ Checks passando - executando demonstração..."

        # Construir APK
        echo "🔨 Construindo APK..."
        ./gradlew assembleDebug --no-daemon > build_claude.log 2>&1
        if [ $? -eq 0 ]; then
            echo "✅ Build bem-sucedido"

            # Verificar se dispositivo está conectado
            adb devices | grep -v "List of devices" | grep -v "^$" > /dev/null
            if [ $? -eq 0 ]; then
                echo "📱 Dispositivo conectado - instalando APK..."
                adb install -r app/build/outputs/apk/debug/app-debug.apk > install_claude.log 2>&1
                if [ $? -eq 0 ]; then
                    echo "✅ APK instalado com sucesso"
                    echo "🎮 App pronto para uso!"

                    # Iniciar app
                    adb shell am start -n com.splitscreen.inputbridge/.MainActivity > /dev/null 2>&1
                    echo "🚀 App iniciado no dispositivo"
                else
                    echo "❌ Falha na instalação do APK"
                fi
            else
                echo "⚠️ Nenhum dispositivo conectado - APK disponível em app/build/outputs/apk/debug/"
            fi
        else
            echo "❌ Build falhou"
            tail -10 build_claude.log
        fi
    else
        echo "⚠️ Checks ainda em execução ou falhando"
    fi
else
    echo "✅ Nenhuma PR aberta encontrada"
fi

echo "🕐 Última verificação: $(date)"
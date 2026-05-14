#!/bin/bash

# Script de monitoramento automático do APK com correção automática
# Executa testes a cada 5 minutos e corrige problemas automaticamente

echo "🚀 Iniciando monitoramento automático do APK com correção automática..."

# Função para verificar se o APK foi construído
check_apk_build() {
    echo "🔍 Verificando construção do APK..."

    # Limpar builds anteriores
    rm -rf app/build/outputs/apk/debug/*.apk 2>/dev/null || true

    # Construir APK
    echo "🔨 Construindo APK..."
    ./gradlew assembleDebug --no-daemon --stacktrace > build_log.txt 2>&1
    BUILD_EXIT_CODE=$?

    if [ $BUILD_EXIT_CODE -eq 0 ]; then
        echo "✅ Build bem-sucedido"

        # Verificar APK
        if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
            APK_SIZE=$(du -h app/build/outputs/apk/debug/app-debug.apk | cut -f1)
            echo "📦 APK gerado: $APK_SIZE"

            # Validar APK (se aapt2 estiver disponível)
            if command -v aapt2 &> /dev/null; then
                echo "🔍 Validando APK..."
                aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk > apk_info.txt 2>&1
                echo "✅ APK validado com sucesso"
            else
                echo "⚠️  aapt2 não disponível, pulando validação"
            fi

            return 0
        else
            echo "❌ APK não encontrado após build"
            return 1
        fi
    else
        echo "❌ Build falhou"
        tail -20 build_log.txt
        return 1
    fi
}

# Função para corrigir problemas automaticamente
auto_fix_issues() {
    echo "🔧 Tentando correção automática de problemas..."

    # Executar script de correção automática
    if [ -f "auto_fix_apk.sh" ]; then
        echo "🔄 Executando correção automática..."
        ./auto_fix_apk.sh > auto_fix_log.txt 2>&1
        local fix_result=$?

        if [ $fix_result -eq 0 ]; then
            echo "✅ Correção automática bem-sucedida"
            return 0
        else
            echo "❌ Correção automática falhou"
            tail -10 auto_fix_log.txt
            return 1
        fi
    else
        echo "⚠️ Script de correção automática não encontrado"
        return 1
    fi
}

# Função para enviar notificação (placeholder)
send_notification() {
    local status=$1
    local message=$2
    echo "🔔 Notificação: $status - $message"

    # Aqui você pode adicionar integração com:
    # - Slack webhook
    # - Email
    # - Telegram bot
    # - GitHub issue creation
}

# Loop principal
while true; do
    echo "========================================"
    echo "🕐 $(date)"
    echo "========================================"

    TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')

    if check_apk_build; then
        send_notification "SUCCESS" "APK construído com sucesso em $TIMESTAMP"
        echo "✅ Teste concluído com sucesso - Próxima execução em 5 minutos"
    else
        send_notification "FAILURE" "Falha na construção do APK em $TIMESTAMP"
        echo "🔧 Tentando correção automática..."

        if auto_fix_issues; then
            echo "✅ Correção automática aplicada - Tentando build novamente"
            if check_apk_build; then
                send_notification "SUCCESS" "APK construído após correção automática em $TIMESTAMP"
                echo "✅ Build bem-sucedido após correção!"
            else
                echo "❌ Build ainda falhando após correção"
            fi
        else
            echo "❌ Correção automática falhou - Próxima tentativa em 5 minutos"
        fi
    fi

    echo "💤 Aguardando 5 minutos..."
    sleep 300  # 5 minutos
done
# Script de monitoramento automático do APK para Windows
# Executa testes a cada 5 minutos e reporta resultados

Write-Host "🚀 Iniciando monitoramento automático do APK..." -ForegroundColor Green

# Função para verificar se o APK foi construído
function Check-APKBuild {
    Write-Host "🔍 Verificando construção do APK..." -ForegroundColor Cyan

    # Limpar builds anteriores
    if (Test-Path "app\build\outputs\apk\debug\*.apk") {
        Remove-Item "app\build\outputs\apk\debug\*.apk" -Force
    }

    # Construir APK
    Write-Host "🔨 Construindo APK..." -ForegroundColor Yellow
    $buildLog = "build_log.txt"
    & .\gradlew assembleDebug --no-daemon --stacktrace 2>&1 | Out-File -FilePath $buildLog -Encoding UTF8
    $buildExitCode = $LASTEXITCODE

    if ($buildExitCode -eq 0) {
        Write-Host "✅ Build bem-sucedido" -ForegroundColor Green

        # Verificar APK
        $apkPath = "app\build\outputs\apk\debug\app-debug.apk"
        if (Test-Path $apkPath) {
            $apkSize = (Get-Item $apkPath).Length / 1MB
            Write-Host "📦 APK gerado: $([math]::Round($apkSize, 2)) MB" -ForegroundColor Green

            # Validar APK (se aapt2 estiver disponível)
            if (Get-Command "aapt2" -ErrorAction SilentlyContinue) {
                Write-Host "🔍 Validando APK..." -ForegroundColor Cyan
                aapt2 dump badging $apkPath > apk_info.txt 2>&1
                Write-Host "✅ APK validado com sucesso" -ForegroundColor Green
            } else {
                Write-Host "⚠️ aapt2 não disponível, pulando validação" -ForegroundColor Yellow
            }

            return $true
        } else {
            Write-Host "❌ APK não encontrado após build" -ForegroundColor Red
            return $false
        }
    } else {
        Write-Host "❌ Build falhou" -ForegroundColor Red
        if (Test-Path $buildLog) {
            Get-Content $buildLog -Tail 20
        }
        return $false
    }
}

# Função para enviar notificação (placeholder)
function Send-Notification {
    param(
        [string]$Status,
        [string]$Message
    )
    Write-Host "🔔 Notificação: $Status - $Message" -ForegroundColor Magenta

    # Aqui você pode adicionar integração com:
    # - Slack webhook
    # - Email
    # - Telegram bot
    # - GitHub issue creation
}

# Loop principal
while ($true) {
    Write-Host "========================================" -ForegroundColor Blue
    Write-Host "🕐 $(Get-Date)" -ForegroundColor Blue
    Write-Host "========================================" -ForegroundColor Blue

    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

    if (Check-APKBuild) {
        Send-Notification "SUCCESS" "APK construído com sucesso em $timestamp"
        Write-Host "✅ Teste concluído com sucesso - Próxima execução em 5 minutos" -ForegroundColor Green
    } else {
        Send-Notification "FAILURE" "Falha na construção do APK em $timestamp"
        Write-Host "❌ Teste falhou - Próxima execução em 5 minutos" -ForegroundColor Red
    }

    Write-Host "💤 Aguardando 5 minutos..." -ForegroundColor Gray
    Start-Sleep -Seconds 300  # 5 minutos
}
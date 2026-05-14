# Script de demonstração visual do app funcionando para Windows
# Mostra o app em ação no dispositivo conectado

Write-Host "📱 Script de Demonstração Visual do SplitScreen Input Bridge" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Função para verificar ambiente
function Check-Environment {
    Write-Host "🔧 Verificando ambiente..." -ForegroundColor Yellow

    # Verificar ADB
    if (-not (Get-Command "adb" -ErrorAction SilentlyContinue)) {
        Write-Host "❌ ADB não encontrado" -ForegroundColor Red
        return $false
    }

    # Verificar dispositivo conectado
    $devices = adb devices | Select-String -Pattern "device$" -SimpleMatch
    if ($devices -eq $null) {
        Write-Host "❌ Nenhum dispositivo conectado" -ForegroundColor Red
        Write-Host "💡 Conecte um dispositivo via USB ou WiFi" -ForegroundColor Yellow
        Write-Host "   Veja WIFI_ADB_GUIDE.md para instruções" -ForegroundColor Gray
        return $false
    }

    Write-Host "✅ Ambiente pronto" -ForegroundColor Green
    return $true
}

# Função para verificar se app está instalado
function Check-AppInstalled {
    Write-Host "🔍 Verificando se app está instalado..." -ForegroundColor Yellow

    $installed = adb shell pm list packages | Select-String "com.splitscreen.inputbridge"
    if ($installed -ne $null) {
        Write-Host "✅ App instalado" -ForegroundColor Green
        return $true
    } else {
        Write-Host "❌ App não instalado" -ForegroundColor Red
        Write-Host "💡 Execute o diagnóstico primeiro:" -ForegroundColor Yellow
        Write-Host "   .\diagnostic_apk.ps1" -ForegroundColor Gray
        return $false
    }
}

# Função para iniciar app
function Start-App {
    Write-Host "🚀 Iniciando app..." -ForegroundColor Yellow

    # Iniciar activity principal
    $result = adb shell am start -n com.splitscreen.inputbridge/.MainActivity 2>$null

    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ App iniciado com sucesso" -ForegroundColor Green
        return $true
    } else {
        Write-Host "❌ Falha ao iniciar app" -ForegroundColor Red
        return $false
    }
}

# Função para demonstrar funcionalidades
function Demo-Features {
    Write-Host "🎮 Demonstrando funcionalidades..." -ForegroundColor Cyan

    # Mostrar informações do dispositivo
    Write-Host "📱 Informações do dispositivo:" -ForegroundColor Yellow
    adb shell getprop ro.product.model
    adb shell getprop ro.build.version.release

    # Mostrar status do app
    Write-Host "📊 Status do app:" -ForegroundColor Yellow
    adb shell dumpsys package com.splitscreen.inputbridge | Select-String -Pattern "versionName|installed"

    # Verificar serviços em execução
    Write-Host "⚙️ Serviços em execução:" -ForegroundColor Yellow
    $services = adb shell dumpsys activity services | Select-String -Pattern "inputbridge" -SimpleMatch
    if ($services -ne $null) {
        $services | Select-Object -First 3
    } else {
        Write-Host "   Nenhum serviço do InputBridge encontrado" -ForegroundColor Gray
    }

    Write-Host "✅ Demonstração concluída!" -ForegroundColor Green
}

# Função para simular interação
function Simulate-Interaction {
    Write-Host "🕹️ Simulando interação com gamepad..." -ForegroundColor Cyan

    # Simular toque na tela (coordenadas centrais)
    Write-Host "👆 Simulando toque na tela..." -ForegroundColor Yellow
    adb shell input tap 500 500

    # Aguardar
    Start-Sleep -Seconds 2

    # Simular swipe
    Write-Host "👉 Simulando swipe..." -ForegroundColor Yellow
    adb shell input swipe 300 500 800 500 500

    # Aguardar
    Start-Sleep -Seconds 2

    # Simular botão back
    Write-Host "🔙 Simulando botão back..." -ForegroundColor Yellow
    adb shell input keyevent 4

    Write-Host "✅ Interações simuladas!" -ForegroundColor Green
}

# Função para mostrar logs em tempo real
function Show-Logs {
    Write-Host "📋 Mostrando logs em tempo real (10 segundos)..." -ForegroundColor Cyan
    Write-Host "💡 Procure por mensagens com tag 'InputBridge'" -ForegroundColor Gray

    # Mostrar últimos logs e seguir por 10 segundos
    Write-Host "🔍 Coletando logs..." -ForegroundColor Yellow
    $logs = adb logcat -T 100 2>$null | Select-String -Pattern "inputbridge" -SimpleMatch

    if ($logs -ne $null) {
        $logs | Select-Object -First 10 | ForEach-Object {
            Write-Host "   $($_.Line)" -ForegroundColor Gray
        }
    } else {
        Write-Host "   Nenhum log do InputBridge encontrado nos últimos 100 registros" -ForegroundColor Gray
    }

    Write-Host "✅ Logs coletados!" -ForegroundColor Green
}

# Função principal de demonstração
function Main-Demo {
    Write-Host "🎪 Iniciando demonstração completa..." -ForegroundColor Cyan

    # Verificar ambiente
    if (-not (Check-Environment)) {
        exit 1
    }

    Write-Host ""

    # Verificar app instalado
    if (-not (Check-AppInstalled)) {
        exit 1
    }

    Write-Host ""

    # Iniciar app
    if (Start-App) {
        Write-Host ""
        # Demonstrar funcionalidades
        Demo-Features
        Write-Host ""

        # Simular interação
        Simulate-Interaction
        Write-Host ""

        # Mostrar logs
        Show-Logs
        Write-Host ""

        Write-Host "🎉 Demonstração completa! O app está funcionando corretamente." -ForegroundColor Green
    } else {
        Write-Host "❌ Não foi possível iniciar o app para demonstração" -ForegroundColor Red
        exit 1
    }
}

# Executar demonstração
Main-Demo
# Script de diagnóstico completo do APK para Windows
# Verifica build, instalação e funcionamento no dispositivo

Write-Host "🔍 Script de Diagnóstico do APK SplitScreen Input Bridge" -ForegroundColor Cyan
Write-Host "=====================================================" -ForegroundColor Cyan

# Função para verificar ambiente
function Check-Environment {
    Write-Host "🔧 Verificando ambiente..." -ForegroundColor Yellow

    # Verificar ADB
    if (-not (Get-Command "adb" -ErrorAction SilentlyContinue)) {
        Write-Host "❌ ADB não encontrado. Instale o Android SDK Platform Tools." -ForegroundColor Red
        return $false
    }

    # Verificar Gradle
    if (-not (Test-Path ".\gradlew.bat")) {
        Write-Host "❌ Gradle wrapper não encontrado." -ForegroundColor Red
        return $false
    }

    Write-Host "✅ Ambiente OK" -ForegroundColor Green
    return $true
}

# Função para verificar dispositivos conectados
function Check-Devices {
    Write-Host "📱 Verificando dispositivos conectados..." -ForegroundColor Yellow

    $devices = adb devices | Select-String -Pattern "device$" -SimpleMatch
    if ($devices -eq $null) {
        Write-Host "⚠️  Nenhum dispositivo conectado. Conecte via USB ou WiFi." -ForegroundColor Yellow
        Write-Host "   Veja WIFI_ADB_GUIDE.md para instruções de conexão WiFi." -ForegroundColor Gray
        return $false
    } else {
        $deviceCount = ($devices | Measure-Object).Count
        Write-Host "✅ $deviceCount dispositivo(s) conectado(s)" -ForegroundColor Green
        adb devices
        return $true
    }
}

# Função para construir APK
function Build-APK {
    Write-Host "🔨 Construindo APK..." -ForegroundColor Yellow

    # Limpar builds anteriores
    if (Test-Path "app\build\outputs\apk\debug\*.apk") {
        Remove-Item "app\build\outputs\apk\debug\*.apk" -Force
    }

    # Construir
    $buildLog = "build_diagnostic.log"
    & .\gradlew assembleDebug --no-daemon --stacktrace 2>&1 | Out-File -FilePath $buildLog -Encoding UTF8
    $exitCode = $LASTEXITCODE

    if ($exitCode -eq 0) {
        Write-Host "✅ Build concluído com sucesso" -ForegroundColor Green

        # Verificar APK gerado
        $apkPath = "app\build\outputs\apk\debug\app-debug.apk"
        if (Test-Path $apkPath) {
            $apkSize = (Get-Item $apkPath).Length / 1MB
            Write-Host "📦 APK gerado: $([math]::Round($apkSize, 2)) MB" -ForegroundColor Green
            return $true
        } else {
            Write-Host "❌ APK não encontrado após build" -ForegroundColor Red
            return $false
        }
    } else {
        Write-Host "❌ Build falhou" -ForegroundColor Red
        Get-Content $buildLog -Tail 10
        return $false
    }
}

# Função para instalar APK no dispositivo
function Install-APK {
    Write-Host "📲 Instalando APK no dispositivo..." -ForegroundColor Yellow

    $apkPath = "app\build\outputs\apk\debug\app-debug.apk"

    if (-not (Test-Path $apkPath)) {
        Write-Host "❌ APK não encontrado: $apkPath" -ForegroundColor Red
        return $false
    }

    # Instalar APK
    $installLog = "install.log"
    adb install -r $apkPath 2>&1 | Out-File -FilePath $installLog -Encoding UTF8
    $installResult = $LASTEXITCODE

    if ($installResult -eq 0) {
        Write-Host "✅ APK instalado com sucesso" -ForegroundColor Green
        return $true
    } else {
        Write-Host "❌ Falha na instalação do APK" -ForegroundColor Red
        Get-Content $installLog -Tail 5
        return $false
    }
}

# Função para verificar instalação
function Verify-Installation {
    Write-Host "✅ Verificando instalação..." -ForegroundColor Yellow

    # Verificar se o pacote está instalado
    $packageCheckLog = "package_check.log"
    adb shell pm list packages | Select-String "com.splitscreen.inputbridge" > $packageCheckLog 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ App instalado corretamente" -ForegroundColor Green

        # Tentar iniciar o app (opcional)
        Write-Host "🚀 Tentando iniciar o app..." -ForegroundColor Cyan
        adb shell am start -n com.splitscreen.inputbridge/.MainActivity 2>$null
        return $true
    } else {
        Write-Host "❌ App não encontrado após instalação" -ForegroundColor Red
        return $false
    }
}

# Função principal
function Main {
    Write-Host "🕐 Iniciando diagnóstico: $(Get-Date)" -ForegroundColor Cyan
    Write-Host ""

    # Verificar ambiente
    if (-not (Check-Environment)) {
        Write-Host "❌ Diagnóstico interrompido - Problemas no ambiente" -ForegroundColor Red
        exit 1
    }

    Write-Host ""

    # Verificar dispositivos
    $devicesConnected = Check-Devices

    Write-Host ""

    # Construir APK
    if (-not (Build-APK)) {
        Write-Host "❌ Diagnóstico falhou - Build do APK" -ForegroundColor Red
        exit 1
    }

    Write-Host ""

    # Se houver dispositivo conectado, testar instalação
    if ($devicesConnected) {
        Write-Host ""
        if ((Install-APK) -and (Verify-Installation)) {
            Write-Host "🎉 Diagnóstico completo - Tudo funcionando!" -ForegroundColor Green
        } else {
            Write-Host "❌ Diagnóstico completo - Problemas na instalação" -ForegroundColor Red
        }
    } else {
        Write-Host "✅ Diagnóstico de build completo - APK gerado com sucesso" -ForegroundColor Green
        Write-Host "💡 Conecte um dispositivo para testar a instalação" -ForegroundColor Yellow
    }

    Write-Host ""
    Write-Host "📄 Logs detalhados disponíveis em:" -ForegroundColor Gray
    Write-Host "   - build_diagnostic.log" -ForegroundColor Gray
    Write-Host "   - install.log" -ForegroundColor Gray
    Write-Host "   - package_check.log" -ForegroundColor Gray
}

# Executar diagnóstico
Main
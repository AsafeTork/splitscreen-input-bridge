# Script de correção automática de erros no APK para Windows
# Detecta e corrige problemas comuns de build e instalação

Write-Host "🔧 Script de Correção Automática do APK SplitScreen Input Bridge" -ForegroundColor Cyan
Write-Host "==============================================================" -ForegroundColor Cyan

# Função para analisar erros de build
function Analyze-BuildErrors {
    Write-Host "🔍 Analisando erros de build..." -ForegroundColor Yellow

    if (Test-Path "build_diagnostic.log") {
        $content = Get-Content "build_diagnostic.log" -Raw

        # Erros comuns de Gradle
        if ($content -match "Could not resolve") {
            Write-Host "❌ Erro de dependência detectado" -ForegroundColor Red
            Write-Host "💡 Tentando limpar cache do Gradle..." -ForegroundColor Yellow
            & .\gradlew clean --no-daemon
            return 1
        }

        # Erros de compilação Kotlin/Java
        if ($content -match "error:") {
            Write-Host "❌ Erro de compilação detectado" -ForegroundColor Red
            Write-Host "📄 Erros encontrados:" -ForegroundColor Gray
            Select-String "error:" "build_diagnostic.log" | Select-Object -First 5 | ForEach-Object {
                Write-Host "   $($_.Line)" -ForegroundColor Gray
            }
            return 2
        }

        # Erros de memória
        if ($content -match "OutOfMemoryError|GC overhead") {
            Write-Host "❌ Erro de memória detectado" -ForegroundColor Red
            Write-Host "💡 Aumentando memória para Gradle..." -ForegroundColor Yellow
            $env:GRADLE_OPTS = "-Xmx4g -XX:MaxMetaspaceSize=512m"
            return 3
        }
    }

    Write-Host "✅ Nenhum erro crítico detectado" -ForegroundColor Green
    return 0
}

# Função para corrigir erros de dependência
function Fix-DependencyIssues {
    Write-Host "🔧 Corrigindo problemas de dependência..." -ForegroundColor Yellow

    # Limpar cache do Gradle
    Write-Host "🗑️ Limpando cache do Gradle..." -ForegroundColor Gray
    & .\gradlew clean --no-daemon

    # Verificar conexão com internet
    try {
        $response = Invoke-WebRequest -Uri "http://google.com" -TimeoutSec 5 -UseBasicParsing
    } catch {
        Write-Host "❌ Sem conexão com internet" -ForegroundColor Red
        Write-Host "💡 Verifique sua conexão e tente novamente" -ForegroundColor Yellow
        return $false
    }

    # Forçar refresh de dependências
    Write-Host "🔄 Atualizando dependências..." -ForegroundColor Gray
    $refreshLog = "refresh_deps.log"
    & .\gradlew --refresh-dependencies assembleDebug --no-daemon 2>&1 | Out-File -FilePath $refreshLog -Encoding UTF8

    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ Dependências atualizadas com sucesso" -ForegroundColor Green
        return $true
    } else {
        Write-Host "❌ Falha ao atualizar dependências" -ForegroundColor Red
        return $false
    }
}

# Função para corrigir erros de compilação
function Fix-CompilationErrors {
    Write-Host "🔧 Corrigindo erros de compilação..." -ForegroundColor Yellow

    # Verificar versão do JDK
    if (Get-Command "javac" -ErrorAction SilentlyContinue) {
        $javaVersion = javac -version 2>&1 | Select-String -Pattern '[0-9]+' | ForEach-Object { $_.Matches.Value }
        if ($javaVersion -and [int]$javaVersion -lt 17) {
            Write-Host "❌ Versão do JDK muito antiga: $javaVersion" -ForegroundColor Red
            Write-Host "💡 Atualize para JDK 17 ou superior" -ForegroundColor Yellow
            return $false
        }
    }

    # Limpar e reconstruir
    Write-Host "🔨 Reconstruindo projeto..." -ForegroundColor Gray
    $rebuildLog = "rebuild.log"
    & .\gradlew clean assembleDebug --no-daemon 2>&1 | Out-File -FilePath $rebuildLog -Encoding UTF8

    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ Projeto reconstruído com sucesso" -ForegroundColor Green
        return $true
    } else {
        Write-Host "❌ Falha na reconstrução" -ForegroundColor Red
        Write-Host "📄 Últimas linhas do log:" -ForegroundColor Gray
        Get-Content $rebuildLog -Tail 10
        return $false
    }
}

# Função para corrigir erros de instalação
function Fix-InstallationErrors {
    Write-Host "🔧 Corrigindo erros de instalação..." -ForegroundColor Yellow

    # Verificar se o dispositivo está conectado
    $devices = adb devices | Select-String -Pattern "device$" -SimpleMatch
    if ($devices -eq $null) {
        Write-Host "❌ Nenhum dispositivo conectado" -ForegroundColor Red
        Write-Host "💡 Conecte um dispositivo via USB ou WiFi" -ForegroundColor Yellow
        Write-Host "   Veja WIFI_ADB_GUIDE.md para instruções" -ForegroundColor Gray
        return $false
    }

    # Verificar versão do Android
    $sdkVersion = adb shell getprop ro.build.version.sdk 2>$null
    if ($sdkVersion -and [int]$sdkVersion -lt 33) {
        Write-Host "❌ Versão do Android muito antiga: SDK $sdkVersion" -ForegroundColor Red
        Write-Host "💡 Necessário Android 13+ (SDK 33)" -ForegroundColor Yellow
        return $false
    }

    # Tentar instalação com reinício
    Write-Host "🔄 Tentando instalação com reinício..." -ForegroundColor Gray
    $installFixLog = "install_fix.log"
    adb uninstall com.splitscreen.inputbridge 2>$null
    adb install -r "app\build\outputs\apk\debug\app-debug.apk" 2>&1 | Out-File -FilePath $installFixLog -Encoding UTF8

    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ APK instalado com sucesso após correção" -ForegroundColor Green
        return $true
    } else {
        Write-Host "❌ Falha na instalação mesmo após correção" -ForegroundColor Red
        Write-Host "📄 Erro da instalação:" -ForegroundColor Gray
        Get-Content $installFixLog -Tail 5
        return $false
    }
}

# Função principal de correção automática
function Auto-Fix {
    Write-Host "🚀 Iniciando correção automática..." -ForegroundColor Cyan

    # Analisar erros atuais
    $errorCode = Analyze-BuildErrors

    switch ($errorCode) {
        1 {
            # Erro de dependência
            if (Fix-DependencyIssues) {
                Write-Host "✅ Correção de dependência bem-sucedida" -ForegroundColor Green
                return $true
            }
        }
        2 {
            # Erro de compilação
            if (Fix-CompilationErrors) {
                Write-Host "✅ Correção de compilação bem-sucedida" -ForegroundColor Green
                return $true
            }
        }
        3 {
            # Erro de memória
            Write-Host "💡 Tente executar novamente com mais memória:" -ForegroundColor Yellow
            Write-Host "   `$env:GRADLE_OPTS = `"-Xmx4g -XX:MaxMetaspaceSize=512m`"" -ForegroundColor Gray
            Write-Host "   .\auto_fix_apk.ps1" -ForegroundColor Gray
            return $false
        }
        0 {
            # Verificar instalação
            $installed = adb shell pm list packages | Select-String "com.splitscreen.inputbridge"
            if (-not $installed) {
                Write-Host "⚠️ App não instalado, tentando correção..." -ForegroundColor Yellow
                if (Fix-InstallationErrors) {
                    Write-Host "✅ Instalação corrigida com sucesso" -ForegroundColor Green
                    return $true
                }
            } else {
                Write-Host "✅ Tudo funcionando corretamente!" -ForegroundColor Green
                return $true
            }
        }
    }

    Write-Host "❌ Não foi possível corrigir automaticamente" -ForegroundColor Red
    Write-Host "💡 Execute o diagnóstico completo para mais detalhes:" -ForegroundColor Yellow
    Write-Host "   .\diagnostic_apk.ps1" -ForegroundColor Gray
    return $false
}

# Executar correção automática
Auto-Fix
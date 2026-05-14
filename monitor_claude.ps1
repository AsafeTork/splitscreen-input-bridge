# Script de monitoramento do APK no Claude Code para Windows
# Verifica status da PR e executa testes

Write-Host "🔍 Monitorando PR e APK no Claude Code..." -ForegroundColor Cyan

# Verificar status da PR
Write-Host "📋 Verificando status da PR..." -ForegroundColor Yellow
$prStatus = gh pr list --limit 5 | Select-String "OPEN"
if ($prStatus -ne $null) {
    Write-Host "✅ PR ainda aberta" -ForegroundColor Green

    # Verificar commits na PR
    $commits = (gh pr view 1 --json commits | Select-String "commit" | Measure-Object).Count
    Write-Host "📝 Commits na PR: $commits" -ForegroundColor Gray

    # Verificar status dos checks
    Write-Host "🧪 Verificando status dos checks..." -ForegroundColor Yellow
    $checks = gh pr checks 1 2>$null

    # Se os checks estiverem passando, executar demonstração
    if ($checks -match "pass") {
        Write-Host "✅ Checks passando - executando demonstração..." -ForegroundColor Green

        # Construir APK
        Write-Host "🔨 Construindo APK..." -ForegroundColor Gray
        $buildLog = "build_claude.log"
        & .\gradlew assembleDebug --no-daemon 2>&1 | Out-File -FilePath $buildLog -Encoding UTF8
        if ($LASTEXITCODE -eq 0) {
            Write-Host "✅ Build bem-sucedido" -ForegroundColor Green

            # Verificar se dispositivo está conectado
            $devices = adb devices | Select-String -Pattern "device$" -SimpleMatch
            if ($devices -ne $null) {
                Write-Host "📱 Dispositivo conectado - instalando APK..." -ForegroundColor Gray
                $installLog = "install_claude.log"
                adb install -r "app\build\outputs\apk\debug\app-debug.apk" 2>&1 | Out-File -FilePath $installLog -Encoding UTF8
                if ($LASTEXITCODE -eq 0) {
                    Write-Host "✅ APK instalado com sucesso" -ForegroundColor Green
                    Write-Host "🎮 App pronto para uso!" -ForegroundColor Green

                    # Iniciar app
                    adb shell am start -n com.splitscreen.inputbridge/.MainActivity > $null 2>&1
                    Write-Host "🚀 App iniciado no dispositivo" -ForegroundColor Cyan
                } else {
                    Write-Host "❌ Falha na instalação do APK" -ForegroundColor Red
                    Get-Content $installLog -Tail 5
                }
            } else {
                Write-Host "⚠️ Nenhum dispositivo conectado - APK disponível em app\build\outputs\apk\debug\" -ForegroundColor Yellow
            }
        } else {
            Write-Host "❌ Build falhou" -ForegroundColor Red
            Get-Content $buildLog -Tail 10
        }
    } else {
        Write-Host "⚠️ Checks ainda em execução ou falhando" -ForegroundColor Yellow
    }
} else {
    Write-Host "✅ Nenhuma PR aberta encontrada" -ForegroundColor Green
}

Write-Host "🕐 Última verificação: $(Get-Date)" -ForegroundColor Cyan
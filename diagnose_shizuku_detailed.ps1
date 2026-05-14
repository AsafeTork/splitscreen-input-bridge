# Script de diagnóstico detalhado do Shizuku para Windows
# Execute este script com o dispositivo conectado via ADB

Write-Host "🔍 Diagnóstico Detalhado do Shizuku" -ForegroundColor Cyan
Write-Host "===================================" -ForegroundColor Cyan

# Verificar se o dispositivo está conectado
Write-Host ""
Write-Host "📱 Verificando dispositivo conectado..." -ForegroundColor Yellow
$devices = adb devices | Select-String -Pattern "List" -NotMatch | Select-String -Pattern "^$" -NotMatch
if ($devices -eq $null) {
    Write-Host "❌ Nenhum dispositivo conectado!" -ForegroundColor Red
    Write-Host "Por favor, conecte seu dispositivo Android via USB e habilite a depuração USB." -ForegroundColor Yellow
    exit 1
}

Write-Host "✅ Dispositivo conectado!" -ForegroundColor Green
Write-Host $devices -ForegroundColor Gray

# Verificar status do Shizuku
Write-Host ""
Write-Host "🔧 Verificando status do Shizuku..." -ForegroundColor Yellow
Write-Host "-----------------------------------" -ForegroundColor Gray
adb shell dumpsys package dev.rikka.shizuku | Select-String -Pattern "enabled|version|granted"

# Verificar permissões do aplicativo
Write-Host ""
Write-Host "🔓 Verificando permissões do aplicativo..." -ForegroundColor Yellow
Write-Host "------------------------------------------" -ForegroundColor Gray
adb shell dumpsys package com.splitscreen.inputbridge | Select-String -Pattern "requested permissions" -Context 0,20

# Verificar se o Shizuku está rodando
Write-Host ""
Write-Host "🏃 Verificando se Shizuku está rodando..." -ForegroundColor Yellow
Write-Host "-----------------------------------------" -ForegroundColor Gray
adb shell ps | Select-String -Pattern "shizuku"

# Verificar status das permissões específicas
Write-Host ""
Write-Host "🔐 Verificando permissões específicas..." -ForegroundColor Yellow
Write-Host "----------------------------------------" -ForegroundColor Gray
adb shell pm list permissions -d -g | Select-String -Pattern "shizuku|Shizuku" -Context 5,5

# Verificar se o serviço está ativo
Write-Host ""
Write-Host "サービ Verificando serviços ativos..." -ForegroundColor Yellow
Write-Host "-------------------------------------" -ForegroundColor Gray
adb shell dumpsys activity services | Select-String -Pattern "shizuku" -SimpleMatch

# Verificar logs do Shizuku
Write-Host ""
Write-Host "📋 Verificando logs do Shizuku..." -ForegroundColor Yellow
Write-Host "---------------------------------" -ForegroundColor Gray
adb logcat -T 100 | Select-String -Pattern "shizuku","Shizuku" | Select-Object -Last 10

Write-Host ""
Write-Host "✅ Diagnóstico concluído!" -ForegroundColor Green
Write-Host ""
Write-Host "Se o problema persistir, tente:" -ForegroundColor Yellow
Write-Host "1. Reiniciar o app Shizuku" -ForegroundColor Gray
Write-Host "2. Revogar e conceder novamente a permissão" -ForegroundColor Gray
Write-Host "3. Reiniciar o dispositivo" -ForegroundColor Gray
Write-Host "4. Verificar se o Shizuku está atualizado" -ForegroundColor Gray
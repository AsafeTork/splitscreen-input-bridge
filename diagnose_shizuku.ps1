# Script para diagnosticar problemas com permissões do Shizuku no Windows

Write-Host "🔍 Diagnosticando problemas com permissões do Shizuku..." -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan

Write-Host ""
Write-Host "📱 Verificando status do Shizuku:" -ForegroundColor Yellow
Write-Host "--------------------------------" -ForegroundColor Gray
adb shell dumpsys package dev.rikka.shizuku | Select-String -Pattern "enabled|version"

Write-Host ""
Write-Host "🔧 Verificando permissões do aplicativo:" -ForegroundColor Yellow
Write-Host "----------------------------------------" -ForegroundColor Gray
adb shell dumpsys package com.splitscreen.inputbridge | Select-String -Pattern "requested permissions" -Context 0,10

Write-Host ""
Write-Host "⚙️ Verificando status do serviço:" -ForegroundColor Yellow
Write-Host "---------------------------------" -ForegroundColor Gray
adb shell dumpsys activity services | Select-String -Pattern "inputbridge" -SimpleMatch

Write-Host ""
Write-Host "📋 Verificando logs do aplicativo:" -ForegroundColor Yellow
Write-Host "----------------------------------" -ForegroundColor Gray
adb logcat -T 100 | Select-String -Pattern "shizuku","inputbridge" | Select-Object -Last 20

Write-Host ""
Write-Host "✅ Diagnóstico concluído!" -ForegroundColor Green
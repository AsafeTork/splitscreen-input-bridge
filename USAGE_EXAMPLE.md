# Exemplo de Uso: Melhorias no InputBridgeService

Este guia demonstra como utilizar os quatro sistemas melhorados no InputBridgeService.

## 1. Configuração Inicial

```kotlin
// No seu Activity ou Service
val service = InputBridgeService()
service.onCreate()

// Acesse os componentes melhorados
val metrics = service.getPerformanceMetrics()
val logger = service.getStructuredLogger()
val configManager = service.getConfigManager()
val profileManager = service.getProfileManager()
```

## 2. Sistema de Métricas de Performance

### Monitoramento Básico

```kotlin
// Obtenha métricas em tempo real
val currentFps = metrics.getCurrentFps()
val avgLatency = metrics.getAverageProcessingLatencyMs()
val successRate = metrics.getInjectionSuccessRate()

logger.info("Performance Status", "metrics_update", mapOf(
    "fps" to currentFps,
    "latency_ms" to "%.2f".format(avgLatency),
    "success_rate" to "%.1f".format(successRate)
))
```

### Análise Avançada

```kotlin
// Métricas estatísticas avançadas
val latencyStdDev = metrics.getLatencyStdDevMs()
val latency95th = metrics.getLatency95thPercentileMs()
val latency99th = metrics.getLatency99thPercentileMs()
val avgJitter = metrics.getAverageJitterMs()

// Gera relatório completo
val jsonReport = metrics.generateMetricsJsonReport()
logger.debug("Performance Report", "metrics_analysis", mapOf(
    "report" to jsonReport
))
```

### Monitoramento Contínuo

```kotlin
// Em uma coroutine
CoroutineScope(Dispatchers.Default).launch {
    while (true) {
        // Coleta métricas de sistema
        metrics.collectSystemMetrics()
        
        // Verifica condições críticas
        if (metrics.getCurrentFps() < 45) {
            logger.warn("Low FPS detected", "performance_alert", mapOf(
                "current_fps" to metrics.getCurrentFps(),
                "threshold" to 45
            ))
        }
        
        Thread.sleep(5000) // Verifica a cada 5 segundos
    }
}
```

## 3. Logging Estruturado

### Logs Básicos

```kotlin
// Logs de diferentes níveis
logger.verbose("Debug information", "debug_event")
logger.debug("Detailed operation", "operation_detail", mapOf("param" to "value"))
logger.info("Important event", "system_event", mapOf("status" to "success"))
logger.warn("Potential issue", "warning_event", mapOf("severity" to "medium"))

// Log com exceção
try {
    riskyOperation()
} catch (e: Exception) {
    logger.error("Operation failed", "error_event", mapOf("operation" to "risky"), e)
}
```

### Logs Especializados

```kotlin
// Log de evento de injeção
logger.logInjectionEvent(
    success = true,
    deviceDescriptor = "xbox_controller_1",
    latencyMs = 2.87,
    context = mapOf("injection_type" to "touch", "target_x" to 1280)
)

// Log de evento de transformação
logger.logTransformationEvent(
    axisX = 0.75f,
    axisY = -0.32f,
    touchX = 1280.5f,
    touchY = 1560.2f,
    context = mapOf("action" to "move", "frame_number" to 12345)
)

// Log de evento de performance
logger.logPerformanceEvent("frame_processing", mapOf(
    "frame_time_ms" to 16.7,
    "gpu_time_ms" to 8.3,
    "cpu_time_ms" to 5.2
))
```

### Gerenciamento de Logs

```kotlin
// Obtém todos os arquivos de log
val logFiles = logger.getAllLogFiles()
logger.info("Log files", "log_management", mapOf(
    "count" to logFiles.size,
    "current" to logger.getCurrentLogFilePath()
))

// Limpa logs antigos
logger.clearLogFiles()
```

## 4. Configuração Dinâmica

### Ajuste de Parâmetros

```kotlin
// Ajusta parâmetros individuais
configManager.updateConfig("deadzone_threshold" to 0.2f)
configManager.updateConfig("enable_input_smoothing" to true)
configManager.updateConfig("prediction_factor" to 0.02f)
configManager.updateConfig("watchdog_interval_ms" to 3000L)

// Obtém configuração atual
val currentConfig = configManager.configState.value
logger.info("Configuration updated", "config_change", currentConfig.toMap())
```

### Gerenciamento de Perfis

```kotlin
// Cria um novo perfil
configManager.createProfile("high_performance")

// Altera para o novo perfil
configManager.switchProfile("high_performance")

// Ajusta configurações específicas do perfil
configManager.updateConfig("deadzone_threshold" to 0.1f)
configManager.updateConfig("enable_prediction" to true)
configManager.updateConfig("kalman_filter_q" to 0.01)

// Lista todos os perfis disponíveis
val profiles = configManager.getAvailableProfiles()
logger.info("Available profiles", "profile_list", mapOf(
    "profiles" to profiles.joinToString(", ")
))
```

### Observação de Mudanças

```kotlin
// Observa mudanças na configuração
configManager.configState
    .onEach { configState ->
        logger.info("Configuration changed", "config_update", mapOf(
            "profile" to configState.profileName,
            "deadzone" to configState.deadzoneThreshold,
            "smoothing" to configState.enableInputSmoothing
        ))
        
        // Ajusta comportamento com base na configuração
        if (configState.enableInputSmoothing) {
            // Habilita suavização
        } else {
            // Desabilita suavização
        }
    }
    .launchIn(CoroutineScope(Dispatchers.Default))
```

## 5. Persistência de Perfis

### Gerenciamento de Perfis de Usuário

```kotlin
// Cria um perfil para um jogo específico
profileManager.createProfile("racing_game", "default")

// Configura dispositivos para o perfil
profileManager.updateProfileDeviceDescriptors("racing_game",
    player1Descriptor = "racing_wheel_1",
    player2Descriptor = "racing_wheel_2"
)

// Atualiza configurações específicas
profileManager.updateProfileConfig("racing_game", mapOf(
    "deadzone_threshold" to 0.05f,
    "enable_input_smoothing" to true,
    "enable_prediction" to true,
    "prediction_factor" to 0.05f
))

// Altera para o perfil
profileManager.switchProfile("racing_game")
```

### Backup e Restauração

```kotlin
// Exporta todos os perfis para JSON
CoroutineScope(Dispatchers.IO).launch {
    try {
        val jsonExport = profileManager.exportProfilesToJson()
        
        // Salva em arquivo (exemplo)
        saveToFile(jsonExport, "profiles_backup.json")
        
        logger.info("Profiles exported", "profile_backup", mapOf(
            "size" to jsonExport.length,
            "timestamp" to System.currentTimeMillis()
        ))
    } catch (e: Exception) {
        logger.error("Export failed", "profile_error", null, e)
    }
}

// Importa perfis de JSON
CoroutineScope(Dispatchers.IO).launch {
    try {
        val jsonImport = loadFromFile("profiles_backup.json")
        val success = profileManager.importProfilesFromJson(jsonImport)
        
        logger.info("Profiles imported", "profile_restore", mapOf(
            "success" to success
        ))
    } catch (e: Exception) {
        logger.error("Import failed", "profile_error", null, e)
    }
}
```

### Gerenciamento Completo

```kotlin
// Obtém todos os perfis
val allProfiles = profileManager.getAllProfiles()
allProfiles?.forEach { profile ->
    logger.info("Profile info", "profile_list", mapOf(
        "name" to profile.name,
        "player1" to profile.player1Descriptor,
        "player2" to profile.player2Descriptor,
        "configs" to profile.configPreferences.size,
        "last_used" to profile.lastUsedTimestamp
    ))
}

// Obtém o perfil atual
val currentProfile = profileManager.getCurrentProfile()
logger.info("Current profile", "profile_active", mapOf(
    "name" to currentProfile?.name,
    "is_default" to currentProfile?.isDefault
))
```

## 6. Exemplo Completo: Configuração para Jogo de Corrida

```kotlin
fun setupRacingGameProfile(service: InputBridgeService) {
    val logger = service.getStructuredLogger()
    val configManager = service.getConfigManager()
    val profileManager = service.getProfileManager()

    logger.info("Setting up racing game profile", "game_setup")

    // 1. Cria perfil para jogo de corrida
    profileManager.createProfile("racing_game", "default")

    // 2. Configura dispositivos
    profileManager.updateProfileDeviceDescriptors("racing_game",
        player1Descriptor = "racing_wheel_1",
        player2Descriptor = "racing_wheel_2"
    )

    // 3. Altera para o perfil
    profileManager.switchProfile("racing_game")

    // 4. Ajusta configuração para melhor performance
    configManager.updateConfig("deadzone_threshold" to 0.05f) // Menor deadzone para controle preciso
    configManager.updateConfig("prediction_factor" to 0.05f) // Maior previsão para movimento suave
    configManager.updateConfig("enable_input_smoothing" to true)
    configManager.updateConfig("enable_prediction" to true)
    configManager.updateConfig("kalman_filter_q" to 0.01) // Menos ruído para controle suave
    configManager.updateConfig("kalman_filter_r" to 0.08)

    // 5. Configura watchdog para verificação frequente
    configManager.updateConfig("watchdog_interval_ms" to 2000L)
    configManager.updateConfig("adaptive_watchdog_enabled" to true)

    // 6. Aumenta prioridade para melhor responsividade
    configManager.updateConfig("injection_priority" to android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)

    // 7. Obtém configuração final
    val finalConfig = configManager.configState.value
    logger.info("Racing game profile configured", "game_ready", mapOf(
        "profile" to finalConfig.profileName,
        "deadzone" to finalConfig.deadzoneThreshold,
        "prediction" to finalConfig.predictionFactor,
        "smoothing" to finalConfig.enableInputSmoothing,
        "watchdog_interval" to finalConfig.watchdogIntervalMs
    ))

    // 8. Inicia a bridge
    service.startBridge()
}
```

## 7. Exemplo Completo: Monitoramento de Performance

```kotlin
fun setupPerformanceMonitoring(service: InputBridgeService) {
    val metrics = service.getPerformanceMetrics()
    val logger = service.getStructuredLogger()
    val configManager = service.getConfigManager()

    CoroutineScope(Dispatchers.Default).launch {
        var alertCount = 0
        
        while (true) {
            try {
                // Coleta métricas
                metrics.collectSystemMetrics()
                
                val currentFps = metrics.getCurrentFps()
                val avgLatency = metrics.getAverageProcessingLatencyMs()
                val successRate = metrics.getInjectionSuccessRate()
                val systemMetrics = metrics.getCurrentSystemMetrics()

                // Loga status atual
                logger.logPerformanceEvent("periodic_metrics", mapOf(
                    "fps" to currentFps,
                    "latency_ms" to "%.2f".format(avgLatency),
                    "success_rate" to "%.1f".format(successRate),
                    "cpu_usage" to "%.1f".format(systemMetrics?.cpuUsage ?: 0f),
                    "memory_usage_mb" to "%.1f".format(systemMetrics?.memoryUsageMb ?: 0f)
                ))

                // Verifica condições críticas
                var alertTriggered = false

                if (currentFps < 45) {
                    logger.warn("Low FPS detected", "performance_alert", mapOf(
                        "current_fps" to currentFps,
                        "threshold" to 45,
                        "recommended_action" to "reduce_prediction_factor"
                    ))
                    alertTriggered = true
                    alertCount++
                }

                if (successRate < 95.0) {
                    logger.warn("Low injection success rate", "performance_alert", mapOf(
                        "current_rate" to "%.1f".format(successRate),
                        "threshold" to 95.0,
                        "recommended_action" to "check_shizuku_connection"
                    ))
                    alertTriggered = true
                    alertCount++
                }

                if (avgLatency > 10.0) {
                    logger.warn("High latency detected", "performance_alert", mapOf(
                        "current_latency_ms" to "%.2f".format(avgLatency),
                        "threshold_ms" to 10.0,
                        "recommended_action" to "optimize_processing_pipeline"
                    ))
                    alertTriggered = true
                    alertCount++
                }

                // Ajusta configuração automaticamente se necessário
                if (alertCount > 2 && currentFps < 30) {
                    logger.info("Applying automatic optimization", "auto_tuning")
                    configManager.updateConfig("enable_prediction" to false)
                    configManager.updateConfig("prediction_factor" to 0.01f)
                    alertCount = 0 // Reseta contador após ajuste
                }

                // Gera relatório completo periodicamente
                if (System.currentTimeMillis() % 30000 < 1000) { // A cada ~30 segundos
                    val jsonReport = metrics.generateMetricsJsonReport()
                    logger.debug("Full metrics report", "metrics_report", mapOf(
                        "report" to jsonReport
                    ))
                }

                Thread.sleep(5000) // Verifica a cada 5 segundos
                
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                logger.error("Monitoring failed", "monitoring_error", null, e)
                Thread.sleep(10000) // Aguarda antes de tentar novamente
            }
        }
    }
}
```

## 8. Exemplo Completo: Recuperação de Falhas

```kotlin
fun setupFailureRecovery(service: InputBridgeService) {
    val logger = service.getStructuredLogger()
    val configManager = service.getConfigManager()
    val profileManager = service.getProfileManager()

    // Observa erros de injeção
    val metrics = service.getPerformanceMetrics()
    
    CoroutineScope(Dispatchers.Default).launch {
        var consecutiveFailures = 0
        
        while (true) {
            val successRate = metrics.getInjectionSuccessRate()
            val failedInjections = metrics.getEventsDropped()

            if (successRate < 80.0) {
                consecutiveFailures++
                
                if (consecutiveFailures >= 3) {
                    logger.logCriticalEvent(
                        eventType = "injection_crisis",
                        message = "Critical injection failure rate",
                        context = mapOf(
                            "success_rate" to "%.1f".format(successRate),
                            "failed_injections" to failedInjections,
                            "consecutive_failures" to consecutiveFailures
                        )
                    )
                    
                    // Aplica configuração de fallback
                    logger.info("Applying fallback configuration", "recovery_action")
                    
                    // Salva configuração atual
                    val originalConfig = configManager.configState.value
                    
                    // Aplica configuração segura
                    configManager.updateConfig("enable_prediction" to false)
                    configManager.updateConfig("enable_input_smoothing" to false)
                    configManager.updateConfig("deadzone_threshold" to 0.3f)
                    
                    // Tenta reconectar Shizuku
                    try {
                        ShizukuUserService.reconnect()
                        Thread.sleep(2000)
                        
                        // Verifica se melhorou
                        if (metrics.getInjectionSuccessRate() > 90.0) {
                            logger.info("Recovery successful", "recovery_success")
                            consecutiveFailures = 0
                            
                            // Restaura configuração original
                            configManager.updateConfig("enable_prediction" to originalConfig.enablePrediction)
                            configManager.updateConfig("enable_input_smoothing" to originalConfig.enableInputSmoothing)
                            configManager.updateConfig("deadzone_threshold" to originalConfig.deadzoneThreshold)
                        }
                    } catch (e: Exception) {
                        logger.error("Recovery attempt failed", "recovery_error", null, e)
                    }
                }
            } else {
                consecutiveFailures = 0
            }
            
            Thread.sleep(10000) // Verifica a cada 10 segundos
        }
    }
}
```

## 9. Melhores Práticas

### 1. Monitoramento Contínuo

```kotlin
// Inicie o monitoramento quando o serviço iniciar
service.startBridge()
setupPerformanceMonitoring(service)
setupFailureRecovery(service)
```

### 2. Gerenciamento de Perfis

```kotlin
// Crie perfis para diferentes cenários
createDefaultProfiles(service)

// Permita ao usuário alternar entre perfis
profileManager.switchProfile("user_selected_profile")
```

### 3. Logging Estratégico

```kotlin
// Use logs apropriados para cada situação
logger.verbose("Debug information", "debug") // Para desenvolvimento
logger.debug("Operation details", "details") // Para diagnóstico
logger.info("Important events", "events") // Para monitoramento
logger.warn("Potential issues", "warnings") // Para alertas
logger.error("Critical failures", "errors") // Para problemas
```

### 4. Backup Regular

```kotlin
// Faça backup dos perfis regularmente
CoroutineScope(Dispatchers.IO).launch {
    val jsonBackup = profileManager.exportProfilesToJson()
    saveBackupToCloud(jsonBackup) // Implemente conforme necessário
}
```

## 10. Solução de Problemas

### Problema: Baixo FPS

```kotlin
// Verifique métricas
val fps = metrics.getCurrentFps()
val latency = metrics.getAverageProcessingLatencyMs()

// Ações possíveis
if (fps < 30) {
    // 1. Reduza a previsão
    configManager.updateConfig("prediction_factor" to 0.01f)
    
    // 2. Desabilite recursos intensivos
    configManager.updateConfig("enable_prediction" to false)
    
    // 3. Aumente o deadzone
    configManager.updateConfig("deadzone_threshold" to 0.25f)
}
```

### Problema: Alta Latência

```kotlin
// Analise latência
val avgLatency = metrics.getAverageProcessingLatencyMs()
val latency95th = metrics.getLatency95thPercentileMs()

if (avgLatency > 10.0) {
    // 1. Reduza a suavização
    configManager.updateConfig("kalman_filter_q" to 0.005)
    
    // 2. Aumente a prioridade
    configManager.updateConfig("injection_priority" to android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
    
    // 3. Verifique métricas de sistema
    metrics.collectSystemMetrics()
    val systemMetrics = metrics.getCurrentSystemMetrics()
    
    if (systemMetrics?.cpuUsage ?: 0f > 80.0) {
        logger.warn("High CPU usage detected", "system_alert", mapOf(
            "cpu_usage" to "%.1f".format(systemMetrics?.cpuUsage ?: 0f)
        ))
    }
}
```

### Problema: Baixa Taxa de Sucesso de Injeção

```kotlin
// Verifique a taxa de sucesso
val successRate = metrics.getInjectionSuccessRate()

if (successRate < 90.0) {
    // 1. Verifique conexão Shizuku
    val isShizukuReady = ShizukuUserService.isReady()
    
    if (!isShizukuReady) {
        logger.error("Shizuku not ready", "shizuku_error")
        // Tente reconectar
        ShizukuUserService.reconnect()
    }
    
    // 2. Aplique configuração mais conservadora
    configManager.updateConfig("enable_prediction" to false)
    configManager.updateConfig("enable_input_smoothing" to false)
    
    // 3. Aumente o intervalo do watchdog
    configManager.updateConfig("watchdog_interval_ms" to 10000L)
}
```

## Conclusão

Estes exemplos demonstram como utilizar os sistemas melhorados para:
- Monitorar performance em tempo real
- Ajustar configurações dinamicamente
- Gerenciar múltiplos perfis
- Registrar eventos estruturados
- Diagnosticar e resolver problemas
- Otimizar para diferentes cenários

As melhorias fornecem uma base sólida para um sistema robusto e profissional de roteamento de entrada para split-screen.
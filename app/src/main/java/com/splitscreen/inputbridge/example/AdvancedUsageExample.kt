package com.splitscreen.inputbridge.example

import android.content.Context
import com.splitscreen.inputbridge.InputBridgeServiceEnhanced
import com.splitscreen.inputbridge.config.AdvancedConfigManager
import com.splitscreen.inputbridge.metrics.PerformanceMetrics
import com.splitscreen.inputbridge.metrics.SystemMetricsCollector
import com.splitscreen.inputbridge.persistence.ProfilePersistenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Exemplos avançados de uso das novas funcionalidades implementadas
 * Incluindo métricas de sistema, logging para arquivo e configurações avançadas
 */
class AdvancedUsageExample {

    /**
     * Exemplo 1: Monitoramento completo de performance com métricas de sistema
     */
    fun monitorCompletePerformance(service: InputBridgeServiceEnhanced) {
        val metrics = service.getPerformanceMetrics()
        val logger = service.getStructuredLogger()

        // Coleta métricas de sistema
        metrics.collectSystemMetrics()
        val systemMetrics = metrics.getCurrentSystemMetrics()

        // Obtém métricas de performance
        val currentFps = metrics.getCurrentFps()
        val avgLatency = metrics.getAverageProcessingLatencyMs()
        val successRate = metrics.getInjectionSuccessRate()
        val systemCpu = metrics.getAverageSystemCpuUsage()
        val memoryUsage = metrics.getAverageMemoryUsageMb()
        val batteryLevel = metrics.getAverageBatteryLevel()

        logger.info("Complete Performance Monitor", "metrics_monitor", mapOf(
            "fps" to currentFps,
            "latency_ms" to "%.2f".format(avgLatency),
            "success_rate" to "%.1f".format(successRate),
            "events_processed" to metrics.getEventsProcessed(),
            "events_dropped" to metrics.getEventsDropped(),
            "system_cpu" to "%.1f".format(systemCpu),
            "memory_mb" to "%.1f".format(memoryUsage),
            "battery_level" to "%.0f".format(batteryLevel),
            "battery_temp" to (systemMetrics?.batteryTemperature?.toString() ?: "N/A") as Any,
            "storage_available_mb" to (systemMetrics?.availableStorageMb?.toString() ?: "N/A") as Any
        ))

        // Gera relatório completo
        val metricsReport = metrics.generateMetricsReport()
        println("Complete Metrics Report:\n$metricsReport")

        // Exporta métricas em JSON para integração com sistemas externos
        val jsonReport = metrics.generateMetricsJsonReport()
        println("JSON Metrics Report:\n$jsonReport")
    }

    /**
     * Exemplo 2: Logging para arquivo com rotação automática
     */
    fun fileLoggingExample(service: InputBridgeServiceEnhanced, context: Context) {
        val logger = service.getStructuredLogger()

        // Obtém o caminho do arquivo de log atual
        val logFilePath = logger.getCurrentLogFilePath()
        println("Current log file: $logFilePath")

        // Lista todos os arquivos de log
        val logFiles = logger.getAllLogFiles()
        println("All log files (${logFiles.size}):")
        logFiles.forEachIndexed { index, file ->
            println("${index + 1}. ${file.name} (${file.length() / 1024} KB)")
        }

        // Loga mensagens que serão salvas em arquivo
        logger.info("File logging test", "logging_test", mapOf(
            "feature" to "file_logging",
            "status" to "enabled"
        ))

        logger.warn("Warning message test", "logging_test", mapOf(
            "type" to "warning",
            "severity" to "medium"
        ))

        // Loga um evento crítico com contexto completo
        logger.logCriticalEvent("critical_test", "Critical event for testing", mapOf(
            "module" to "testing",
            "action" to "file_logging_verification",
            "expected_result" to "log_file_created"
        ))
    }

    /**
     * Exemplo 3: Configurações avançadas com modos de performance
     */
    fun advancedConfigExample(service: InputBridgeServiceEnhanced) {
        val configManager = service.getConfigManager()

        // Ativa o modo de economia de bateria
        configManager.updateConfig("battery_saver_mode" to true)
        configManager.updateConfig("adaptive_performance" to true)

        // Configurações para economia de bateria
        configManager.updateConfig("watchdog_interval_ms" to 10000L) // Intervalos mais longos
        configManager.updateConfig("max_fps" to 30) // FPS reduzido
        configManager.updateConfig("enable_prediction" to false) // Desativa previsão

        // Monitora mudanças de configuração
        val configScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        configScope.launch {
            configManager.configState.collect { configState ->
                println("Config updated - Battery Saver: ${configState.batterySaverMode}, " +
                        "Performance Mode: ${configState.performanceMode}, " +
                        "Adaptive: ${configState.adaptivePerformance}")

                // Ajusta configurações automaticamente com base no modo
                if (configState.batterySaverMode) {
                    // Aplica configurações de economia de bateria
                    if (configState.maxFps > 30) {
                        configManager.updateConfig("max_fps" to 30)
                    }
                    if (configState.enablePrediction) {
                        configManager.updateConfig("enable_prediction" to false)
                    }
                } else if (configState.performanceMode) {
                    // Aplica configurações de alto desempenho
                    if (configState.maxFps < 120) {
                        configManager.updateConfig("max_fps" to 120)
                    }
                    if (!configState.enablePrediction) {
                        configManager.updateConfig("enable_prediction" to true)
                    }
                    if (!configState.enableInputSmoothing) {
                        configManager.updateConfig("enable_input_smoothing" to true)
                    }
                }
            }
        }

        // Ativa o modo de performance
        // configManager.updateConfig("performance_mode" to true)
        // configManager.updateConfig("battery_saver_mode" to false)
    }

    /**
     * Exemplo 4: Gerenciamento avançado de perfis com backup automático
     */
    fun advancedProfileManagement(service: InputBridgeServiceEnhanced) {
        val profileManager = service.getProfileManager()

        // Cria perfis especializados
        profileManager.createProfile("battery_saver")
        profileManager.createProfile("high_performance")
        profileManager.createProfile("racing_game")
        profileManager.createProfile("fps_game")

        // Configura o perfil de economia de bateria
        profileManager.updateProfileConfig("battery_saver", mapOf(
            "deadzone_threshold" to 0.2f,
            "enable_input_smoothing" to false,
            "enable_prediction" to false,
            "max_fps" to 30,
            "watchdog_interval_ms" to 15000L
        ))

        // Configura o perfil de alto desempenho
        profileManager.updateProfileConfig("high_performance", mapOf(
            "deadzone_threshold" to 0.1f,
            "enable_input_smoothing" to true,
            "enable_prediction" to true,
            "prediction_factor" to 0.02f,
            "max_fps" to 120,
            "watchdog_interval_ms" to 2000L,
            "kalman_filter_q" to 0.01,
            "kalman_filter_r" to 0.05
        ))

        // Cria backup automático
        profileManager.createAutomaticBackup()

        // Exporta perfis com metadados
        val exportScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        exportScope.launch {
            val jsonExport = profileManager.exportProfilesToJson(includeMetadata = true)
            println("Profiles export with metadata:\n$jsonExport")

            // Salvar em arquivo
            val exportFile = java.io.File(service.getExternalFilesDir(null), "profiles_backup.json")
            exportFile.writeText(jsonExport)
            println("Profiles saved to: ${exportFile.absolutePath}")

            // Verifica versão do formato de exportação
            val exportVersion = profileManager.getExportVersion(jsonExport)
            println("Export format version: $exportVersion")
        }
    }

    /**
     * Exemplo 5: Integração completa com métricas de sistema e logging
     */
    fun completeIntegrationExample(service: InputBridgeServiceEnhanced, context: Context) {
        // 1. Monitoramento completo de performance
        monitorCompletePerformance(service)

        // 2. Logging para arquivo
        fileLoggingExample(service, context)

        // 3. Configurações avançadas
        advancedConfigExample(service)

        // 4. Gerenciamento de perfis
        advancedProfileManagement(service)

        // 5. Coleta periódica de métricas de sistema
        val metrics = service.getPerformanceMetrics()
        val metricsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        metricsScope.launch {
            while (true) {
                metrics.collectSystemMetrics()
                kotlinx.coroutines.delay(5000) // Coleta a cada 5 segundos
            }
        }

        // 6. Logging estruturado com contexto completo
        val logger = service.getStructuredLogger()
        logger.logLifecycleEvent("integration_complete", mapOf(
            "modules" to "metrics,logging,config,profiles",
            "status" to "fully_integrated",
            "timestamp" to System.currentTimeMillis()
        ))
    }

    /**
     * Exemplo 6: Análise de performance em tempo real
     */
    fun realtimePerformanceAnalysis(service: InputBridgeServiceEnhanced) {
        val metrics = service.getPerformanceMetrics()
        val logger = service.getStructuredLogger()
        val configManager = service.getConfigManager()

        // Monitora métricas em tempo real e ajusta configurações automaticamente
        val analysisScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        analysisScope.launch {
            while (true) {
                // Coleta métricas
                metrics.collectSystemMetrics()
                val systemMetrics = metrics.getCurrentSystemMetrics()
                val currentFps = metrics.getCurrentFps()
                val avgLatency = metrics.getAverageProcessingLatencyMs()
                val successRate = metrics.getInjectionSuccessRate()

                // Analisa performance
                val performanceScore = calculatePerformanceScore(currentFps, avgLatency, successRate)

                logger.logPerformanceEvent("realtime_analysis", mapOf(
                    "performance_score" to performanceScore,
                    "fps" to currentFps,
                    "latency_ms" to "%.2f".format(avgLatency),
                    "success_rate" to "%.1f".format(successRate),
                    "battery_level" to (systemMetrics?.batteryLevel ?: 0),
                    "cpu_usage" to (systemMetrics?.cpuUsage ?: 0f)
                ))

                // Ajusta configurações automaticamente com base na performance
                val currentConfig = configManager.configState.value
                if (currentConfig.adaptivePerformance) {
                    when {
                        performanceScore < 30 -> {
                            // Performance baixa - ativa modo de economia
                            if (!currentConfig.batterySaverMode) {
                                configManager.updateConfig("battery_saver_mode" to true)
                                logger.info("Adaptive performance", "config_adjustment", mapOf(
                                    "action" to "battery_saver_enabled",
                                    "reason" to "low_performance_score"
                                ))
                            }
                        }
                        performanceScore > 80 && (systemMetrics?.batteryLevel ?: 100) > 50 -> {
                            // Performance alta e bateria suficiente - ativa modo de performance
                            if (!currentConfig.performanceMode) {
                                configManager.updateConfig("performance_mode" to true)
                                configManager.updateConfig("battery_saver_mode" to false)
                                logger.info("Adaptive performance", "config_adjustment", mapOf(
                                    "action" to "performance_mode_enabled",
                                    "reason" to "high_performance_score"
                                ))
                            }
                        }
                        else -> {
                            // Modo balanceado
                            if (currentConfig.performanceMode || currentConfig.batterySaverMode) {
                                configManager.updateConfig("performance_mode" to false)
                                configManager.updateConfig("battery_saver_mode" to false)
                                logger.info("Adaptive performance", "config_adjustment", mapOf(
                                    "action" to "balanced_mode_enabled",
                                    "reason" to "normal_performance_score"
                                ))
                            }
                        }
                    }
                }

                kotlinx.coroutines.delay(10000) // Analisa a cada 10 segundos
            }
        }
    }

    /**
     * Calcula uma pontuação de performance (0-100)
     */
    private fun calculatePerformanceScore(fps: Int, latencyMs: Double, successRate: Double): Int {
        // Normaliza métricas para uma escala 0-100
        val fpsScore = (fps.toDouble() / 120.0 * 100.0).coerceIn(0.0, 100.0)
        val latencyScore = ((100.0 - latencyMs) / 100.0 * 100.0).coerceIn(0.0, 100.0)
        val successScore = successRate.coerceIn(0.0, 100.0)

        // Calcula média ponderada
        val weightedScore = (fpsScore * 0.4 + latencyScore * 0.3 + successScore * 0.3)
        return weightedScore.toInt()
    }

    /**
     * Exemplo 7: Diagnóstico completo do sistema
     */
    fun systemDiagnostics(service: InputBridgeServiceEnhanced, context: Context) {
        val metrics = service.getPerformanceMetrics()
        val logger = service.getStructuredLogger()
        val profileManager = service.getProfileManager()
        val configManager = service.getConfigManager()

        // Coleta métricas de sistema
        metrics.collectSystemMetrics()
        val systemMetrics = metrics.getCurrentSystemMetrics()

        // Gera relatório de diagnóstico
        val diagnosticReport = buildString {
            appendLine("=== System Diagnostics Report ===")
            appendLine("Timestamp: ${System.currentTimeMillis()}")
            appendLine()

            // Informações do sistema
            appendLine("--- System Information ---")
            appendLine("CPU Usage: ${systemMetrics?.cpuUsage?.roundToInt() ?: 0}%")
            appendLine("Memory Usage: ${systemMetrics?.memoryUsageMb?.roundToInt() ?: 0} MB")
            appendLine("Battery Level: ${systemMetrics?.batteryLevel ?: 0}%")
            appendLine("Battery Temperature: ${systemMetrics?.batteryTemperature?.toString() ?: "N/A"}°C")
            appendLine("Storage Available: ${systemMetrics?.availableStorageMb?.toFloat() ?: 0f} MB")
            appendLine("Process CPU: ${systemMetrics?.processCpuUsage?.roundToInt() ?: 0}%")
            appendLine("Charging: ${systemMetrics?.isCharging ?: false}")
            appendLine()

            // Performance
            appendLine("--- Performance Metrics ---")
            appendLine("Current FPS: ${metrics.getCurrentFps()}")
            appendLine("Avg Processing Latency: ${metrics.getAverageProcessingLatencyMs().roundToInt()}ms")
            appendLine("Avg Injection Latency: ${metrics.getAverageInjectionLatencyMs().roundToInt()}ms")
            appendLine("Latency StdDev: ${metrics.getLatencyStdDevMs().roundToInt()}ms")
            appendLine("95th Percentile Latency: ${metrics.getLatency95thPercentileMs().roundToInt()}ms")
            appendLine("Injection Success Rate: ${metrics.getInjectionSuccessRate().roundToInt()}%")
            appendLine("Events Processed: ${metrics.getEventsProcessed()}")
            appendLine("Events Dropped: ${metrics.getEventsDropped()}")
            appendLine()

            // Configuração atual
            appendLine("--- Current Configuration ---")
            val currentConfig = configManager.configState.value
            appendLine("Profile: ${currentConfig.profileName}")
            appendLine("Deadzone: ${currentConfig.deadzoneThreshold}")
            appendLine("Prediction: ${currentConfig.enablePrediction} (factor: ${currentConfig.predictionFactor})")
            appendLine("Smoothing: ${currentConfig.enableInputSmoothing}")
            appendLine("Max FPS: ${currentConfig.maxFps}")
            appendLine("Watchdog Interval: ${currentConfig.watchdogIntervalMs}ms")
            appendLine("Battery Saver: ${currentConfig.batterySaverMode}")
            appendLine("Performance Mode: ${currentConfig.performanceMode}")
            appendLine("Adaptive Performance: ${currentConfig.adaptivePerformance}")
            appendLine()

            // Perfis
            appendLine("--- Profiles ---")
            val currentProfile = profileManager.getCurrentProfile()
            appendLine("Current Profile: ${currentProfile?.name}")
            appendLine("Player 1 Device: ${currentProfile?.player1Descriptor?.take(20) ?: "None"}")
            appendLine("Player 2 Device: ${currentProfile?.player2Descriptor?.take(20) ?: "None"}")

            val allProfiles = profileManager.getAllProfiles()
            appendLine("Total Profiles: ${allProfiles?.size}")
            appendLine()

            // Logging
            appendLine("--- Logging ---")
            val logFiles = logger.getAllLogFiles()
            appendLine("Log Files: ${logFiles.size}")
            appendLine("Current Log: ${logger.getCurrentLogFilePath()?.takeLast(30) ?: "None"}")
            appendLine()

            appendLine("=== End of Diagnostics ===")
        }

        logger.info("System diagnostics completed", "diagnostics", mapOf(
            "report_size" to diagnosticReport.length,
            "profiles_count" to (profileManager.getAllProfiles()?.size ?: 0),
            "log_files_count" to logger.getAllLogFiles().size
        ))

        println(diagnosticReport)
    }
}

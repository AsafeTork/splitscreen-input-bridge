package com.splitscreen.inputbridge.example

import android.content.Context
import com.splitscreen.inputbridge.InputBridgeServiceEnhanced
import com.splitscreen.inputbridge.config.AdvancedConfigManager
import com.splitscreen.inputbridge.logging.EnhancedStructuredLogger
import com.splitscreen.inputbridge.metrics.PerformanceMetrics
import com.splitscreen.inputbridge.persistence.ProfilePersistenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Exemplo completo de integração demonstrando todas as funcionalidades:
 * 1. Sistema de métricas de performance
 * 2. Logging estruturado com timestamps de alta precisão
 * 3. Sistema de configuração dinâmica
 * 4. Sistema de persistência para múltiplos perfis
 */
class CompleteIntegrationExample {

    /**
     * Demonstração completa de todas as funcionalidades integradas
     */
    fun demonstrateCompleteIntegration(service: InputBridgeServiceEnhanced, context: Context) {
        println("=== Iniciando Demonstração Completa ===")

        // 1. Configurar observadores de métricas
        setupMetricsMonitoring(service)

        // 2. Configurar observadores de configuração
        setupConfigMonitoring(service)

        // 3. Configurar observadores de perfis
        setupProfileMonitoring(service)

        // 4. Demonstrar configuração dinâmica
        demonstrateDynamicConfiguration(service)

        // 5. Demonstrar gerenciamento de perfis
        demonstrateProfileManagement(service)

        // 6. Demonstrar logging avançado
        demonstrateAdvancedLogging(service)

        // 7. Demonstrar monitoramento de performance
        demonstratePerformanceMonitoring(service)

        println("=== Demonstração Completa Concluída ===")
    }

    /**
     * Configura monitoramento contínuo de métricas
     */
    private fun setupMetricsMonitoring(service: InputBridgeServiceEnhanced) {
        val metrics = service.getPerformanceMetrics()
        val logger = service.getStructuredLogger()

        // Loga métricas periodicamente
        val monitoringScope = CoroutineScope(Dispatchers.Default)
        monitoringScope.launch {
            while (true) {
                try {
                    // Gera relatório de métricas
                    val report = metrics.generateMetricsJsonReport()
                    logger.info("Metrics Update", "metrics_monitoring", mapOf(
                        "report" to report,
                        "timestamp" to System.currentTimeMillis()
                    ))

                    // Verifica condições críticas
                    val currentFps = metrics.getCurrentFps()
                    val latency = metrics.getAverageProcessingLatencyMs()
                    val successRate = metrics.getInjectionSuccessRate()

                    if (currentFps < 30) {
                        logger.warn("Low FPS detected", "performance_warning", mapOf(
                            "fps" to currentFps,
                            "latency_ms" to "%.2f".format(latency),
                            "success_rate" to "%.1f".format(successRate)
                        ))
                    }

                    if (successRate < 90.0) {
                        logger.warn("Low injection success rate", "performance_warning", mapOf(
                            "success_rate" to "%.1f".format(successRate),
                            "failed_injections" to metrics.getEventsDropped()
                        ))
                    }

                    // Aguarda 5 segundos antes da próxima verificação
                    Thread.sleep(5000)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    logger.error("Metrics monitoring failed", "monitoring_error", null, e)
                }
            }
        }
    }

    /**
     * Configura monitoramento de mudanças de configuração
     */
    private fun setupConfigMonitoring(service: InputBridgeServiceEnhanced) {
        val configManager = service.getConfigManager()
        val logger = service.getStructuredLogger()

        configManager.configState
            .onEach { configState ->
                logger.info("Configuration Changed", "config_update", mapOf(
                    "profile" to configState.profileName,
                    "deadzone" to configState.deadzoneThreshold,
                    "smoothing" to configState.enableInputSmoothing,
                    "prediction" to configState.enablePrediction,
                    "watchdog_interval" to configState.watchdogIntervalMs,
                    "log_level" to configState.logLevel
                ))

                // Ajusta comportamento com base na configuração
                if (configState.enableInputSmoothing) {
                    logger.debug("Input smoothing enabled", "config_effect")
                } else {
                    logger.debug("Input smoothing disabled", "config_effect")
                }
            }
            .launchIn(CoroutineScope(Dispatchers.Default))
    }

    /**
     * Configura monitoramento de mudanças de perfis
     */
    private fun setupProfileMonitoring(service: InputBridgeServiceEnhanced) {
        val profileManager = service.getProfileManager()
        val logger = service.getStructuredLogger()

        // Observa mudanças no perfil atual
        val monitoringScope = CoroutineScope(Dispatchers.Default)
        monitoringScope.launch {
            var lastProfileName = profileManager.getCurrentProfile()?.name

            while (true) {
                try {
                    val currentProfile = profileManager.getCurrentProfile()

                    if (currentProfile != null && currentProfile.name != lastProfileName) {
                        lastProfileName = currentProfile.name

                        logger.info("Profile Switched", "profile_change", mapOf(
                            "profile_name" to currentProfile.name,
                            "player1_device" to currentProfile.player1Descriptor,
                            "player2_device" to currentProfile.player2Descriptor,
                            "is_default" to currentProfile.isDefault,
                            "config_count" to currentProfile.configPreferences.size
                        ))

                        // Aplica configurações específicas do perfil
                        applyProfileSpecificSettings(service, currentProfile)
                    }

                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    logger.error("Profile monitoring failed", "monitoring_error", null, e)
                }
            }
        }
    }

    /**
     * Aplica configurações específicas do perfil
     */
    private fun applyProfileSpecificSettings(service: InputBridgeServiceEnhanced, profile: ProfilePersistenceManager.UserProfile) {
        val configManager = service.getConfigManager()
        val logger = service.getStructuredLogger()

        // Aplica configurações do perfil
        for ((key, value) in profile.configPreferences) {
            try {
                when (key) {
                    "deadzone_threshold" -> configManager.updateConfig("deadzone_threshold" to ((value as? Number)?.toFloat() ?: 0.15f))
                    "enable_input_smoothing" -> configManager.updateConfig("enable_input_smoothing" to ((value as? Boolean) ?: true))
                    "enable_prediction" -> configManager.updateConfig("enable_prediction" to ((value as? Boolean) ?: true))
                    // Adicione mais configurações conforme necessário
                }
            } catch (e: Exception) {
                logger.error("Failed to apply profile setting", "profile_error", mapOf(
                    "key" to key,
                    "value" to value.toString(),
                    "profile" to profile.name
                ), e)
            }
        }

        logger.info("Profile settings applied", "profile_config", mapOf(
            "profile" to profile.name,
            "settings_count" to profile.configPreferences.size
        ))
    }

    /**
     * Demonstra configuração dinâmica em tempo real
     */
    private fun demonstrateDynamicConfiguration(service: InputBridgeServiceEnhanced) {
        val configManager = service.getConfigManager()
        val logger = service.getStructuredLogger()

        println("\n=== Demonstrando Configuração Dinâmica ===")

        // 1. Obtém configuração atual
        val currentConfig = configManager.configState.value
        logger.info("Current Configuration", "config_demo", mapOf(
            "profile" to currentConfig.profileName,
            "deadzone" to currentConfig.deadzoneThreshold,
            "smoothing" to currentConfig.enableInputSmoothing,
            "prediction" to currentConfig.enablePrediction
        ))

        // 2. Ajusta parâmetros em tempo real
        logger.info("Adjusting deadzone threshold", "config_change")
        configManager.updateConfig("deadzone_threshold" to 0.2f)

        logger.info("Enabling input smoothing", "config_change")
        configManager.updateConfig("enable_input_smoothing" to true)

        logger.info("Adjusting prediction factor", "config_change")
        configManager.updateConfig("prediction_factor" to 0.02f)

        logger.info("Changing watchdog interval", "config_change")
        configManager.updateConfig("watchdog_interval_ms" to 3000L)

        // 3. Cria um novo perfil de configuração
        logger.info("Creating high-performance profile", "profile_creation")
        configManager.createProfile("high_performance")

        // 4. Altera para o novo perfil
        logger.info("Switching to high-performance profile", "profile_switch")
        configManager.switchProfile("high_performance")

        // 5. Ajusta configurações específicas do perfil
        configManager.updateConfig("deadzone_threshold" to 0.1f)
        configManager.updateConfig("enable_prediction" to true)
        configManager.updateConfig("kalman_filter_q" to 0.01)

        println("Configuração dinâmica demonstrada com sucesso!")
    }

    /**
     * Demonstra gerenciamento avançado de perfis
     */
    private fun demonstrateProfileManagement(service: InputBridgeServiceEnhanced) {
        val profileManager = service.getProfileManager()
        val logger = service.getStructuredLogger()

        println("\n=== Demonstrando Gerenciamento de Perfis ===")

        // 1. Cria perfis para diferentes cenários
        logger.info("Creating game-specific profiles", "profile_management")
        profileManager.createProfile("racing_game")
        profileManager.createProfile("fps_game")
        profileManager.createProfile("rpg_game")

        // 2. Configura perfis com parâmetros específicos
        profileManager.updateProfileConfig("racing_game", mapOf(
            "deadzone_threshold" to 0.1f,
            "enable_input_smoothing" to true,
            "enable_prediction" to true,
            "prediction_factor" to 0.03f
        ))

        profileManager.updateProfileConfig("fps_game", mapOf(
            "deadzone_threshold" to 0.2f,
            "enable_input_smoothing" to false,
            "enable_prediction" to false
        ))

        profileManager.updateProfileConfig("rpg_game", mapOf(
            "deadzone_threshold" to 0.15f,
            "enable_input_smoothing" to true,
            "enable_prediction" to true,
            "prediction_factor" to 0.015f
        ))

        // 3. Atualiza descritores de dispositivos para cada perfil
        profileManager.updateProfileDeviceDescriptors("racing_game",
            player1Descriptor = "racing_wheel_1",
            player2Descriptor = "racing_wheel_2"
        )

        profileManager.updateProfileDeviceDescriptors("fps_game",
            player1Descriptor = "xbox_controller_1",
            player2Descriptor = "xbox_controller_2"
        )

        // 4. Lista todos os perfis disponíveis
        val availableProfiles = profileManager.getAllProfiles()
        logger.info("Available Profiles", "profile_list", mapOf(
            "count" to (availableProfiles?.size ?: 0),
            "profiles" to (availableProfiles?.joinToString(", ") { it.name } ?: "none")
        ))

        // 5. Exporta perfis para JSON
        val monitoringScope = CoroutineScope(Dispatchers.IO)
        monitoringScope.launch {
            try {
                val jsonExport = profileManager.exportProfilesToJson()
                logger.info("Profiles Exported", "profile_export", mapOf(
                    "size" to jsonExport.length,
                    "timestamp" to System.currentTimeMillis()
                ))

                // Simula salvamento em arquivo
                saveProfilesToFile(jsonExport)

                // Simula importação de backup
                val importedJson = loadProfilesFromFile()
                if (importedJson != null) {
                    profileManager.importProfilesFromJson(importedJson)
                    logger.info("Profiles Imported", "profile_import", mapOf(
                        "success" to true
                    ))
                }
            } catch (e: Exception) {
                logger.error("Profile export/import failed", "profile_error", null, e)
            }
        }

        println("Gerenciamento de perfis demonstrado com sucesso!")
    }

    /**
     * Demonstra logging estruturado avançado
     */
    private fun demonstrateAdvancedLogging(service: InputBridgeServiceEnhanced) {
        val logger = service.getStructuredLogger()

        println("\n=== Demonstrando Logging Avançado ===")

        // 1. Log de inicialização
        logger.info("Service Initialization", "service_lifecycle", mapOf(
            "version" to "2.1.0",
            "build_type" to "debug",
            "timestamp" to System.currentTimeMillis(),
            "features" to "metrics,logging,config,profiles"
        ))

        // 2. Log de evento de dispositivo
        logger.debug("Input Device Connected", "device_event", mapOf(
            "device_id" to 1,
            "device_name" to "Xbox Wireless Controller",
            "vendor_id" to 1118,
            "product_id" to 654,
            "connection_type" to "Bluetooth"
        ))

        // 3. Log de evento de transformação
        logger.logTransformationEvent(
            axisX = 0.75f,
            axisY = -0.32f,
            touchX = 1280.5f,
            touchY = 1560.2f,
            context = mapOf(
                "device_id" to 2,
                "action" to "move",
                "frame_number" to 12345
            )
        )

        // 4. Log de evento de injeção
        logger.logInjectionEvent(
            success = true,
            deviceDescriptor = "xbox_controller_2",
            latencyMs = 2.87,
            context = mapOf(
                "injection_type" to "touch",
                "target_x" to 1280,
                "target_y" to 1560
            )
        )

        // 5. Log de evento de performance
        logger.logPerformanceEvent("frame_processing", mapOf(
            "frame_time_ms" to 16.7,
            "gpu_time_ms" to 8.3,
            "cpu_time_ms" to 5.2,
            "memory_usage_mb" to 45.8
        ))

        // 6. Log de evento crítico (simulado)
        try {
            simulateCriticalOperation()
        } catch (e: Exception) {
            logger.logCriticalEvent(
                eventType = "critical_failure",
                message = "Critical operation failed",
                context = mapOf(
                    "operation" to "shizuku_connection",
                    "attempt" to 3,
                    "retryable" to true,
                    "recovery_attempted" to true
                ),
                throwable = e
            )
        }

        println("Logging avançado demonstrado com sucesso!")
    }

    /**
     * Demonstra monitoramento de performance em tempo real
     */
    private fun demonstratePerformanceMonitoring(service: InputBridgeServiceEnhanced) {
        val metrics = service.getPerformanceMetrics()
        val logger = service.getStructuredLogger()

        println("\n=== Demonstrando Monitoramento de Performance ===")

        // 1. Obtém métricas em tempo real
        val currentFps = metrics.getCurrentFps()
        val avgLatency = metrics.getAverageProcessingLatencyMs()
        val successRate = metrics.getInjectionSuccessRate()
        val eventsProcessed = metrics.getEventsProcessed()
        val eventsDropped = metrics.getEventsDropped()

        logger.info("Real-time Performance Metrics", "performance_monitor", mapOf(
            "fps" to currentFps,
            "avg_latency_ms" to "%.2f".format(avgLatency),
            "success_rate" to "%.1f".format(successRate),
            "events_processed" to eventsProcessed,
            "events_dropped" to eventsDropped,
            "drop_rate" to if (eventsProcessed > 0) (eventsDropped.toDouble() / eventsProcessed.toDouble() * 100.0).roundToInt() else 0
        ))

        // 2. Gera relatório completo
        val textReport = metrics.generateMetricsReport()
        logger.debug("Metrics Text Report", "performance_report", mapOf(
            "report" to textReport.replace("\n", " | ")
        ))

        val jsonReport = metrics.generateMetricsJsonReport()
        logger.debug("Metrics JSON Report", "performance_report", mapOf(
            "report_length" to jsonReport.length
        ))

        // 3. Analisa métricas avançadas
        val latencyStdDev = metrics.getLatencyStdDevMs()
        val latency95th = metrics.getLatency95thPercentileMs()
        val latency99th = metrics.getLatency99thPercentileMs()
        val avgJitter = metrics.getAverageJitterMs()

        logger.info("Advanced Performance Analysis", "performance_analysis", mapOf(
            "latency_stddev_ms" to "%.2f".format(latencyStdDev),
            "latency_95th_ms" to "%.2f".format(latency95th),
            "latency_99th_ms" to "%.2f".format(latency99th),
            "avg_jitter_ms" to "%.2f".format(avgJitter),
            "total_injections" to metrics.getEventsProcessed(),
            "successful_injections" to metrics.getEventsProcessed() - metrics.getEventsDropped()
        ))

        // 4. Verifica condições de performance
        if (currentFps < 45) {
            logger.warn("Performance Warning: Low FPS", "performance_alert", mapOf(
                "current_fps" to currentFps,
                "threshold" to 45,
                "recommended_action" to "reduce_prediction_factor"
            ))
        }

        if (successRate < 95.0) {
            logger.warn("Performance Warning: Low Success Rate", "performance_alert", mapOf(
                "current_rate" to "%.1f".format(successRate),
                "threshold" to 95.0,
                "recommended_action" to "check_shizuku_connection"
            ))
        }

        if (avgLatency > 10.0) {
            logger.warn("Performance Warning: High Latency", "performance_alert", mapOf(
                "current_latency_ms" to "%.2f".format(avgLatency),
                "threshold_ms" to 10.0,
                "recommended_action" to "optimize_processing_pipeline"
            ))
        }

        println("Monitoramento de performance demonstrado com sucesso!")
    }

    // Funções auxiliares simuladas
    private fun simulateCriticalOperation() {
        throw RuntimeException("Simulated critical failure")
    }

    private fun saveProfilesToFile(json: String) {
        // Simulação de salvamento em arquivo
        println("Profiles saved to file (simulated): ${json.length} bytes")
    }

    private fun loadProfilesFromFile(): String? {
        // Simulação de carregamento de arquivo
        return """{"profiles":[],"current_profile":"default","version":1,"timestamp":${System.currentTimeMillis()}}"""
    }

    /**
     * Demonstração de cenário completo de jogo
     */
    fun demonstrateGameScenario(service: InputBridgeServiceEnhanced) {
        val logger = service.getStructuredLogger()
        val metrics = service.getPerformanceMetrics()
        val configManager = service.getConfigManager()
        val profileManager = service.getProfileManager()

        println("\n=== Demonstrando Cenário Completo de Jogo ===")

        // 1. Configura perfil para jogo de corrida
        logger.info("Setting up for racing game", "game_scenario")
        profileManager.switchProfile("racing_game")

        // 2. Ajusta configurações específicas para corrida
        configManager.updateConfig("deadzone_threshold" to 0.05f) // Menor deadzone para controle preciso
        configManager.updateConfig("prediction_factor" to 0.05f) // Maior previsão para movimento suave
        configManager.updateConfig("enable_input_smoothing" to true)

        // 3. Simula eventos de jogo
        logger.info("Game started", "game_event", mapOf(
            "game_type" to "racing",
            "track" to "monza",
            "players" to 2,
            "difficulty" to "hard"
        ))

        // Simula processamento de frames
        for (frame in 1..100) {
            // Simula processamento de entrada
            metrics.onEventProcessingStarted()

            // Simula transformação de entrada
            val axisX = (Math.random() * 2 - 1).toFloat() // -1.0 a 1.0
            val axisY = (Math.random() * 2 - 1).toFloat() // -1.0 a 1.0
            val touchX = ((axisX + 1.0f) / 2.0f) * 1920
            val touchY = 1080f + ((axisY + 1.0f) / 2.0f) * 540

            logger.logTransformationEvent(axisX, axisY, touchX, touchY, mapOf(
                "frame" to frame,
                "game_time_ms" to frame * 16
            ))

            // Simula injeção
            val injectionSuccess = Math.random() > 0.05 // 95% de sucesso
            if (injectionSuccess) {
                metrics.recordSuccessfulInjection()
                logger.logInjectionEvent(true, "racing_wheel", 2.5)
            } else {
                metrics.recordFailedInjection()
                logger.logInjectionEvent(false, "racing_wheel", 2.5)
            }

            // Simula latência
            val processingLatency = (Math.random() * 5 + 1).toLong() * 1_000_000 // 1-6ms
            val injectionLatency = (Math.random() * 3 + 1).toLong() * 1_000_000 // 1-4ms

            metrics.recordProcessingLatency(System.nanoTime() - processingLatency, System.nanoTime())
            metrics.recordInjectionLatency(System.nanoTime() - injectionLatency, System.nanoTime())
            metrics.recordFrameTime(System.nanoTime())

            // Loga métricas periodicamente
            if (frame % 30 == 0) {
                logger.logPerformanceEvent("game_frame_batch", mapOf(
                    "batch_frames" to 30,
                    "current_fps" to metrics.getCurrentFps(),
                    "avg_latency_ms" to "%.2f".format(metrics.getAverageProcessingLatencyMs())
                ))
            }

            // Simula pequena pausa
            Thread.sleep(16) // ~60 FPS
        }

        // 4. Finaliza o jogo
        logger.info("Game completed", "game_event", mapOf(
            "duration_seconds" to 100 * 0.016,
            "final_fps" to metrics.getCurrentFps(),
            "avg_latency_ms" to "%.2f".format(metrics.getAverageProcessingLatencyMs()),
            "success_rate" to "%.1f".format(metrics.getInjectionSuccessRate())
        ))

        // 5. Gera relatório final
        val finalReport = metrics.generateMetricsReport()
        logger.info("Final Game Report", "game_report", mapOf(
            "report" to finalReport.replace("\n", " | ")
        ))

        // 6. Reseta para configuração padrão
        configManager.switchProfile("default")
        logger.info("Reset to default configuration", "config_reset")

        println("Cenário completo de jogo demonstrado com sucesso!")
    }

    /**
     * Demonstração de recuperação de falhas
     */
    fun demonstrateFailureRecovery(service: InputBridgeServiceEnhanced) {
        val logger = service.getStructuredLogger()
        val metrics = service.getPerformanceMetrics()
        val configManager = service.getConfigManager()

        println("\n=== Demonstrando Recuperação de Falhas ===")

        // 1. Simula falha de conexão Shizuku
        logger.info("Simulating Shizuku connection failure", "test_scenario")

        try {
            simulateShizukuFailure()
        } catch (e: Exception) {
            logger.logCriticalEvent(
                eventType = "shizuku_failure",
                message = "Shizuku connection lost",
                context = mapOf(
                    "attempt" to 1,
                    "recovery_attempted" to true,
                    "fallback_enabled" to true
                ),
                throwable = e
            )

            // 2. Aplica configuração de fallback
            logger.info("Applying fallback configuration", "recovery_action")
            configManager.updateConfig("enable_prediction" to false)
            configManager.updateConfig("enable_input_smoothing" to false)

            // 3. Tenta reconectar
            logger.info("Attempting to reconnect", "recovery_action")
            Thread.sleep(2000) // Simula tempo de reconexão

            // 4. Restaura configuração original
            logger.info("Restoring original configuration", "recovery_success")
            configManager.updateConfig("enable_prediction" to true)
            configManager.updateConfig("enable_input_smoothing" to true)

            logger.info("Recovery completed successfully", "recovery_success")
        }

        println("Recuperação de falhas demonstrada com sucesso!")
    }

    private fun simulateShizukuFailure() {
        throw RuntimeException("Shizuku service not responding")
    }

    /**
     * Demonstração de otimização de performance
     */
    fun demonstratePerformanceOptimization(service: InputBridgeServiceEnhanced) {
        val logger = service.getStructuredLogger()
        val metrics = service.getPerformanceMetrics()
        val configManager = service.getConfigManager()

        println("\n=== Demonstrando Otimização de Performance ===")

        // 1. Obtém métricas atuais
        val initialFps = metrics.getCurrentFps()
        val initialLatency = metrics.getAverageProcessingLatencyMs()
        val initialSuccessRate = metrics.getInjectionSuccessRate()

        logger.info("Initial Performance Metrics", "optimization_start", mapOf(
            "fps" to initialFps,
            "latency_ms" to "%.2f".format(initialLatency),
            "success_rate" to "%.1f".format(initialSuccessRate)
        ))

        // 2. Aplica otimizações
        logger.info("Applying performance optimizations", "optimization_action")

        // Reduz deadzone para melhor responsividade
        configManager.updateConfig("deadzone_threshold" to 0.1f)

        // Ajusta filtro Kalman para melhor balanceamento
        configManager.updateConfig("kalman_filter_q" to 0.01)
        configManager.updateConfig("kalman_filter_r" to 0.08)

        // Aumenta prioridade de injeção
        configManager.updateConfig("injection_priority" to android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)

        // 3. Simula melhoria de performance
        Thread.sleep(1000)

        // 4. Obtém métricas após otimização
        val optimizedFps = initialFps + 15 // Simula melhoria
        val optimizedLatency = (initialLatency * 0.8).coerceAtLeast(1.0) // 20% melhor
        val optimizedSuccessRate = (initialSuccessRate + 5).coerceAtMost(100.0) // 5% melhor

        logger.info("Optimized Performance Metrics", "optimization_result", mapOf(
            "fps" to optimizedFps,
            "latency_ms" to "%.2f".format(optimizedLatency),
            "success_rate" to "%.1f".format(optimizedSuccessRate),
            "fps_improvement" to "${optimizedFps - initialFps} (${(optimizedFps.toDouble() / initialFps.toDouble() * 100 - 100).roundToInt()}%)",
            "latency_reduction" to "${(initialLatency - optimizedLatency).roundToInt()}ms (${(1 - optimizedLatency / initialLatency) * 100}.roundToInt()%)"
        ))

        // 5. Salva configuração otimizada
        configManager.saveConfig()
        logger.info("Optimized configuration saved", "optimization_success")

        println("Otimização de performance demonstrada com sucesso!")
    }
}
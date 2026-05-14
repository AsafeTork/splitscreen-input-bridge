package com.splitscreen.inputbridge.example

import android.content.Context
import com.splitscreen.inputbridge.InputBridgeServiceEnhanced
import com.splitscreen.inputbridge.config.AdvancedConfigManager
import com.splitscreen.inputbridge.persistence.ProfilePersistenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Exemplos de uso das novas funcionalidades implementadas
 */
class UsageExample {

    /**
     * Exemplo 1: Configuração dinâmica de parâmetros
     */
    fun configureRuntimeParameters(service: InputBridgeServiceEnhanced) {
        val configManager = service.getConfigManager()

        // Atualiza a zona morta (deadzone) para 0.2
        configManager.updateConfig("deadzone_threshold" to 0.2f)

        // Desativa a previsão de movimento
        configManager.updateConfig("enable_prediction" to false)

        // Aumenta o fator de previsão
        configManager.updateConfig("prediction_factor" to 0.02f)

        // Ajusta os parâmetros do filtro Kalman
        configManager.updateConfig("kalman_filter_q" to 0.01)
        configManager.updateConfig("kalman_filter_r" to 0.05)

        // Ajusta o intervalo do watchdog
        configManager.updateConfig("watchdog_interval_ms" to 3000L)
    }

    /**
     * Exemplo 2: Gerenciamento de perfis
     */
    fun manageProfiles(service: InputBridgeServiceEnhanced, context: Context) {
        val profileManager = service.getProfileManager()

        // Cria um novo perfil chamado "high_performance"
        profileManager.createProfile("high_performance")

        // Cria um perfil para jogos de corrida
        profileManager.createProfile("racing_game")

        // Altera para o perfil "high_performance"
        profileManager.switchProfile("high_performance")

        // Atualiza configurações específicas do perfil
        profileManager.updateProfileConfig("high_performance", mapOf(
            "deadzone_threshold" to 0.1f,
            "enable_input_smoothing" to true,
            "enable_prediction" to true
        ))

        // Atualiza os descritores de dispositivo para o perfil
        profileManager.updateProfileDeviceDescriptors("high_performance",
            player1Descriptor = "device1_descriptor",
            player2Descriptor = "device2_descriptor"
        )

        // Exporta todos os perfis para JSON
        val exportScope = CoroutineScope(Dispatchers.IO)
        exportScope.launch {
            val jsonExport = profileManager.exportProfilesToJson()
            // Salvar jsonExport em um arquivo ou enviar para nuvem
        }

        // Importa perfis de um JSON
        val jsonString = """{"profiles": [], "current_profile": "high_performance"}"""
        profileManager.importProfilesFromJson(jsonString)

        // Obtém todos os perfis disponíveis
        val allProfiles = profileManager.getAllProfiles()
        allProfiles?.forEach { profile ->
            println("Profile: ${profile.name}, Devices: P1=${profile.player1Descriptor}, P2=${profile.player2Descriptor}")
        }
    }

    /**
     * Exemplo 3: Monitoramento de métricas de performance
     */
    fun monitorPerformance(service: InputBridgeServiceEnhanced) {
        val metrics = service.getPerformanceMetrics()
        val logger = service.getStructuredLogger()

        // Obtém métricas em tempo real
        val currentFps = metrics.getCurrentFps()
        val avgLatency = metrics.getAverageProcessingLatencyMs()
        val successRate = metrics.getInjectionSuccessRate()

        logger.info("Performance Monitor", "metrics_monitor", mapOf(
            "fps" to currentFps,
            "latency_ms" to "%.2f".format(avgLatency),
            "success_rate" to "%.1f".format(successRate),
            "events_processed" to metrics.getEventsProcessed(),
            "events_dropped" to metrics.getEventsDropped()
        ))

        // Gera um relatório completo de métricas
        val metricsReport = metrics.generateMetricsReport()
        println("Metrics Report:\n$metricsReport")

        // Loga métricas automaticamente (já é feito pelo serviço)
        metrics.logMetrics()
    }

    /**
     * Exemplo 4: Logging estruturado avançado
     */
    fun advancedLogging(service: InputBridgeServiceEnhanced) {
        val logger = service.getStructuredLogger()

        // Log de informações
        logger.info("Service initialized", "service_lifecycle", mapOf(
            "version" to "2.0.0",
            "build" to "debug",
            "timestamp" to System.currentTimeMillis()
        ))

        // Log de eventos de dispositivo
        logger.debug("Gamepad connected", "device_event", mapOf(
            "device_id" to 1,
            "device_name" to "Xbox Controller",
            "vendor_id" to 1118,
            "product_id" to 654
        ))

        // Log de erros com contexto
        try {
            // Alguma operação que pode falhar
            riskyOperation()
        } catch (e: Exception) {
            logger.error("Operation failed", "operation_error", mapOf(
                "operation" to "device_connection",
                "attempt" to 3,
                "retryable" to true
            ), e)
        }

        // Log de eventos de performance
        logger.logPerformanceEvent("frame_processing", mapOf(
            "frame_time_ms" to 16.7,
            "gpu_time_ms" to 8.3,
            "cpu_time_ms" to 5.2
        ))
    }

    /**
     * Exemplo 5: Integração completa
     */
    fun fullIntegrationExample(service: InputBridgeServiceEnhanced) {
        // 1. Configurar parâmetros dinâmicos
        configureRuntimeParameters(service)

        // 2. Configurar perfis
        manageProfiles(service, service.applicationContext)

        // 3. Monitorar performance
        monitorPerformance(service)

        // 4. Logging avançado
        advancedLogging(service)

        // 5. Responder a mudanças de configuração
        val configManager = service.getConfigManager()
        val configScope = CoroutineScope(Dispatchers.Default)

        configScope.launch {
            configManager.configState.collect { configState ->
                // Reage a mudanças na configuração
                println("Config updated: deadzone=${configState.deadzoneThreshold}, " +
                        "smoothing=${configState.enableInputSmoothing}")
            }
        }
    }

    private fun riskyOperation() {
        throw RuntimeException("Simulated error")
    }

    /**
     * Exemplo 6: Persistência e recuperação de estado
     */
    fun statePersistenceExample(service: InputBridgeServiceEnhanced) {
        val profileManager = service.getProfileManager()
        val configManager = service.getConfigManager()

        // Salvar estado atual
        profileManager.saveProfile(profileManager.getCurrentProfile()!!)
        configManager.saveConfig()

        // Recarregar estado
        profileManager.loadProfiles()
        configManager.loadConfig()

        // Exportar para backup
        val exportScope = CoroutineScope(Dispatchers.IO)
        exportScope.launch {
            val backupJson = profileManager.exportProfilesToJson()
            // Salvar em arquivo ou nuvem
            saveToCloud(backupJson)
        }

        // Importar de backup
        val backupJson = loadFromCloud()
        if (backupJson != null) {
            profileManager.importProfilesFromJson(backupJson)
        }
    }

    private fun saveToCloud(json: String) {
        // Implementação de salvamento em nuvem
    }

    private fun loadFromCloud(): String? {
        // Implementação de carregamento da nuvem
        return null
    }
}
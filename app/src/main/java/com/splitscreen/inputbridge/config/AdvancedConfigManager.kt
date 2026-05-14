package com.splitscreen.inputbridge.config

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.WorkerThread
import com.splitscreen.inputbridge.logging.EnhancedStructuredLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sistema de configuração dinâmica avançado com suporte para perfis,
 * ajustes em runtime e otimização automática de performance
 */
class AdvancedConfigManager(private val context: Context, private val logger: EnhancedStructuredLogger) {

    companion object {
        private const val PREFS_NAME = "InputBridgeAdvancedConfig"
        private const val DEFAULT_PROFILE = "default"
    }

    // Configurações atuais
    private val _configState = MutableStateFlow<ConfigState>(ConfigState.empty())
    val configState: StateFlow<ConfigState> = _configState.asStateFlow()

    // Estado de carregamento
    private val isLoading = AtomicBoolean(false)

    // SharedPreferences para persistência
    private val sharedPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Coroutine scope
    private val configScope = CoroutineScope(Dispatchers.Default)

    // Estado de otimização automática
    private val autoOptimizationEnabled = AtomicBoolean(false)

    /**
     * Estado atual da configuração
     */
    data class ConfigState(
        val profileName: String,
        val deadzoneThreshold: Float,
        val predictionFactor: Float,
        val kalmanFilterQ: Double,
        val kalmanFilterR: Double,
        val watchdogIntervalMs: Long,
        val adaptiveWatchdogEnabled: Boolean,
        val lowBatteryThreshold: Int,
        val injectionPriority: Int,
        val maxFps: Int,
        val enableInputSmoothing: Boolean,
        val enablePrediction: Boolean,
        val logLevel: String,
        val metricsCollectionEnabled: Boolean,
        val batterySaverMode: Boolean = false,
        val performanceMode: Boolean = false,
        val adaptivePerformance: Boolean = true,
        val autoOptimize: Boolean = false,
        val latencyCompensation: Float = 0.0f,
        val framePrediction: Boolean = false,
        val adaptiveDeadzone: Boolean = false,
        val dynamicPriorityAdjustment: Boolean = false,
        val networkMonitoringEnabled: Boolean = false
    ) {
        companion object {
            fun empty() = ConfigState(
                profileName = DEFAULT_PROFILE,
                deadzoneThreshold = 0.15f,
                predictionFactor = 0.016f,
                kalmanFilterQ = 0.02,
                kalmanFilterR = 0.1,
                watchdogIntervalMs = 5000L,
                adaptiveWatchdogEnabled = true,
                lowBatteryThreshold = 20,
                injectionPriority = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY,
                maxFps = 60,
                enableInputSmoothing = true,
                enablePrediction = true,
                logLevel = "INFO",
                metricsCollectionEnabled = true,
                batterySaverMode = false,
                performanceMode = false,
                adaptivePerformance = true,
                autoOptimize = false,
                latencyCompensation = 0.0f,
                framePrediction = false,
                adaptiveDeadzone = false,
                dynamicPriorityAdjustment = false,
                networkMonitoringEnabled = false
            )
        }

        fun toMap(): Map<String, Any> {
            return mapOf(
                "profile_name" to profileName,
                "deadzone_threshold" to deadzoneThreshold,
                "prediction_factor" to predictionFactor,
                "kalman_filter_q" to kalmanFilterQ,
                "kalman_filter_r" to kalmanFilterR,
                "watchdog_interval_ms" to watchdogIntervalMs,
                "adaptive_watchdog_enabled" to adaptiveWatchdogEnabled,
                "low_battery_threshold" to lowBatteryThreshold,
                "injection_priority" to injectionPriority,
                "max_fps" to maxFps,
                "enable_input_smoothing" to enableInputSmoothing,
                "enable_prediction" to enablePrediction,
                "log_level" to logLevel,
                "metrics_collection_enabled" to metricsCollectionEnabled,
                "battery_saver_mode" to batterySaverMode,
                "performance_mode" to performanceMode,
                "adaptive_performance" to adaptivePerformance,
                "auto_optimize" to autoOptimize,
                "latency_compensation" to latencyCompensation,
                "frame_prediction" to framePrediction,
                "adaptive_deadzone" to adaptiveDeadzone,
                "dynamic_priority_adjustment" to dynamicPriorityAdjustment,
                "network_monitoring_enabled" to networkMonitoringEnabled
            )
        }

        fun validate(): Boolean {
            return deadzoneThreshold in 0.0f..1.0f &&
                   predictionFactor >= 0.0f &&
                   kalmanFilterQ > 0.0 &&
                   kalmanFilterR > 0.0 &&
                   watchdogIntervalMs > 0 &&
                   lowBatteryThreshold in 0..100 &&
                   maxFps > 0 &&
                   injectionPriority in android.os.Process.THREAD_PRIORITY_LOWEST..android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY &&
                   latencyCompensation >= 0.0f
        }

        fun isPerformanceMode(): Boolean {
            return performanceMode || autoOptimize
        }

        fun isBatterySaverMode(): Boolean {
            return batterySaverMode
        }
    }

    init {
        // Carrega a configuração inicial
        loadConfig()
    }

    /**
     * Carrega a configuração do perfil atual
     */
    fun loadConfig() {
        if (isLoading.getAndSet(true)) return

        configScope.launch {
            try {
                val currentProfile = getCurrentProfileName()
                val config = loadProfileConfig(currentProfile)
                _configState.value = config
                logger.info("Configuration loaded", "config_loaded", mapOf("profile" to currentProfile))
            } catch (e: Exception) {
                logger.error("Failed to load configuration", "config_error", null, e)
                // Carrega configuração padrão em caso de erro
                _configState.value = ConfigState.empty()
            } finally {
                isLoading.set(false)
            }
        }
    }

    /**
     * Salva a configuração atual
     */
    fun saveConfig() {
        configScope.launch {
            try {
                val currentConfig = _configState.value
                saveProfileConfig(currentConfig.profileName, currentConfig)
                logger.info("Configuration saved", "config_saved", mapOf("profile" to currentConfig.profileName))
            } catch (e: Exception) {
                logger.error("Failed to save configuration", "config_error", null, e)
            }
        }
    }

    /**
     * Atualiza uma configuração específica
     */
    fun updateConfig(vararg updates: Pair<String, Any>) {
        configScope.launch {
            try {
                val currentConfig = _configState.value
                val updatedConfig = when (val key = updates[0].first) {
                    "deadzone_threshold" -> currentConfig.copy(deadzoneThreshold = updates[0].second as Float)
                    "prediction_factor" -> currentConfig.copy(predictionFactor = updates[0].second as Float)
                    "kalman_filter_q" -> currentConfig.copy(kalmanFilterQ = updates[0].second as Double)
                    "kalman_filter_r" -> currentConfig.copy(kalmanFilterR = updates[0].second as Double)
                    "watchdog_interval_ms" -> currentConfig.copy(watchdogIntervalMs = updates[0].second as Long)
                    "adaptive_watchdog_enabled" -> currentConfig.copy(adaptiveWatchdogEnabled = updates[0].second as Boolean)
                    "low_battery_threshold" -> currentConfig.copy(lowBatteryThreshold = updates[0].second as Int)
                    "injection_priority" -> currentConfig.copy(injectionPriority = updates[0].second as Int)
                    "max_fps" -> currentConfig.copy(maxFps = updates[0].second as Int)
                    "enable_input_smoothing" -> currentConfig.copy(enableInputSmoothing = updates[0].second as Boolean)
                    "enable_prediction" -> currentConfig.copy(enablePrediction = updates[0].second as Boolean)
                    "log_level" -> currentConfig.copy(logLevel = updates[0].second as String)
                    "metrics_collection_enabled" -> currentConfig.copy(metricsCollectionEnabled = updates[0].second as Boolean)
                    "battery_saver_mode" -> currentConfig.copy(batterySaverMode = updates[0].second as Boolean)
                    "performance_mode" -> currentConfig.copy(performanceMode = updates[0].second as Boolean)
                    "adaptive_performance" -> currentConfig.copy(adaptivePerformance = updates[0].second as Boolean)
                    "auto_optimize" -> currentConfig.copy(autoOptimize = updates[0].second as Boolean)
                    "latency_compensation" -> currentConfig.copy(latencyCompensation = updates[0].second as Float)
                    "frame_prediction" -> currentConfig.copy(framePrediction = updates[0].second as Boolean)
                    "adaptive_deadzone" -> currentConfig.copy(adaptiveDeadzone = updates[0].second as Boolean)
                    "dynamic_priority_adjustment" -> currentConfig.copy(dynamicPriorityAdjustment = updates[0].second as Boolean)
                    "network_monitoring_enabled" -> currentConfig.copy(networkMonitoringEnabled = updates[0].second as Boolean)
                    else -> currentConfig
                }

                // Valida a configuração antes de aplicar
                if (!updatedConfig.validate()) {
                    logger.error("Invalid configuration values", "config_validation_error", mapOf(
                        "key" to updates[0].first,
                        "value" to updates[0].second.toString()
                    ))
                    return@launch
                }

                _configState.value = updatedConfig
                saveConfig()

                logger.info("Configuration updated", "config_updated", mapOf(
                    "key" to updates[0].first,
                    "value" to updates[0].second.toString(),
                    "profile" to updatedConfig.profileName
                ))

                // Aplica otimizações automáticas se habilitado
                if (updatedConfig.autoOptimize) {
                    applyAutoOptimization()
                }
            } catch (e: Exception) {
                logger.error("Failed to update configuration", "config_error", mapOf("key" to updates[0].first), e)
            }
        }
    }

    /**
     * Aplica otimizações automáticas com base nas métricas de performance
     */
    fun applyAutoOptimization() {
        configScope.launch {
            try {
                val currentConfig = _configState.value
                if (!currentConfig.autoOptimize) return@launch

                // Aqui você integraria com as métricas de performance para ajustar dinamicamente
                // Por enquanto, aplicamos configurações balanceadas
                val optimizedConfig = currentConfig.copy(
                    injectionPriority = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY,
                    enableInputSmoothing = true,
                    predictionFactor = 0.018f,
                    deadzoneThreshold = 0.12f,
                    kalmanFilterQ = 0.015
                )

                if (!optimizedConfig.validate()) {
                    logger.error("Invalid optimized configuration", "config_validation_error")
                    return@launch
                }

                _configState.value = optimizedConfig
                saveConfig()

                logger.info("Auto-optimization applied", "config_optimization", mapOf(
                    "profile" to optimizedConfig.profileName,
                    "changes" to "injection_priority, prediction_factor, deadzone_threshold"
                ))
            } catch (e: Exception) {
                logger.error("Failed to apply auto-optimization", "config_error", null, e)
            }
        }
    }

    /**
     * Carrega a configuração de um perfil específico
     */
    @WorkerThread
    private suspend fun loadProfileConfig(profileName: String): ConfigState = withContext(Dispatchers.IO) {
        val prefs = sharedPrefs
        val profileKey = "profile_$profileName"

        val jsonString = prefs.getString(profileKey, null)
        if (jsonString != null) {
            try {
                val json = JSONObject(jsonString)
                ConfigState(
                    profileName = profileName,
                    deadzoneThreshold = json.optDouble("deadzone_threshold", 0.15).toFloat(),
                    predictionFactor = json.optDouble("prediction_factor", 0.016).toFloat(),
                    kalmanFilterQ = json.optDouble("kalman_filter_q", 0.02),
                    kalmanFilterR = json.optDouble("kalman_filter_r", 0.1),
                    watchdogIntervalMs = json.optLong("watchdog_interval_ms", 5000),
                    adaptiveWatchdogEnabled = json.optBoolean("adaptive_watchdog_enabled", true),
                    lowBatteryThreshold = json.optInt("low_battery_threshold", 20),
                    injectionPriority = json.optInt("injection_priority", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY),
                    maxFps = json.optInt("max_fps", 60),
                    enableInputSmoothing = json.optBoolean("enable_input_smoothing", true),
                    enablePrediction = json.optBoolean("enable_prediction", true),
                    logLevel = json.optString("log_level", "INFO"),
                    metricsCollectionEnabled = json.optBoolean("metrics_collection_enabled", true),
                    batterySaverMode = json.optBoolean("battery_saver_mode", false),
                    performanceMode = json.optBoolean("performance_mode", false),
                    adaptivePerformance = json.optBoolean("adaptive_performance", true),
                    autoOptimize = json.optBoolean("auto_optimize", false),
                    latencyCompensation = json.optDouble("latency_compensation", 0.0).toFloat(),
                    framePrediction = json.optBoolean("frame_prediction", false),
                    adaptiveDeadzone = json.optBoolean("adaptive_deadzone", false),
                    dynamicPriorityAdjustment = json.optBoolean("dynamic_priority_adjustment", false),
                    networkMonitoringEnabled = json.optBoolean("network_monitoring_enabled", false)
                )
            } catch (e: Exception) {
                logger.error("Failed to parse profile config", "config_parse_error", mapOf("profile" to profileName), e)
                ConfigState.empty().copy(profileName = profileName)
            }
        } else {
            // Se o perfil não existir, cria um novo com valores padrão
            ConfigState.empty().copy(profileName = profileName)
        }
    }

    /**
     * Salva a configuração de um perfil
     */
    @WorkerThread
    private suspend fun saveProfileConfig(profileName: String, config: ConfigState) = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("deadzone_threshold", config.deadzoneThreshold.toDouble())
            put("prediction_factor", config.predictionFactor.toDouble())
            put("kalman_filter_q", config.kalmanFilterQ)
            put("kalman_filter_r", config.kalmanFilterR)
            put("watchdog_interval_ms", config.watchdogIntervalMs)
            put("adaptive_watchdog_enabled", config.adaptiveWatchdogEnabled)
            put("low_battery_threshold", config.lowBatteryThreshold)
            put("injection_priority", config.injectionPriority)
            put("max_fps", config.maxFps)
            put("enable_input_smoothing", config.enableInputSmoothing)
            put("enable_prediction", config.enablePrediction)
            put("log_level", config.logLevel)
            put("metrics_collection_enabled", config.metricsCollectionEnabled)
            put("battery_saver_mode", config.batterySaverMode)
            put("performance_mode", config.performanceMode)
            put("adaptive_performance", config.adaptivePerformance)
            put("auto_optimize", config.autoOptimize)
            put("latency_compensation", config.latencyCompensation.toDouble())
            put("frame_prediction", config.framePrediction)
            put("adaptive_deadzone", config.adaptiveDeadzone)
            put("dynamic_priority_adjustment", config.dynamicPriorityAdjustment)
            put("network_monitoring_enabled", config.networkMonitoringEnabled)
        }

        sharedPrefs.edit()
            .putString("profile_$profileName", json.toString())
            .putString("current_profile", profileName)
            .apply()
    }

    /**
     * Obtém o nome do perfil atual
     */
    private fun getCurrentProfileName(): String {
        return sharedPrefs.getString("current_profile", DEFAULT_PROFILE) ?: DEFAULT_PROFILE
    }

    /**
     * Altera para um perfil diferente
     */
    fun switchProfile(profileName: String) {
        configScope.launch {
            try {
                // Salva a configuração atual antes de mudar
                val currentConfig = _configState.value
                saveProfileConfig(currentConfig.profileName, currentConfig)

                // Carrega o novo perfil
                val newConfig = loadProfileConfig(profileName)
                _configState.value = newConfig

                logger.info("Profile switched", "profile_switch", mapOf(
                    "from" to currentConfig.profileName,
                    "to" to profileName
                ))
            } catch (e: Exception) {
                logger.error("Failed to switch profile", "profile_error", mapOf("profile" to profileName), e)
            }
        }
    }

    /**
     * Cria um novo perfil baseado no perfil atual
     */
    fun createProfile(newProfileName: String) {
        configScope.launch {
            try {
                val currentConfig = _configState.value
                val newConfig = currentConfig.copy(profileName = newProfileName)

                saveProfileConfig(newProfileName, newConfig)
                logger.info("Profile created", "profile_created", mapOf("profile" to newProfileName))
            } catch (e: Exception) {
                logger.error("Failed to create profile", "profile_error", mapOf("profile" to newProfileName), e)
            }
        }
    }

    /**
     * Deleta um perfil
     */
    fun deleteProfile(profileName: String) {
        if (profileName == DEFAULT_PROFILE) {
            logger.warn("Cannot delete default profile", "profile_error")
            return
        }

        configScope.launch {
            try {
                sharedPrefs.edit()
                    .remove("profile_$profileName")
                    .apply()

                // Se o perfil atual for deletado, volta para o padrão
                val currentProfile = getCurrentProfileName()
                if (currentProfile == profileName) {
                    sharedPrefs.edit()
                        .putString("current_profile", DEFAULT_PROFILE)
                        .apply()
                    loadConfig()
                }

                logger.info("Profile deleted", "profile_deleted", mapOf("profile" to profileName))
            } catch (e: Exception) {
                logger.error("Failed to delete profile", "profile_error", mapOf("profile" to profileName), e)
            }
        }
    }

    /**
     * Obtém a lista de todos os perfis disponíveis
     */
    fun getAvailableProfiles(): List<String> {
        val profiles = mutableListOf<String>()
        profiles.add(DEFAULT_PROFILE) // Sempre inclui o perfil padrão

        val allEntries = sharedPrefs.all
        for ((key, _) in allEntries) {
            if (key.startsWith("profile_") && key != "profile_$DEFAULT_PROFILE") {
                val profileName = key.removePrefix("profile_")
                profiles.add(profileName)
            }
        }

        return profiles.sorted()
    }

    /**
     * Reseta todas as configurações para os valores padrão
     */
    fun resetToDefaults() {
        configScope.launch {
            try {
                val currentProfile = _configState.value.profileName
                val defaultConfig = ConfigState.empty().copy(profileName = currentProfile)

                _configState.value = defaultConfig
                saveConfig()

                logger.info("Configuration reset to defaults", "config_reset")
            } catch (e: Exception) {
                logger.error("Failed to reset configuration", "config_error", null, e)
            }
        }
    }

    /**
     * Limpa o cache e recarrega a configuração
     */
    fun refresh() {
        loadConfig()
    }

    /**
     * Aplica configurações de performance com base no modo atual
     */
    fun applyPerformanceModeSettings() {
        configScope.launch {
            try {
                val currentConfig = _configState.value
                val updatedConfig = if (currentConfig.performanceMode || currentConfig.autoOptimize) {
                    // Modo performance: prioriza baixa latência
                    currentConfig.copy(
                        injectionPriority = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY,
                        enableInputSmoothing = false, // Desativa smoothing para menor latência
                        predictionFactor = 0.02f, // Aumenta a predição
                        watchdogIntervalMs = 2000L, // Watchdog mais frequente
                        deadzoneThreshold = 0.1f, // Deadzone menor para mais sensibilidade
                        kalmanFilterQ = 0.01, // Filtro mais agressivo
                        latencyCompensation = 0.5f,
                        framePrediction = true,
                        dynamicPriorityAdjustment = true
                    )
                } else {
                    // Modo padrão: balanceado
                    currentConfig.copy(
                        injectionPriority = android.os.Process.THREAD_PRIORITY_DEFAULT,
                        enableInputSmoothing = true,
                        predictionFactor = 0.016f,
                        watchdogIntervalMs = 5000L,
                        deadzoneThreshold = 0.15f,
                        kalmanFilterQ = 0.02,
                        latencyCompensation = 0.0f,
                        framePrediction = false,
                        dynamicPriorityAdjustment = false
                    )
                }

                // Valida antes de aplicar
                if (!updatedConfig.validate()) {
                    logger.error("Invalid performance mode configuration", "config_validation_error")
                    return@launch
                }

                _configState.value = updatedConfig
                saveConfig()

                logger.info("Performance mode settings applied", "config_performance", mapOf(
                    "performance_mode" to currentConfig.performanceMode.toString(),
                    "auto_optimize" to currentConfig.autoOptimize.toString()
                ))
            } catch (e: Exception) {
                logger.error("Failed to apply performance mode settings", "config_error", null, e)
            }
        }
    }

    /**
     * Aplica configurações de economia de bateria
     */
    fun applyBatterySaverSettings() {
        configScope.launch {
            try {
                val currentConfig = _configState.value
                val updatedConfig = if (currentConfig.batterySaverMode) {
                    // Modo economia de bateria
                    currentConfig.copy(
                        watchdogIntervalMs = 10000L, // Watchdog menos frequente
                        adaptiveWatchdogEnabled = true,
                        enablePrediction = false, // Desativa predição para economizar CPU
                        enableInputSmoothing = true,
                        injectionPriority = android.os.Process.THREAD_PRIORITY_DEFAULT,
                        maxFps = 30,
                        networkMonitoringEnabled = false
                    )
                } else {
                    // Configurações normais
                    currentConfig.copy(
                        watchdogIntervalMs = 5000L,
                        enablePrediction = true,
                        maxFps = 60,
                        networkMonitoringEnabled = false
                    )
                }

                // Valida antes de aplicar
                if (!updatedConfig.validate()) {
                    logger.error("Invalid battery saver configuration", "config_validation_error")
                    return@launch
                }

                _configState.value = updatedConfig
                saveConfig()

                logger.info("Battery saver settings applied", "config_battery_saver", mapOf(
                    "battery_saver_mode" to currentConfig.batterySaverMode.toString()
                ))
            } catch (e: Exception) {
                logger.error("Failed to apply battery saver settings", "config_error", null, e)
            }
        }
    }

    /**
     * Habilita/desabilita otimização automática
     */
    fun setAutoOptimizationEnabled(enabled: Boolean) {
        configScope.launch {
            try {
                val currentConfig = _configState.value
                val updatedConfig = currentConfig.copy(autoOptimize = enabled)

                _configState.value = updatedConfig
                saveConfig()

                if (enabled) {
                    applyAutoOptimization()
                }

                logger.info("Auto-optimization ${if (enabled) "enabled" else "disabled"}", "config_auto_optimize", mapOf(
                    "enabled" to enabled.toString()
                ))
            } catch (e: Exception) {
                logger.error("Failed to set auto-optimization", "config_error", null, e)
            }
        }
    }

    /**
     * Libera recursos
     */
    fun cleanup() {
        configScope.coroutineContext.cancelChildren()
    }

    /**
     * Obtém a configuração atual como mapa
     */
    fun getCurrentConfigMap(): Map<String, Any> {
        return _configState.value.toMap()
    }

    /**
     * Exporta a configuração atual para JSON
     */
    fun exportConfigToJson(): String {
        val config = _configState.value
        return JSONObject(config.toMap()).toString(2)
    }

    /**
     * Importa configuração de JSON
     */
    fun importConfigFromJson(jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)
            val profileName = json.optString("profile_name", DEFAULT_PROFILE)

            configScope.launch {
                try {
                    val config = ConfigState(
                        profileName = profileName,
                        deadzoneThreshold = json.optDouble("deadzone_threshold", 0.15).toFloat(),
                        predictionFactor = json.optDouble("prediction_factor", 0.016).toFloat(),
                        kalmanFilterQ = json.optDouble("kalman_filter_q", 0.02),
                        kalmanFilterR = json.optDouble("kalman_filter_r", 0.1),
                        watchdogIntervalMs = json.optLong("watchdog_interval_ms", 5000),
                        adaptiveWatchdogEnabled = json.optBoolean("adaptive_watchdog_enabled", true),
                        lowBatteryThreshold = json.optInt("low_battery_threshold", 20),
                        injectionPriority = json.optInt("injection_priority", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY),
                        maxFps = json.optInt("max_fps", 60),
                        enableInputSmoothing = json.optBoolean("enable_input_smoothing", true),
                        enablePrediction = json.optBoolean("enable_prediction", true),
                        logLevel = json.optString("log_level", "INFO"),
                        metricsCollectionEnabled = json.optBoolean("metrics_collection_enabled", true),
                        batterySaverMode = json.optBoolean("battery_saver_mode", false),
                        performanceMode = json.optBoolean("performance_mode", false),
                        adaptivePerformance = json.optBoolean("adaptive_performance", true),
                        autoOptimize = json.optBoolean("auto_optimize", false),
                        latencyCompensation = json.optDouble("latency_compensation", 0.0).toFloat(),
                        framePrediction = json.optBoolean("frame_prediction", false),
                        adaptiveDeadzone = json.optBoolean("adaptive_deadzone", false),
                        dynamicPriorityAdjustment = json.optBoolean("dynamic_priority_adjustment", false),
                        networkMonitoringEnabled = json.optBoolean("network_monitoring_enabled", false)
                    )

                    if (!config.validate()) {
                        logger.error("Invalid imported configuration", "config_validation_error")
                        return@launch
                    }

                    _configState.value = config
                    saveConfig()

                    logger.info("Configuration imported", "config_imported", mapOf("profile" to profileName))
                } catch (e: Exception) {
                    logger.error("Failed to import configuration", "config_error", null, e)
                }
            }

            true
        } catch (e: Exception) {
            logger.error("Invalid configuration JSON", "config_error", null, e)
            false
        }
    }
}

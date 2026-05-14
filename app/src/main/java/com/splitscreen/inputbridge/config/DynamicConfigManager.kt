// Deprecated - use AdvancedConfigManager instead

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.WorkerThread
import com.splitscreen.inputbridge.logging.StructuredLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sistema de configuração dinâmica para ajustar parâmetros em runtime
 *
 * Suporta múltiplos perfis e atualização dinâmica sem reiniciar o serviço
 */
class DynamicConfigManager(private val context: Context, private val logger: StructuredLogger) {

    companion object {
        private const val PREFS_NAME = "InputBridgeDynamicConfig"
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
        val adaptivePerformance: Boolean = true
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
                adaptivePerformance = true
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
                "adaptive_performance" to adaptivePerformance
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
                   injectionPriority in android.os.Process.THREAD_PRIORITY_LOWEST..android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
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
            } catch (e: Exception) {
                logger.error("Failed to update configuration", "config_error", mapOf("key" to updates[0].first), e)
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
                    adaptivePerformance = json.optBoolean("adaptive_performance", true)
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
    fun getAvailableProfiles(): List<String> = withContext(Dispatchers.IO) {
        val profiles = mutableListOf<String>()
        profiles.add(DEFAULT_PROFILE) // Sempre inclui o perfil padrão

        val allEntries = sharedPrefs.all
        for ((key, _) in allEntries) {
            if (key.startsWith("profile_") && key != "profile_$DEFAULT_PROFILE") {
                val profileName = key.removePrefix("profile_")
                profiles.add(profileName)
            }
        }

        profiles.sorted()
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
     * Adiciona configurações avançadas com base no modo de performance
     */
    fun applyPerformanceModeSettings() {
        configScope.launch {
            try {
                val currentConfig = _configState.value
                val updatedConfig = if (currentConfig.performanceMode) {
                    // Modo performance: prioriza baixa latência
                    currentConfig.copy(
                        injectionPriority = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY,
                        enableInputSmoothing = false, // Desativa smoothing para menor latência
                        predictionFactor = 0.02f, // Aumenta a predição
                        watchdogIntervalMs = 2000L, // Watchdog mais frequente
                        deadzoneThreshold = 0.1f, // Deadzone menor para mais sensibilidade
                        kalmanFilterQ = 0.01 // Filtro mais agressivo
                    )
                } else {
                    // Modo padrão: balanceado
                    currentConfig.copy(
                        injectionPriority = android.os.Process.THREAD_PRIORITY_DEFAULT,
                        enableInputSmoothing = true,
                        predictionFactor = 0.016f,
                        watchdogIntervalMs = 5000L,
                        deadzoneThreshold = 0.15f,
                        kalmanFilterQ = 0.02
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
                    "performance_mode" to currentConfig.performanceMode.toString()
                ))
            } catch (e: Exception) {
                logger.error("Failed to apply performance mode settings", "config_error", null, e)
            }
        }
    }

    /**
     * Libera recursos
     */
    fun cleanup() {
        configScope.coroutineContext.cancelChildren()
    }
}

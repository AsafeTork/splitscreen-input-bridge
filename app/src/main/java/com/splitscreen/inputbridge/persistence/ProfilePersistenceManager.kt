package com.splitscreen.inputbridge.persistence

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.WorkerThread
import com.splitscreen.inputbridge.logging.StructuredLogger
import com.splitscreen.inputbridge.util.CoroutineManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Sistema de persistência para múltiplos perfis de usuário
 *
 * Gerencia o armazenamento e recuperação de configurações de perfil,
 * mapeamento de dispositivos e preferências específicas de perfil
 */
class ProfilePersistenceManager(private val context: Context, private val logger: com.splitscreen.inputbridge.logging.EnhancedStructuredLogger) {

    companion object {
        private const val PREFS_NAME = "InputBridgeProfiles"
        private const val CURRENT_PROFILE_KEY = "current_profile"
        private const val PROFILES_LIST_KEY = "profiles_list"
    }

    // SharedPreferences para persistência
    private val sharedPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Coroutine scope
    private val persistenceScope = CoroutineManager.createComputeScope()

    // Estado de carregamento
    private val isLoading = AtomicBoolean(false)

    // Controle de backup automático
    private val lastBackupTime = AtomicLong(0)
    private val backupInterval = 60 * 60 * 1000L // 1 hora

    /**
     * Representa um perfil de usuário completo
     */
    data class UserProfile(
        val name: String,
        val player1Descriptor: String,
        val player2Descriptor: String,
        val configPreferences: Map<String, Any>,
        val lastUsedTimestamp: Long,
        val creationTimestamp: Long,
        val isDefault: Boolean = false
    ) {

        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("name", name)
                put("player1_descriptor", player1Descriptor)
                put("player2_descriptor", player2Descriptor)

                val prefsJson = JSONObject()
                for ((key, value) in configPreferences) {
                    when (value) {
                        is String -> prefsJson.put(key, value)
                        is Int -> prefsJson.put(key, value)
                        is Long -> prefsJson.put(key, value)
                        is Float -> prefsJson.put(key, value.toDouble())
                        is Double -> prefsJson.put(key, value)
                        is Boolean -> prefsJson.put(key, value)
                        else -> prefsJson.put(key, value.toString())
                    }
                }
                put("config_preferences", prefsJson)

                put("last_used_timestamp", lastUsedTimestamp)
                put("creation_timestamp", creationTimestamp)
                put("is_default", isDefault)
            }
        }

        companion object {
            fun createDefault(name: String): UserProfile {
                return UserProfile(
                    name = name,
                    player1Descriptor = "",
                    player2Descriptor = "",
                    configPreferences = emptyMap(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    creationTimestamp = System.currentTimeMillis(),
                    isDefault = true
                )
            }

            fun fromJson(json: JSONObject): UserProfile {
                return UserProfile(
                    name = json.getString("name"),
                    player1Descriptor = json.getString("player1_descriptor"),
                    player2Descriptor = json.getString("player2_descriptor"),
                    configPreferences = parsePreferences(json.optJSONObject("config_preferences")),
                    lastUsedTimestamp = json.getLong("last_used_timestamp"),
                    creationTimestamp = json.getLong("creation_timestamp"),
                    isDefault = json.optBoolean("is_default", false)
                )
            }

            private fun parsePreferences(prefsJson: JSONObject?): Map<String, Any> {
                if (prefsJson == null) return emptyMap()

                val result = mutableMapOf<String, Any>()
                val keys = prefsJson.keys()

                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = prefsJson.get(key)

                    when (value) {
                        is String -> result[key] = value
                        is Int -> result[key] = value
                        is Long -> result[key] = value
                        is Double -> {
                            // Verifica se é um float disfarçado
                            if (value == value.toFloat().toDouble()) {
                                result[key] = value.toFloat()
                            } else {
                                result[key] = value
                            }
                        }
                        is Boolean -> result[key] = value
                        else -> result[key] = value.toString()
                    }
                }

                return result
            }
        }
    }

    /**
     * Estado atual dos perfis
     */
    data class ProfilesState(
        val currentProfileName: String,
        val availableProfiles: List<String>,
        val profiles: Map<String, UserProfile>
    )

    // Estado atual
    private var currentState: ProfilesState? = null

    init {
        // Carrega o estado inicial
        loadProfiles()
    }

    /**
     * Carrega todos os perfis do armazenamento
     */
    fun loadProfiles() {
        if (isLoading.getAndSet(true)) return

        persistenceScope.launch {
            try {
                val state = withContext(Dispatchers.IO) { loadProfilesInternal() }
                currentState = state

                logger.info("Profiles loaded", "profiles_loaded", mapOf(
                    "count" to state.availableProfiles.size,
                    "current" to state.currentProfileName
                ))
            } catch (e: Exception) {
                logger.error("Failed to load profiles", "profiles_error", null, e)
                // Cria um estado padrão em caso de erro
                currentState = createDefaultState()
            } finally {
                isLoading.set(false)
            }
        }
    }

    /**
     * Carrega os perfis internamente (em background)
     */
    @WorkerThread
    private suspend fun loadProfilesInternal(): ProfilesState = withContext(Dispatchers.IO) {
        val prefs = sharedPrefs
        val currentProfileName = prefs.getString(CURRENT_PROFILE_KEY, "default") ?: "default"

        // Carrega a lista de perfis
        val profilesListJson = prefs.getString(PROFILES_LIST_KEY, null)
        val profileNames = if (profilesListJson != null) {
            try {
                val jsonArray = JSONArray(profilesListJson)
                (0 until jsonArray.length()).map { jsonArray.getString(it) }
            } catch (e: Exception) {
                listOf("default")
            }
        } else {
            listOf("default")
        }

        // Carrega cada perfil
        val profiles = mutableMapOf<String, UserProfile>()

        for (profileName in profileNames) {
            val profileJsonString = prefs.getString("profile_$profileName", null)
            if (profileJsonString != null) {
                try {
                    val profileJson = JSONObject(profileJsonString)
                    val profile = UserProfile.fromJson(profileJson)
                    profiles[profileName] = profile
                } catch (e: Exception) {
                    logger.error("Failed to parse profile", "profile_parse_error", mapOf("profile" to profileName), e)
                }
            }
        }

        // Garante que o perfil padrão exista
        if (!profiles.containsKey("default")) {
            val defaultProfile = UserProfile.createDefault("default")
            profiles["default"] = defaultProfile
            saveProfile(defaultProfile)
        }

        // Garante que o perfil atual exista
        if (!profiles.containsKey(currentProfileName)) {
            prefs.edit().putString(CURRENT_PROFILE_KEY, "default").apply()
        }

        ProfilesState(
            currentProfileName = prefs.getString(CURRENT_PROFILE_KEY, "default") ?: "default",
            availableProfiles = profiles.keys.sorted(),
            profiles = profiles
        )
    }

    /**
     * Cria um estado padrão
     */
    private fun createDefaultState(): ProfilesState {
        val defaultProfile = UserProfile.createDefault("default")
        return ProfilesState(
            currentProfileName = "default",
            availableProfiles = listOf("default"),
            profiles = mapOf("default" to defaultProfile)
        )
    }

    /**
     * Salva um perfil
     */
    fun saveProfile(profile: UserProfile) {
        persistenceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val profileJson = profile.toJson()
                    sharedPrefs.edit()
                        .putString("profile_${profile.name}", profileJson.toString())
                        .apply()

                    // Atualiza a lista de perfis se necessário
                    updateProfilesList()
                }

                logger.info("Profile saved", "profile_saved", mapOf("profile" to profile.name))
                loadProfiles() // Recarrega para atualizar o estado
            } catch (e: Exception) {
                logger.error("Failed to save profile", "profile_error", mapOf("profile" to profile.name), e)
            }
        }
    }

    /**
     * Atualiza a lista de perfis no SharedPreferences
     */
    @WorkerThread
    private suspend fun updateProfilesList() = withContext(Dispatchers.IO) {
        val currentState = currentState ?: return@withContext
        val profileNames = currentState.availableProfiles
        val jsonArray = JSONArray(profileNames)
        sharedPrefs.edit()
            .putString(PROFILES_LIST_KEY, jsonArray.toString())
            .apply()
    }

    /**
     * Obtém o perfil atual
     */
    fun getCurrentProfile(): UserProfile? {
        val state = currentState ?: return null
        return state.profiles[state.currentProfileName]
    }

    /**
     * Obtém um perfil pelo nome
     */
    fun getProfile(profileName: String): UserProfile? {
        return currentState?.profiles?.get(profileName)
    }

    /**
     * Obtém todos os perfis
     */
    fun getAllProfiles(): List<UserProfile>? {
        return currentState?.profiles?.values?.toList()
    }

    /**
     * Obtém o estado atual
     */
    fun getCurrentState(): ProfilesState? {
        return currentState
    }

    /**
     * Altera para um perfil diferente
     */
    fun switchProfile(profileName: String): Boolean {
        val state = currentState ?: return false

        if (!state.profiles.containsKey(profileName)) {
            logger.warn("Profile not found", "profile_error", mapOf("profile" to profileName))
            return false
        }

        persistenceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    sharedPrefs.edit()
                        .putString(CURRENT_PROFILE_KEY, profileName)
                        .apply()
                }

                // Atualiza o timestamp de último uso
                val profile = state.profiles[profileName]!!
                val updatedProfile = profile.copy(lastUsedTimestamp = System.currentTimeMillis())
                saveProfile(updatedProfile)

                logger.info("Switched to profile", "profile_switch", mapOf("profile" to profileName))
                loadProfiles()
            } catch (e: Exception) {
                logger.error("Failed to switch profile", "profile_error", mapOf("profile" to profileName), e)
            }
        }

        return true
    }

    /**
     * Cria um novo perfil
     */
    fun createProfile(profileName: String, baseProfileName: String = "default"): Boolean {
        if (profileName == "default") {
            logger.warn("Cannot create default profile", "profile_error")
            return false
        }

        val state = currentState ?: return false

        if (state.profiles.containsKey(profileName)) {
            logger.warn("Profile already exists", "profile_error", mapOf("profile" to profileName))
            return false
        }

        val baseProfile = state.profiles[baseProfileName] ?: return false

        val newProfile = baseProfile.copy(
            name = profileName,
            player1Descriptor = "",
            player2Descriptor = "",
            lastUsedTimestamp = System.currentTimeMillis(),
            creationTimestamp = System.currentTimeMillis(),
            isDefault = false
        )

        persistenceScope.launch {
            try {
                saveProfile(newProfile)
                logger.info("Profile created", "profile_created", mapOf("profile" to profileName))
            } catch (e: Exception) {
                logger.error("Failed to create profile", "profile_error", mapOf("profile" to profileName), e)
            }
        }

        return true
    }

    /**
     * Deleta um perfil
     */
    fun deleteProfile(profileName: String): Boolean {
        if (profileName == "default") {
            logger.warn("Cannot delete default profile", "profile_error")
            return false
        }

        val state = currentState ?: return false

        if (!state.profiles.containsKey(profileName)) {
            logger.warn("Profile not found", "profile_error", mapOf("profile" to profileName))
            return false
        }

        // Não permite deletar o perfil atual
        if (state.currentProfileName == profileName) {
            logger.warn("Cannot delete current profile", "profile_error", mapOf("profile" to profileName))
            return false
        }

        persistenceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    sharedPrefs.edit()
                        .remove("profile_$profileName")
                        .apply()
                }

                logger.info("Profile deleted", "profile_deleted", mapOf("profile" to profileName))
                loadProfiles()
            } catch (e: Exception) {
                logger.error("Failed to delete profile", "profile_error", mapOf("profile" to profileName), e)
            }
        }

        return true
    }

    /**
     * Atualiza as configurações de um perfil
     */
    fun updateProfileConfig(profileName: String, updates: Map<String, Any>): Boolean {
        val state = currentState ?: return false
        val profile = state.profiles[profileName] ?: return false

        val updatedPrefs = profile.configPreferences.toMutableMap()
        updatedPrefs.putAll(updates)

        val updatedProfile = profile.copy(configPreferences = updatedPrefs)

        persistenceScope.launch {
            try {
                saveProfile(updatedProfile)
                logger.info("Profile config updated", "profile_updated", mapOf("profile" to profileName))
            } catch (e: Exception) {
                logger.error("Failed to update profile config", "profile_error", mapOf("profile" to profileName), e)
            }
        }

        return true
    }

    /**
     * Atualiza os descritores de dispositivo de um perfil
     */
    fun updateProfileDeviceDescriptors(profileName: String, player1Descriptor: String? = null, player2Descriptor: String? = null): Boolean {
        val state = currentState ?: return false
        val profile = state.profiles[profileName] ?: return false

        val updatedProfile = profile.copy(
            player1Descriptor = player1Descriptor ?: profile.player1Descriptor,
            player2Descriptor = player2Descriptor ?: profile.player2Descriptor
        )

        persistenceScope.launch {
            try {
                saveProfile(updatedProfile)
                logger.info("Profile devices updated", "profile_devices_updated", mapOf(
                    "profile" to profileName,
                    "player1" to updatedProfile.player1Descriptor,
                    "player2" to updatedProfile.player2Descriptor
                ))
            } catch (e: Exception) {
                logger.error("Failed to update profile devices", "profile_error", mapOf("profile" to profileName), e)
            }
        }

        return true
    }

    /**
     * Exporta todos os perfis para JSON
     * @param includeMetadata Se deve incluir metadados como versão e timestamp
     */
    suspend fun exportProfilesToJson(includeMetadata: Boolean = true): String = withContext(Dispatchers.IO) {
        val state = currentState ?: createDefaultState()

        val exportJson = JSONObject()
        if (includeMetadata) {
            exportJson.put("current_profile", state.currentProfileName)
            exportJson.put("version", 3) // Versão atualizada com mais metadados
            exportJson.put("timestamp", System.currentTimeMillis())
            exportJson.put("profile_count", state.profiles.size)
            exportJson.put("export_format", "InputBridgeProfiles/v3")
        }

        val profilesJson = JSONArray()
        for (profile in state.profiles.values) {
            val profileJson = profile.toJson()
            // Adiciona metadados de exportação
            profileJson.put("export_timestamp", System.currentTimeMillis())
            profileJson.put("export_version", 3)
            profilesJson.put(profileJson)
        }

        exportJson.put("profiles", profilesJson)

        // Adiciona versão do aplicativo e informações do dispositivo
        exportJson.put("app_version", getAppVersion())
        exportJson.put("device_info", getDeviceInfo())

        exportJson.toString(2)
    }

    /**
     * Obtém a versão do aplicativo
     */
    private fun getAppVersion(): String {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            return packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            logger.error("Failed to get app version", "system_error", null, e)
            return "1.0.0"
        }
    }

    /**
     * Obtém informações do dispositivo
     */
    private fun getDeviceInfo(): JSONObject {
        return JSONObject().apply {
            try {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("product", Build.PRODUCT)
                put("sdk_version", Build.VERSION.SDK_INT)
                put("release", Build.VERSION.RELEASE)
                put("board", Build.BOARD)
                put("hardware", Build.HARDWARE)
            } catch (e: Exception) {
                logger.error("Failed to get device info", "system_error", null, e)
            }
        }
    }

    /**
     * Cria um backup automático dos perfis
     */
    fun createAutomaticBackup() {
        if (System.currentTimeMillis() - lastBackupTime.get() < backupInterval) {
            return
        }

        persistenceScope.launch {
            try {
                val backupJson = exportProfilesToJson(true)
                // Aqui você poderia salvar em armazenamento local ou enviar para nuvem
                // Por enquanto, apenas logamos o backup
                logger.info("Automatic backup created", "profile_backup", mapOf(
                    "size" to backupJson.length,
                    "profiles" to (currentState?.profiles?.size ?: 0)
                ))

                lastBackupTime.set(System.currentTimeMillis())
            } catch (e: Exception) {
                logger.error("Failed to create automatic backup", "profile_error", null, e)
            }
        }
    }

    /**
     * Obtém a versão do formato de exportação
     */
    fun getExportVersion(jsonString: String): Int {
        return try {
            val json = JSONObject(jsonString)
            json.optInt("version", 1)
        } catch (e: Exception) {
            1 // Versão padrão para JSONs inválidos
        }
    }

    /**
     * Importa perfis de JSON
     */
    fun importProfilesFromJson(jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)
            val profilesArray = json.getJSONArray("profiles")
            val currentProfile = json.optString("current_profile", "default")

            persistenceScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        // Limpa os perfis existentes (exceto o padrão)
                        val existingProfiles = sharedPrefs.all.keys.filter { it.startsWith("profile_") }
                        for (key in existingProfiles) {
                            if (key != "profile_default") {
                                sharedPrefs.edit().remove(key).apply()
                            }
                        }

                        // Importa novos perfis
                        for (i in 0 until profilesArray.length()) {
                            val profileJson = profilesArray.getJSONObject(i)
                            val profile = UserProfile.fromJson(profileJson)
                            sharedPrefs.edit()
                                .putString("profile_${profile.name}", profile.toJson().toString())
                                .apply()
                        }

                        // Define o perfil atual
                        sharedPrefs.edit()
                            .putString(CURRENT_PROFILE_KEY, currentProfile)
                            .apply()

                        // Atualiza a lista de perfis
                        updateProfilesList()
                    }

                    logger.info("Profiles imported", "profiles_imported", mapOf(
                        "count" to profilesArray.length(),
                        "current" to currentProfile
                    ))

                    loadProfiles()
                } catch (e: Exception) {
                    logger.error("Failed to import profiles", "profiles_error", null, e)
                }
            }

            true
        } catch (e: Exception) {
            logger.error("Invalid import format", "profiles_error", null, e)
            false
        }
    }

    /**
     * Limpa todos os perfis (exceto o padrão)
     */
    fun clearAllProfiles() {
        persistenceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val existingProfiles = sharedPrefs.all.keys.filter { it.startsWith("profile_") }
                    for (key in existingProfiles) {
                        if (key != "profile_default") {
                            sharedPrefs.edit().remove(key).apply()
                        }
                    }

                    // Reseta para o perfil padrão
                    sharedPrefs.edit()
                        .putString(CURRENT_PROFILE_KEY, "default")
                        .apply()

                    updateProfilesList()
                }

                logger.info("All profiles cleared", "profiles_cleared")
                loadProfiles()
            } catch (e: Exception) {
                logger.error("Failed to clear profiles", "profiles_error", null, e)
            }
        }
    }

    /**
     * Libera recursos
     */
    fun cleanup() {
        persistenceScope.coroutineContext.cancelChildren()
    }
}

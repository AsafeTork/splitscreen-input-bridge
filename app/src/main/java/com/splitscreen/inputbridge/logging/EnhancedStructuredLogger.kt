package com.splitscreen.inputbridge.logging

import android.os.Environment
import android.util.Log
import com.splitscreen.inputbridge.metrics.EnhancedPerformanceMetrics
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Sistema de logging estruturado avançado com timestamps de alta precisão
 * e suporte para análise de logs em tempo real
 */
class EnhancedStructuredLogger(
    private val tag: String,
    private val metrics: EnhancedPerformanceMetrics,
    private val enableFileLogging: Boolean = true,
    private val maxLogFileSize: Long = 5 * 1024 * 1024, // 5MB
    private val maxLogFiles: Int = 5,
    private val enableLogAnalysis: Boolean = true
) {

    companion object {
        private const val TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        private const val LOG_FILE_PREFIX = "input_bridge_log_"
        private const val LOG_FILE_EXTENSION = ".jsonl"
        private const val SESSION_ID_LENGTH = 8
        private val dateFormat = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    // Executor para operações de arquivo
    private val fileExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var logFile: File? = null
    private var currentLogSize: Long = 0
    private var isFileLoggingInitialized = false
    private var sessionId: String = generateSessionId()
    private var sessionStartTime: Long = System.currentTimeMillis()
    private var logEntryCounter: Long = 0

    // Análise de logs
    private val logAnalysisData = mutableMapOf<String, LogAnalysisMetrics>()

    /**
     * Níveis de log
     */
    enum class LogLevel {
        VERBOSE, DEBUG, INFO, WARN, ERROR, CRITICAL
    }

    /**
     * Loga uma mensagem estruturada
     */
    fun log(level: LogLevel, message: String, eventType: String? = null, context: Map<String, Any>? = null, throwable: Throwable? = null) {
        val logEntry = createLogEntry(level, message, eventType, context, throwable)
        val logString = logEntry.toString()

        // Log no formato tradicional para o Logcat
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, logString)
            LogLevel.DEBUG -> Log.d(tag, logString)
            LogLevel.INFO -> Log.i(tag, logString)
            LogLevel.WARN -> Log.w(tag, logString, throwable)
            LogLevel.ERROR -> Log.e(tag, logString, throwable)
            LogLevel.CRITICAL -> {
                Log.e(tag, "CRITICAL: $logString", throwable)
                // Para logs críticos, também fazemos um log adicional para garantir visibilidade
                Log.wtf(tag, "CRITICAL: $message", throwable)
            }
        }

        // Log para arquivo se habilitado
        if (enableFileLogging) {
            writeToLogFile(logString)
        }

        // Análise de logs
        if (enableLogAnalysis) {
            updateLogAnalysisMetrics(level, eventType, context)
        }
    }

    /**
     * Gera um ID de sessão único
     */
    private fun generateSessionId(): String {
        val chars = (('A'..'Z') + ('a'..'z') + ('0'..'9')).toList()
        return (1..SESSION_ID_LENGTH)
            .map { chars.random() }
            .joinToString("")
    }

    /**
     * Cria uma entrada de log estruturada em JSON
     */
    private fun createLogEntry(level: LogLevel, message: String, eventType: String?, context: Map<String, Any>?, throwable: Throwable?): JSONObject {
        val entry = JSONObject()
        val entryId = ++logEntryCounter

        // Timestamp de alta precisão
        val timestamp = System.currentTimeMillis()
        val nanoTime = System.nanoTime()

        entry.put("timestamp", dateFormat.format(Date(timestamp)))
        entry.put("timestamp_ms", timestamp)
        entry.put("timestamp_ns", nanoTime)
        entry.put("session_id", sessionId)
        entry.put("session_age_ms", timestamp - sessionStartTime)
        entry.put("entry_id", entryId)
        entry.put("level", level.name)
        entry.put("message", message)

        if (eventType != null) {
            entry.put("event_type", eventType)
        }

        // Adiciona métricas de performance
        val metricsObj = JSONObject()
        metricsObj.put("current_fps", metrics.getCurrentFps())
        metricsObj.put("avg_latency_ms", metrics.getAverageProcessingLatencyMs())
        metricsObj.put("success_rate", metrics.getInjectionSuccessRate())
        metricsObj.put("stability_index", metrics.getStabilityIndex())
        metricsObj.put("events_per_second", metrics.getEventsPerSecond())
        metricsObj.put("overall_score", metrics.getOverallPerformanceScore())
        entry.put("performance", metricsObj)

        // Adiciona contexto adicional
        if (context != null) {
            val contextObj = JSONObject()
            for ((key, value) in context) {
                contextObj.put(key, value.toString())
            }
            entry.put("context", contextObj)
        }

        // Adiciona informações de exceção
        if (throwable != null) {
            val errorObj = JSONObject()
            errorObj.put("exception", throwable.javaClass.simpleName)
            errorObj.put("message", throwable.message ?: "")
            errorObj.put("stack_trace", getStackTrace(throwable))
            entry.put("error", errorObj)
        }

        return entry
    }

    /**
     * Obtém o stack trace como string
     */
    private fun getStackTrace(throwable: Throwable): String {
        return throwable.stackTraceToString()
    }

    /**
     * Inicializa o logging para arquivo
     */
    private fun initializeFileLogging() {
        if (isFileLoggingInitialized || !enableFileLogging) return

        try {
            val logDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "InputBridgeLogs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }

            // Encontra o próximo nome de arquivo disponível
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            logFile = File(logDir, "${LOG_FILE_PREFIX}${timestamp}_${sessionId}${LOG_FILE_EXTENSION}")
            currentLogSize = 0
            isFileLoggingInitialized = true

            // Agenda a rotação de logs periódica
            fileExecutor.scheduleAtFixedRate(
                this::checkLogRotation,
                1,
                1,
                TimeUnit.MINUTES
            )

            // Loga o início da sessão
            val sessionStartEntry = JSONObject().apply {
                put("timestamp", dateFormat.format(Date()))
                put("timestamp_ms", System.currentTimeMillis())
                put("event_type", "session_start")
                put("session_id", sessionId)
                put("message", "Logging session started")
                put("level", "INFO")
            }
            writeToLogFile(sessionStartEntry.toString())

        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize file logging", e)
        }
    }

    /**
     * Verifica se é necessário rotacionar os logs
     */
    private fun checkLogRotation() {
        if (currentLogSize >= maxLogFileSize) {
            rotateLogs()
        }
    }

    /**
     * Rotaciona os arquivos de log
     */
    private fun rotateLogs() {
        try {
            val logDir = logFile?.parentFile ?: return
            val logFiles = logDir.listFiles { file ->
                file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_EXTENSION)
            }?.sortedBy { it.lastModified() } ?: return

            // Se exceder o número máximo de arquivos, exclui os mais antigos
            if (logFiles.size >= maxLogFiles) {
                val filesToDelete = logFiles.size - maxLogFiles + 1
                for (i in 0 until filesToDelete) {
                    logFiles[i].delete()
                }
            }

            // Cria um novo arquivo de log
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            logFile = File(logDir, "${LOG_FILE_PREFIX}${timestamp}${LOG_FILE_EXTENSION}")
            currentLogSize = 0
        } catch (e: Exception) {
            Log.e(tag, "Failed to rotate logs", e)
        }
    }

    /**
     * Escreve uma entrada no arquivo de log
     */
    private fun writeToLogFile(logEntry: String) {
        if (!enableFileLogging) return

        if (!isFileLoggingInitialized) {
            initializeFileLogging()
        }

        fileExecutor.execute {
            try {
                val file = logFile ?: return@execute
                FileWriter(file, true).use { writer ->
                    writer.write(logEntry)
                    writer.write("\n")
                    currentLogSize += logEntry.length + 1 // +1 para o newline
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to write to log file", e)
            }
        }
    }

    /**
     * Obtém o caminho do arquivo de log atual
     */
    fun getCurrentLogFilePath(): String? {
        return logFile?.absolutePath
    }

    /**
     * Obtém todos os arquivos de log
     */
    fun getAllLogFiles(): List<File> {
        if (!enableFileLogging) return emptyList()

        try {
            val logDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "InputBridgeLogs")
            if (!logDir.exists()) return emptyList()

            return logDir.listFiles { file ->
                file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_EXTENSION)
            }?.sortedByDescending { it.lastModified() } ?: emptyList()
        } catch (e: Exception) {
            Log.e(tag, "Failed to get log files", e)
            return emptyList()
        }
    }

    /**
     * Limpa todos os arquivos de log
     */
    fun clearLogFiles() {
        if (!enableFileLogging) return

        fileExecutor.execute {
            try {
                val logDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "InputBridgeLogs")
                if (logDir.exists()) {
                    val logFiles = logDir.listFiles { file ->
                        file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_EXTENSION)
                    }
                    logFiles?.forEach { it.delete() }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to clear log files", e)
            }
        }
    }

    /**
     * Atualiza métricas de análise de logs
     */
    private fun updateLogAnalysisMetrics(level: LogLevel, eventType: String?, context: Map<String, Any>?) {
        val eventKey = eventType ?: "general"
        val metrics = logAnalysisData.getOrPut(eventKey) {
            LogAnalysisMetrics()
        }

        // Atualiza contadores
        metrics.totalCount++
        when (level) {
            LogLevel.VERBOSE -> metrics.verboseCount++
            LogLevel.DEBUG -> metrics.debugCount++
            LogLevel.INFO -> metrics.infoCount++
            LogLevel.WARN -> metrics.warnCount++
            LogLevel.ERROR -> metrics.errorCount++
            LogLevel.CRITICAL -> {
                metrics.errorCount++
                metrics.criticalCount++
            }
        }

        // Atualiza timestamp
        metrics.lastOccurrence = System.currentTimeMillis()
        if (metrics.firstOccurrence == 0L) {
            metrics.firstOccurrence = metrics.lastOccurrence
        }
    }

    /**
     * Obtém métricas de análise de logs
     */
    fun getLogAnalysisMetrics(): Map<String, LogAnalysisMetrics> {
        return logAnalysisData.toMap()
    }

    /**
     * Gera um relatório de análise de logs
     */
    fun generateLogAnalysisReport(): String {
        return buildString {
            appendLine("=== Log Analysis Report ===")
            appendLine("Session ID: $sessionId")
            appendLine("Session Duration: ${System.currentTimeMillis() - sessionStartTime}ms")
            appendLine("Total Log Entries: $logEntryCounter")
            appendLine()

            if (logAnalysisData.isEmpty()) {
                appendLine("No log analysis data available")
            } else {
                appendLine("=== Event Type Analysis ===")
                for ((eventType, metrics) in logAnalysisData.entries.sortedByDescending { it.value.totalCount }) {
                    appendLine("Event: $eventType")
                    appendLine("  Total: ${metrics.totalCount}")
                    appendLine("  Verbose: ${metrics.verboseCount}")
                    appendLine("  Debug: ${metrics.debugCount}")
                    appendLine("  Info: ${metrics.infoCount}")
                    appendLine("  Warn: ${metrics.warnCount}")
                    appendLine("  Error: ${metrics.errorCount}")
                    appendLine("  Critical: ${metrics.criticalCount}")
                    appendLine("  Duration: ${metrics.lastOccurrence - metrics.firstOccurrence}ms")
                    appendLine()
                }
            }

            appendLine("==================================")
        }
    }

    /**
     * Métricas de análise de logs
     */
    data class LogAnalysisMetrics(
        var totalCount: Int = 0,
        var verboseCount: Int = 0,
        var debugCount: Int = 0,
        var infoCount: Int = 0,
        var warnCount: Int = 0,
        var errorCount: Int = 0,
        var criticalCount: Int = 0,
        var firstOccurrence: Long = 0,
        var lastOccurrence: Long = 0
    ) {
        fun getErrorRate(): Double {
            if (totalCount == 0) return 0.0
            return (errorCount + criticalCount).toDouble() / totalCount.toDouble() * 100.0
        }

        fun getDurationMs(): Long {
            if (firstOccurrence == 0L) return 0
            return lastOccurrence - firstOccurrence
        }
    }

    /**
     * Libera recursos
     */
    fun shutdown() {
        // Loga o fim da sessão
        if (enableFileLogging && isFileLoggingInitialized) {
            val sessionEndEntry = JSONObject().apply {
                put("timestamp", dateFormat.format(Date()))
                put("timestamp_ms", System.currentTimeMillis())
                put("event_type", "session_end")
                put("session_id", sessionId)
                put("session_duration_ms", System.currentTimeMillis() - sessionStartTime)
                put("total_log_entries", logEntryCounter)
                put("message", "Logging session ended")
                put("level", "INFO")
            }
            writeToLogFile(sessionEndEntry.toString())
        }

        fileExecutor.shutdown()
        try {
            if (!fileExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                fileExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            fileExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Métodos convenientes para cada nível de log
     */
    fun verbose(message: String, eventType: String? = null, context: Map<String, Any>? = null) {
        log(LogLevel.VERBOSE, message, eventType, context)
    }

    fun debug(message: String, eventType: String? = null, context: Map<String, Any>? = null) {
        log(LogLevel.DEBUG, message, eventType, context)
    }

    fun info(message: String, eventType: String? = null, context: Map<String, Any>? = null) {
        log(LogLevel.INFO, message, eventType, context)
    }

    fun warn(message: String, eventType: String? = null, context: Map<String, Any>? = null, throwable: Throwable? = null) {
        log(LogLevel.WARN, message, eventType, context, throwable)
    }

    fun error(message: String, eventType: String? = null, context: Map<String, Any>? = null, throwable: Throwable? = null) {
        log(LogLevel.ERROR, message, eventType, context, throwable)
    }

    fun critical(message: String, eventType: String? = null, context: Map<String, Any>? = null, throwable: Throwable? = null) {
        log(LogLevel.CRITICAL, message, eventType, context, throwable)
    }

    /**
     * Loga um evento de injeção
     */
    fun logInjectionEvent(success: Boolean, deviceDescriptor: String, latencyMs: Double, context: Map<String, Any>? = null) {
        val eventType = if (success) "injection_success" else "injection_failure"
        val logContext = mutableMapOf(
            "device" to deviceDescriptor,
            "latency_ms" to "%.2f".format(latencyMs),
            "success" to success.toString()
        )
        if (context != null) {
            logContext.putAll(context)
        }

        if (success) {
            debug("Injection successful", eventType, logContext)
        } else {
            warn("Injection failed", eventType, logContext)
        }
    }

    /**
     * Loga um evento de transformação de entrada
     */
    fun logTransformationEvent(axisX: Float, axisY: Float, touchX: Float, touchY: Float, context: Map<String, Any>? = null) {
        val logContext = mutableMapOf(
            "axis_x" to "%.3f".format(axisX),
            "axis_y" to "%.3f".format(axisY),
            "touch_x" to "%.1f".format(touchX),
            "touch_y" to "%.1f".format(touchY)
        )
        if (context != null) {
            logContext.putAll(context)
        }

        debug("Input transformation", "transformation", logContext)
    }

    /**
     * Loga um evento de performance
     */
    fun logPerformanceEvent(eventType: String, metricsData: Map<String, Any>) {
        val logContext = mutableMapOf(
            "fps" to metrics.getCurrentFps(),
            "latency_ms" to "%.2f".format(metrics.getAverageProcessingLatencyMs()),
            "success_rate" to "%.1f".format(metrics.getInjectionSuccessRate()),
            "latency_95th_percentile_ms" to "%.2f".format(metrics.getLatency95thPercentileMs()),
            "latency_99th_percentile_ms" to "%.2f".format(metrics.getLatency99thPercentileMs()),
            "overall_score" to "%.1f".format(metrics.getOverallPerformanceScore())
        )
        logContext.putAll(metricsData)

        info("Performance update", eventType, logContext)
    }

    /**
     * Loga um evento crítico com nível de severidade alto
     */
    fun logCriticalEvent(eventType: String, message: String, context: Map<String, Any>, throwable: Throwable? = null) {
        val enhancedContext = mutableMapOf(
            "fps" to metrics.getCurrentFps(),
            "latency_ms" to "%.2f".format(metrics.getAverageProcessingLatencyMs()),
            "success_rate" to "%.1f".format(metrics.getInjectionSuccessRate()),
            "events_processed" to metrics.getEventsProcessed(),
            "injection_success_rate" to "%.1f".format(metrics.getInjectionSuccessRate()),
            "overall_score" to "%.1f".format(metrics.getOverallPerformanceScore())
        )
        enhancedContext.putAll(context)

        critical(message, eventType, enhancedContext, throwable)
    }

    /**
     * Loga um evento de configuração
     */
    fun logConfigEvent(eventType: String, configData: Map<String, Any>, success: Boolean = true) {
        val logContext = mutableMapOf(
            "success" to success.toString(),
            "fps" to metrics.getCurrentFps(),
            "latency_ms" to "%.2f".format(metrics.getAverageProcessingLatencyMs()),
            "overall_score" to "%.1f".format(metrics.getOverallPerformanceScore())
        )
        logContext.putAll(configData)

        if (success) {
            info("Configuration event", eventType, logContext)
        } else {
            warn("Configuration event failed", eventType, logContext)
        }
    }

    /**
     * Loga um evento de ciclo de vida do serviço
     */
    fun logLifecycleEvent(eventType: String, lifecycleData: Map<String, Any>) {
        val logContext = mutableMapOf(
            "fps" to metrics.getCurrentFps(),
            "latency_ms" to "%.2f".format(metrics.getAverageProcessingLatencyMs()),
            "success_rate" to "%.1f".format(metrics.getInjectionSuccessRate()),
            "overall_score" to "%.1f".format(metrics.getOverallPerformanceScore())
        )
        logContext.putAll(lifecycleData)

        info("Service lifecycle event", eventType, logContext)
    }

    /**
     * Loga um evento de detecção de anomalia
     */
    fun logAnomalyDetection(anomalies: List<EnhancedPerformanceMetrics.PerformanceAnomaly>) {
        if (anomalies.isEmpty()) return

        val anomalyNames = anomalies.joinToString(", ") { it.name }
        val logContext = mutableMapOf(
            "anomaly_count" to anomalies.size,
            "anomalies" to anomalyNames,
            "fps" to metrics.getCurrentFps(),
            "latency_ms" to "%.2f".format(metrics.getAverageProcessingLatencyMs()),
            "success_rate" to "%.1f".format(metrics.getInjectionSuccessRate()),
            "stability_index" to "%.1f".format(metrics.getStabilityIndex())
        )

        warn("Performance anomalies detected", "anomaly_detection", logContext)
    }
}

package com.splitscreen.inputbridge.metrics

import android.content.Context
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Sistema de métricas de performance avançado com análise estatística aprofundada
 * e integração com métricas de sistema em tempo real
 */
class EnhancedPerformanceMetrics(private val context: Context? = null) {

    companion object {
        private const val TAG = "EnhancedPerformanceMetrics"
        private const val METRICS_WINDOW_SIZE = 200 // Janela móvel maior para melhor precisão
        private const val FPS_WINDOW_SIZE = 120 // Janela de 120 frames para cálculo de FPS
        private const val HISTORY_SIZE = 60 // 60 segundos de histórico
    }

    // Métricas de latência (em nanosegundos)
    private val latencySamples = EnhancedCircularBuffer<Long>(METRICS_WINDOW_SIZE)
    private val injectionLatency = AtomicLong(0)
    private val processingLatency = AtomicLong(0)
    private val endToEndLatency = AtomicLong(0)

    // Métricas de FPS e frames
    private val frameTimes = EnhancedCircularBuffer<Long>(FPS_WINDOW_SIZE)
    private val lastFpsUpdateTime = AtomicLong(0)
    private val currentFps = AtomicInteger(0)
    private val fpsHistory = EnhancedCircularBuffer<Int>(HISTORY_SIZE)

    // Métricas de sucesso de injeção
    private val successfulInjections = AtomicInteger(0)
    private val failedInjections = AtomicInteger(0)
    private val totalInjections = AtomicInteger(0)
    private val injectionSuccessHistory = EnhancedCircularBuffer<Double>(HISTORY_SIZE)

    // Contadores de eventos
    private val eventsProcessed = AtomicInteger(0)
    private val eventsDropped = AtomicInteger(0)
    private val eventsByType = mutableMapOf<String, AtomicInteger>()

    // Métricas de jitter e estabilidade
    private val jitterSamples = EnhancedCircularBuffer<Long>(METRICS_WINDOW_SIZE)
    private val latencyHistory = EnhancedCircularBuffer<Double>(HISTORY_SIZE)

    // Métricas de sistema
    private val systemMetricsCollector = AtomicReference<SystemMetricsCollector?>(null)
    private val systemMetricsHistory = EnhancedCircularBuffer<SystemMetricsCollector.SystemMetrics>(HISTORY_SIZE)

    // Métricas de qualidade de serviço
    private val qosMetrics = AtomicReference(QosMetrics())

    // Estado da sessão
    private val sessionStartTime = AtomicLong(System.currentTimeMillis())
    private val sessionEventCounter = AtomicInteger(0)

    /**
     * Registra o início do processamento de um evento
     */
    fun onEventProcessingStarted(eventType: String = "default") {
        eventsProcessed.incrementAndGet()
        sessionEventCounter.incrementAndGet()
        eventsByType.getOrPut(eventType) { AtomicInteger(0) }.incrementAndGet()
    }

    /**
     * Registra um evento descartado
     */
    fun onEventDropped(eventType: String = "default") {
        eventsDropped.incrementAndGet()
        eventsByType.getOrPut("dropped_$eventType") { AtomicInteger(0) }.incrementAndGet()
    }

    /**
     * Registra a latência de processamento
     */
    fun recordProcessingLatency(startTime: Long, endTime: Long) {
        val latency = endTime - startTime
        processingLatency.set(latency)
        latencySamples.add(latency)
        endToEndLatency.addAndGet(latency)
    }

    /**
     * Registra a latência de injeção
     */
    fun recordInjectionLatency(startTime: Long, endTime: Long) {
        val latency = endTime - startTime
        injectionLatency.set(latency)
        latencySamples.add(latency)
        endToEndLatency.addAndGet(latency)
    }

    /**
     * Registra o tempo de um frame para cálculo de FPS
     */
    fun recordFrameTime(frameTime: Long) {
        frameTimes.add(frameTime)

        // Atualiza FPS a cada segundo
        val currentTime = System.nanoTime()
        if (currentTime - lastFpsUpdateTime.get() >= 1_000_000_000) {
            if (frameTimes.size() >= 2) {
                val fps = calculateFps()
                currentFps.set(fps)
                fpsHistory.add(fps)
                lastFpsUpdateTime.set(currentTime)
            }
        }
    }

    /**
     * Registra uma injeção bem-sucedida
     */
    fun recordSuccessfulInjection() {
        successfulInjections.incrementAndGet()
        totalInjections.incrementAndGet()
        updateQosMetrics()
    }

    /**
     * Registra uma injeção falha
     */
    fun recordFailedInjection() {
        failedInjections.incrementAndGet()
        totalInjections.incrementAndGet()
        updateQosMetrics()
    }

    /**
     * Registra jitter (variação de latência)
     */
    fun recordJitter(jitter: Long) {
        jitterSamples.add(jitter)
    }

    /**
     * Atualiza métricas de qualidade de serviço
     */
    private fun updateQosMetrics() {
        val currentQos = qosMetrics.get()
        val successRate = getInjectionSuccessRate()
        val avgLatency = getAverageProcessingLatencyMs()
        val stability = getStabilityIndex()

        val newQos = currentQos.copy(
            successRate = successRate,
            averageLatencyMs = avgLatency,
            stabilityIndex = stability,
            lastUpdateTime = System.currentTimeMillis()
        )
        qosMetrics.set(newQos)
    }

    /**
     * Calcula o FPS atual com base nos tempos de frame
     */
    private fun calculateFps(): Int {
        if (frameTimes.size() < 2) return 0

        val oldestFrameTime = frameTimes.get(0)
        val newestFrameTime = frameTimes.get(frameTimes.size() - 1)
        val timeSpan = newestFrameTime - oldestFrameTime

        if (timeSpan <= 0) return 0

        val fps = (frameTimes.size().toDouble() / (timeSpan / 1_000_000_000.0)).roundToInt()
        return fps.coerceAtLeast(0)
    }

    /**
     * Obtém a latência média de processamento (em milissegundos)
     */
    fun getAverageProcessingLatencyMs(): Double {
        if (latencySamples.isEmpty()) return 0.0
        return latencySamples.average() / 1_000_000.0
    }

    /**
     * Obtém a latência média de injeção (em milissegundos)
     */
    fun getAverageInjectionLatencyMs(): Double {
        return injectionLatency.get().toDouble() / 1_000_000.0
    }

    /**
     * Obtém a latência end-to-end média (em milissegundos)
     */
    fun getAverageEndToEndLatencyMs(): Double {
        val totalLatency = endToEndLatency.get()
        val totalEvents = eventsProcessed.get()
        if (totalEvents == 0) return 0.0
        return totalLatency.toDouble() / totalEvents.toDouble() / 1_000_000.0
    }

    /**
     * Obtém o FPS atual
     */
    fun getCurrentFps(): Int {
        return currentFps.get()
    }

    /**
     * Obtém a taxa de sucesso de injeção (0-100)
     */
    fun getInjectionSuccessRate(): Double {
        val total = totalInjections.get()
        if (total == 0) return 100.0
        return (successfulInjections.get().toDouble() / total.toDouble()) * 100.0
    }

    /**
     * Obtém o número de eventos processados
     */
    fun getEventsProcessed(): Int {
        return eventsProcessed.get()
    }

    /**
     * Obtém o número de eventos descartados
     */
    fun getEventsDropped(): Int {
        return eventsDropped.get()
    }

    /**
     * Obtém a latência média de jitter (em milissegundos)
     */
    fun getAverageJitterMs(): Double {
        if (jitterSamples.isEmpty()) return 0.0
        return jitterSamples.average() / 1_000_000.0
    }

    /**
     * Obtém o desvio padrão da latência (em milissegundos)
     */
    fun getLatencyStdDevMs(): Double {
        if (latencySamples.isEmpty()) return 0.0
        val mean = latencySamples.average()
        val variance = latencySamples.map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(variance) / 1_000_000.0
    }

    /**
     * Reseta todas as métricas
     */
    fun reset() {
        latencySamples.clear()
        frameTimes.clear()
        successfulInjections.set(0)
        failedInjections.set(0)
        totalInjections.set(0)
        eventsProcessed.set(0)
        eventsDropped.set(0)
        jitterSamples.clear()
        currentFps.set(0)
        lastFpsUpdateTime.set(0)
        endToEndLatency.set(0)
        sessionStartTime.set(System.currentTimeMillis())
        sessionEventCounter.set(0)
        eventsByType.clear()
        systemMetricsHistory.clear()
        qosMetrics.set(QosMetrics())
    }

    /**
     * Inicializa o coletor de métricas de sistema
     */
    fun initializeSystemMetrics(context: Context) {
        if (this.context == null) {
            systemMetricsCollector.set(SystemMetricsCollector(context))
        }
    }

    /**
     * Coleta métricas de sistema
     */
    fun collectSystemMetrics() {
        val collector = systemMetricsCollector.get()
        if (collector != null) {
            val metrics = collector.collectMetrics()
            systemMetricsHistory.add(metrics)
        }
    }

    /**
     * Obtém as métricas de sistema atuais
     */
    fun getCurrentSystemMetrics(): SystemMetricsCollector.SystemMetrics? {
        return if (systemMetricsHistory.size() > 0) {
            systemMetricsHistory.get(systemMetricsHistory.size() - 1)
        } else {
            null
        }
    }

    /**
     * Obtém o uso médio de CPU do sistema
     */
    fun getAverageSystemCpuUsage(): Float {
        if (systemMetricsHistory.isEmpty()) return 0f
        var sum = 0f
        for (i in 0 until systemMetricsHistory.size()) {
            sum += systemMetricsHistory.get(i).cpuUsage
        }
        return sum / systemMetricsHistory.size()
    }

    /**
     * Obtém o uso médio de memória em MB
     */
    fun getAverageMemoryUsageMb(): Float {
        if (systemMetricsHistory.isEmpty()) return 0f
        var sum = 0f
        for (i in 0 until systemMetricsHistory.size()) {
            sum += systemMetricsHistory.get(i).memoryUsageMb
        }
        return sum / systemMetricsHistory.size()
    }

    /**
     * Obtém o nível médio da bateria
     */
    fun getAverageBatteryLevel(): Float {
        if (systemMetricsHistory.isEmpty()) return 0f
        var sum = 0f
        for (i in 0 until systemMetricsHistory.size()) {
            sum += systemMetricsHistory.get(i).batteryLevel
        }
        return sum / systemMetricsHistory.size()
    }

    /**
     * Obtém a temperatura média da bateria
     */
    fun getAverageBatteryTemperature(): Float {
        if (systemMetricsHistory.isEmpty()) return 0f
        var sum = 0f
        for (i in 0 until systemMetricsHistory.size()) {
            sum += systemMetricsHistory.get(i).batteryTemperature
        }
        return sum / systemMetricsHistory.size()
    }

    /**
     * Obtém o FPS médio no histórico
     */
    fun getAverageFps(): Int {
        if (fpsHistory.isEmpty()) return 0
        return (fpsHistory.average()).roundToInt()
    }

    /**
     * Obtém a taxa de sucesso média de injeção no histórico
     */
    fun getAverageSuccessRate(): Double {
        if (injectionSuccessHistory.isEmpty()) return 100.0
        return injectionSuccessHistory.average()
    }

    /**
     * Obtém a duração da sessão em milissegundos
     */
    fun getSessionDurationMs(): Long {
        return System.currentTimeMillis() - sessionStartTime.get()
    }

    /**
     * Obtém a taxa de eventos por segundo
     */
    fun getEventsPerSecond(): Double {
        val durationSeconds = getSessionDurationMs() / 1000.0
        if (durationSeconds <= 0) return 0.0
        return sessionEventCounter.get() / durationSeconds
    }

    /**
     * Obtém estatísticas por tipo de evento
     */
    fun getEventStatistics(): Map<String, Int> {
        return eventsByType.mapValues { it.value.get() }
    }

    /**
     * Obtém as métricas de qualidade de serviço
     */
    fun getQosMetrics(): QosMetrics {
        return qosMetrics.get()
    }

    /**
     * Buffer circular aprimorado para armazenamento eficiente de métricas
     */
    private class EnhancedCircularBuffer<T>(private val capacity: Int) {
        private val buffer = ArrayList<T>(capacity)
        private var head = 0
        private var tail = 0
        private var size = 0

        fun add(item: T) {
            if (capacity == 0) return

            if (size < capacity) {
                buffer.add(item)
                size++
            } else {
                buffer[tail] = item
                tail = (tail + 1) % capacity
            }
            head = (head + 1) % capacity
        }

        fun get(index: Int): T {
            if (index < 0 || index >= size) {
                throw IndexOutOfBoundsException("Index: $index, Size: $size")
            }
            val actualIndex = (head - size + index + capacity) % capacity
            return buffer[actualIndex]
        }

        fun average(): Double {
            if (size == 0) return 0.0
            var sum = 0.0
            for (i in 0 until size) {
                val value = get(i)
                if (value is Number) {
                    sum += value.toDouble()
                }
            }
            return sum / size
        }

        fun isEmpty(): Boolean = size == 0

        fun size(): Int = size

        fun clear() {
            buffer.clear()
            head = 0
            tail = 0
            size = 0
        }

        fun toList(): List<T> {
            val list = mutableListOf<T>()
            for (i in 0 until size) {
                list.add(get(i))
            }
            return list
        }
    }

    /**
     * Métricas de Qualidade de Serviço (QoS)
     */
    data class QosMetrics(
        val successRate: Double = 100.0,
        val averageLatencyMs: Double = 0.0,
        val stabilityIndex: Double = 100.0,
        val lastUpdateTime: Long = System.currentTimeMillis(),
        val qualityScore: Double = 100.0
    ) {
        fun calculateQualityScore(): Double {
            // Fórmula ponderada para calcular a qualidade geral
            val latencyFactor = if (averageLatencyMs < 5) 100.0 else (100.0 - (averageLatencyMs - 5) * 5).coerceAtLeast(0.0)
            val successFactor = successRate * 0.8
            val stabilityFactor = stabilityIndex * 0.2
            return (latencyFactor * 0.4 + successFactor * 0.4 + stabilityFactor * 0.2)
        }
    }

    /**
     * Obtém o percentil 95 da latência (em milissegundos)
     */
    fun getLatency95thPercentileMs(): Double {
        if (latencySamples.isEmpty()) return 0.0

        val sortedLatencies = latencySamples.toList().sorted()
        val index = (sortedLatencies.size * 0.95).toInt().coerceAtMost(sortedLatencies.size - 1)
        return sortedLatencies[index] / 1_000_000.0
    }

    /**
     * Obtém o percentil 99 da latência (em milissegundos)
     */
    fun getLatency99thPercentileMs(): Double {
        if (latencySamples.isEmpty()) return 0.0

        val sortedLatencies = latencySamples.toList().sorted()
        val index = (sortedLatencies.size * 0.99).toInt().coerceAtMost(sortedLatencies.size - 1)
        return sortedLatencies[index] / 1_000_000.0
    }

    /**
     * Obtém o percentil 99.9 da latência (em milissegundos)
     */
    fun getLatency999thPercentileMs(): Double {
        if (latencySamples.isEmpty()) return 0.0

        val sortedLatencies = latencySamples.toList().sorted()
        val index = (sortedLatencies.size * 0.999).toInt().coerceAtMost(sortedLatencies.size - 1)
        return sortedLatencies[index] / 1_000_000.0
    }

    /**
     * Calcula o índice de estabilidade (0-100)
     */
    fun getStabilityIndex(): Double {
        if (latencySamples.isEmpty()) return 100.0

        val stdDev = getLatencyStdDevMs()
        val avgLatency = getAverageProcessingLatencyMs()

        if (avgLatency <= 0) return 100.0

        // Índice de estabilidade baseado no coeficiente de variação
        val coefficientOfVariation = stdDev / avgLatency
        // Mapeia para escala 0-100 (quanto menor a variação, maior a estabilidade)
        return (1.0 / (1.0 + coefficientOfVariation) * 100.0).coerceIn(0.0, 100.0)
    }

    /**
     * Calcula a taxa de eventos descartados
     */
    fun getDropRate(): Double {
        val total = eventsProcessed.get()
        if (total == 0) return 0.0
        return (eventsDropped.get().toDouble() / total.toDouble()) * 100.0
    }

    /**
     * Gera um relatório de métricas completo
     */
    fun generateMetricsReport(): String {
        val systemMetrics = getCurrentSystemMetrics()
        val qos = getQosMetrics()
        return buildString {
            appendLine("=== Enhanced Performance Metrics Report ===")
            appendLine("Session Duration: ${getSessionDurationMs()}ms (${getSessionDurationMs() / 1000} seconds)")
            appendLine("Total Events: ${getEventsProcessed()}")
            appendLine("Events Dropped: ${getEventsDropped()} (${"%.1f".format(getDropRate())}%)")
            appendLine("Events Per Second: ${"%.2f".format(getEventsPerSecond())}")
            appendLine("Current FPS: ${getCurrentFps()}")
            appendLine("Average FPS: ${getAverageFps()}")
            appendLine("Avg Processing Latency: ${getAverageProcessingLatencyMs().roundToInt()}ms")
            appendLine("Avg Injection Latency: ${getAverageInjectionLatencyMs().roundToInt()}ms")
            appendLine("Avg End-to-End Latency: ${getAverageEndToEndLatencyMs().roundToInt()}ms")
            appendLine("Latency StdDev: ${getLatencyStdDevMs().roundToInt()}ms")
            appendLine("Latency 95th Percentile: ${getLatency95thPercentileMs().roundToInt()}ms")
            appendLine("Latency 99th Percentile: ${getLatency99thPercentileMs().roundToInt()}ms")
            appendLine("Latency 99.9th Percentile: ${getLatency999thPercentileMs().roundToInt()}ms")
            appendLine("Avg Jitter: ${getAverageJitterMs().roundToInt()}ms")
            appendLine("Stability Index: ${"%.1f".format(getStabilityIndex())}")
            appendLine("Injection Success Rate: ${"%.1f".format(getInjectionSuccessRate())}%")
            appendLine("Average Success Rate: ${"%.1f".format(getAverageSuccessRate())}%")
            appendLine("Total Injections: ${totalInjections.get()}")
            appendLine("Successful Injections: ${successfulInjections.get()}")
            appendLine("Failed Injections: ${failedInjections.get()}")
            appendLine("QoS Quality Score: ${"%.1f".format(qos.qualityScore)}")

            if (systemMetrics != null) {
                appendLine("\n=== System Metrics ===")
                appendLine("CPU Usage: ${systemMetrics.cpuUsage.roundToInt()}%")
                appendLine("Process CPU Usage: ${systemMetrics.processCpuUsage.roundToInt()}%")
                appendLine("Memory Usage: ${systemMetrics.memoryUsageMb.roundToInt()} MB")
                appendLine("Battery Level: ${systemMetrics.batteryLevel}%")
                appendLine("Battery Temp: ${systemMetrics.batteryTemperature}°C")
                appendLine("Storage Available: ${systemMetrics.availableStorageMb} MB")
                appendLine("Total Storage: ${systemMetrics.totalStorageMb} MB")
                appendLine("Charging: ${systemMetrics.isCharging}")
            }

            // Estatísticas por tipo de evento
            if (eventsByType.isNotEmpty()) {
                appendLine("\n=== Event Statistics ===")
                for ((type, count) in getEventStatistics().entries.sortedByDescending { it.value }) {
                    appendLine("$type: $count")
                }
            }

            appendLine("==============================================")
        }
    }

    /**
     * Gera um relatório de métricas em formato JSON para integração com sistemas externos
     */
    fun generateMetricsJsonReport(): String {
        val systemMetrics = getCurrentSystemMetrics()
        val qos = getQosMetrics()
        return buildString {
            appendLine("{")
            appendLine("  \"timestamp\": ${System.currentTimeMillis()},")
            appendLine("  \"session_duration_ms\": ${getSessionDurationMs()},")
            appendLine("  \"events_processed\": ${getEventsProcessed()},")
            appendLine("  \"events_dropped\": ${getEventsDropped()},")
            appendLine("  \"drop_rate\": ${"%.2f".format(getDropRate())},")
            appendLine("  \"events_per_second\": ${"%.2f".format(getEventsPerSecond())},")
            appendLine("  \"current_fps\": ${getCurrentFps()},")
            appendLine("  \"average_fps\": ${getAverageFps()},")
            appendLine("  \"avg_processing_latency_ms\": ${"%.2f".format(getAverageProcessingLatencyMs())},")
            appendLine("  \"avg_injection_latency_ms\": ${"%.2f".format(getAverageInjectionLatencyMs())},")
            appendLine("  \"avg_end_to_end_latency_ms\": ${"%.2f".format(getAverageEndToEndLatencyMs())},")
            appendLine("  \"latency_stddev_ms\": ${"%.2f".format(getLatencyStdDevMs())},")
            appendLine("  \"latency_95th_percentile_ms\": ${"%.2f".format(getLatency95thPercentileMs())},")
            appendLine("  \"latency_99th_percentile_ms\": ${"%.2f".format(getLatency99thPercentileMs())},")
            appendLine("  \"latency_999th_percentile_ms\": ${"%.2f".format(getLatency999thPercentileMs())},")
            appendLine("  \"avg_jitter_ms\": ${"%.2f".format(getAverageJitterMs())},")
            appendLine("  \"stability_index\": ${"%.1f".format(getStabilityIndex())},")
            appendLine("  \"injection_success_rate\": ${"%.1f".format(getInjectionSuccessRate())},")
            appendLine("  \"average_success_rate\": ${"%.1f".format(getAverageSuccessRate())},")
            appendLine("  \"total_injections\": ${totalInjections.get()},")
            appendLine("  \"successful_injections\": ${successfulInjections.get()},")
            appendLine("  \"failed_injections\": ${failedInjections.get()},")
            appendLine("  \"qos_quality_score\": ${"%.1f".format(qos.qualityScore)},")
            appendLine("  \"qos_success_rate\": ${"%.1f".format(qos.successRate)},")
            appendLine("  \"qos_average_latency_ms\": ${"%.2f".format(qos.averageLatencyMs)},")
            appendLine("  \"qos_stability_index\": ${"%.1f".format(qos.stabilityIndex)}")

            if (systemMetrics != null) {
                appendLine(",")
                appendLine("  \"system_metrics\": {")
                appendLine("    \"cpu_usage\": ${"%.1f".format(systemMetrics.cpuUsage)},")
                appendLine("    \"process_cpu_usage\": ${"%.1f".format(systemMetrics.processCpuUsage)},")
                appendLine("    \"memory_usage_mb\": ${"%.1f".format(systemMetrics.memoryUsageMb)},")
                appendLine("    \"battery_level\": ${systemMetrics.batteryLevel},")
                appendLine("    \"battery_temperature\": ${"%.1f".format(systemMetrics.batteryTemperature)},")
                appendLine("    \"available_storage_mb\": ${systemMetrics.availableStorageMb},")
                appendLine("    \"total_storage_mb\": ${systemMetrics.totalStorageMb},")
                appendLine("    \"is_charging\": ${systemMetrics.isCharging},")
                appendLine("    \"timestamp\": ${systemMetrics.timestamp}")
                appendLine("  }")
            }

            // Estatísticas por tipo de evento
            if (eventsByType.isNotEmpty()) {
                appendLine(",")
                appendLine("  \"event_statistics\": {")
                val eventStats = getEventStatistics().entries.sortedByDescending { it.value }
                for ((i, (type, count)) in eventStats.withIndex()) {
                    val comma = if (i < eventStats.size - 1) "," else """)
                    appendLine("    \"$type\": $count$comma")
                }
                appendLine("  }")
            }

            appendLine("}")
        }
    }

    /**
     * Loga as métricas atuais
     */
    fun logMetrics() {
        Log.i(TAG, generateMetricsReport())
    }

    /**
     * Registra jitter (variação de latência entre eventos consecutivos)
     */
    fun recordJitterFromLatencies(previousLatency: Long, currentLatency: Long) {
        val jitter = Math.abs(currentLatency - previousLatency)
        jitterSamples.add(jitter)
    }

    /**
     * Obtém o número de eventos processados por segundo (média móvel)
     */
    fun getMovingAverageEventsPerSecond(windowSize: Int = 10): Double {
        val durationSeconds = getSessionDurationMs() / 1000.0
        if (durationSeconds <= 0) return 0.0

        val eventsInWindow = if (sessionEventCounter.get() > windowSize) windowSize else sessionEventCounter.get()
        return eventsInWindow / durationSeconds
    }

    /**
     * Detecta anomalias de performance
     */
    fun detectPerformanceAnomalies(): List<PerformanceAnomaly> {
        val anomalies = mutableListOf<PerformanceAnomaly>()

        // Anomalia de latência alta
        if (getAverageProcessingLatencyMs() > 20) {
            anomalies.add(PerformanceAnomaly.HIGH_LATENCY)
        }

        // Anomalia de baixa taxa de sucesso
        if (getInjectionSuccessRate() < 95) {
            anomalies.add(PerformanceAnomaly.LOW_SUCCESS_RATE)
        }

        // Anomalia de FPS baixo
        if (getCurrentFps() < 30) {
            anomalies.add(PerformanceAnomaly.LOW_FPS)
        }

        // Anomalia de alta taxa de descarte
        if (getDropRate() > 5) {
            anomalies.add(PerformanceAnomaly.HIGH_DROP_RATE)
        }

        // Anomalia de instabilidade
        if (getStabilityIndex() < 70) {
            anomalies.add(PerformanceAnomaly.HIGH_JITTER)
        }

        return anomalies
    }

    /**
     * Tipos de anomalias de performance
     */
    enum class PerformanceAnomaly {
        HIGH_LATENCY, LOW_SUCCESS_RATE, LOW_FPS, HIGH_DROP_RATE, HIGH_JITTER
    }

    /**
     * Obtém o escore de performance geral (0-100)
     */
    fun getOverallPerformanceScore(): Double {
        val qos = getQosMetrics()
        val successRateScore = getInjectionSuccessRate() * 0.4
        val latencyScore = if (getAverageProcessingLatencyMs() < 10) 100.0 else (100.0 - (getAverageProcessingLatencyMs() - 10) * 5).coerceAtLeast(0.0)
        val fpsScore = if (getCurrentFps() >= 60) 100.0 else (getCurrentFps().toDouble() / 60.0 * 100.0)
        val stabilityScore = getStabilityIndex() * 0.2

        return (successRateScore * 0.3 + latencyScore * 0.3 + fpsScore * 0.2 + stabilityScore * 0.2)
    }
}

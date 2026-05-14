package com.splitscreen.inputbridge.metrics

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Sistema de métricas de performance para o InputBridgeService
 *
 * Rastreia latência, FPS, taxa de sucesso de injeção e outras métricas críticas
 * Inclui integração com métricas de sistema (CPU, MEM, Bateria)
 */
class PerformanceMetrics(private val context: Context? = null) {

    companion object {
        private const val TAG = "PerformanceMetrics"
        private const val METRICS_WINDOW_SIZE = 100 // Janela móvel de 100 amostras
        private const val FPS_WINDOW_SIZE = 60 // Janela de 60 frames para cálculo de FPS
    }

    // Métricas de latência (em nanosegundos)
    private val latencySamples = CircularBuffer<Long>(METRICS_WINDOW_SIZE)
    private val injectionLatency = AtomicLong(0)
    private val processingLatency = AtomicLong(0)

    // Métricas de FPS
    private val frameTimes = CircularBuffer<Long>(FPS_WINDOW_SIZE)
    private val lastFpsUpdateTime = AtomicLong(0)
    private val currentFps = AtomicInteger(0)

    // Métricas de sucesso de injeção
    private val successfulInjections = AtomicInteger(0)
    private val failedInjections = AtomicInteger(0)
    private val totalInjections = AtomicInteger(0)

    // Contadores de eventos
    private val eventsProcessed = AtomicInteger(0)
    private val eventsDropped = AtomicInteger(0)

    // Métricas de jitter
    private val jitterSamples = CircularBuffer<Long>(METRICS_WINDOW_SIZE)

    // Métricas de sistema
    private val systemMetricsCollector = AtomicReference<SystemMetricsCollector?>(null)
    private val systemMetricsHistory = CircularBuffer<SystemMetricsCollector.SystemMetrics>(30) // 30 amostras

    /**
     * Registra o início do processamento de um evento
     */
    fun onEventProcessingStarted() {
        eventsProcessed.incrementAndGet()
    }

    /**
     * Registra o início da injeção de evento
     */
    fun onInjectionStarted() {
        eventsProcessed.incrementAndGet()
    }

    /**
     * Inicia o rastreamento de métricas
     */
    fun startTracking() {
        // Método vazio para compatibilidade com a interface do EnhancedPerformanceMetrics
    }

    /**
     * Registra um evento descartado
     */
    fun onEventDropped() {
        eventsDropped.incrementAndGet()
    }

    /**
     * Registra a latência de processamento
     */
    fun recordProcessingLatency(startTime: Long, endTime: Long) {
        val latency = endTime - startTime
        processingLatency.set(latency)
        latencySamples.add(latency)
    }

    /**
     * Registra a latência de injeção
     */
    fun recordInjectionLatency(latency: Long) {
        injectionLatency.set(latency)
        latencySamples.add(latency)
    }

    /**
     * Registra a latência de injeção
     */
    fun recordInjectionLatency(startTime: Long, endTime: Long) {
        val latency = endTime - startTime
        injectionLatency.set(latency)
        latencySamples.add(latency)
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
                lastFpsUpdateTime.set(currentTime)
            }
        }
    }

    /**
     * Atualiza o FPS
     */
    fun updateFPS() {
        // O FPS é atualizado automaticamente no recordFrameTime
        // Este método é um placeholder para compatibilidade
    }

    /**
     * Obtém o FPS atual
     */
    fun currentFPS(): Int {
        return currentFps.get()
    }

    /**
     * Registra uma injeção bem-sucedida
     */
    fun recordSuccessfulInjection() {
        successfulInjections.incrementAndGet()
        totalInjections.incrementAndGet()
    }

    /**
     * Registra o sucesso da injeção
     */
    fun recordInjectionSuccess(success: Boolean) {
        if (success) {
            successfulInjections.incrementAndGet()
        } else {
            failedInjections.incrementAndGet()
        }
        totalInjections.incrementAndGet()
    }

    /**
     * Registra uma injeção falha
     */
    fun recordFailedInjection() {
        failedInjections.incrementAndGet()
        totalInjections.incrementAndGet()
    }

    /**
     * Registra jitter (variação de latência)
     */
    fun recordJitter(jitter: Long) {
        jitterSamples.add(jitter)
    }

    /**
     * Registra a latência do Choreographer
     */
    fun recordChoreographerLatency(latency: Float) {
        // Converter para Long e adicionar às amostras de latência
        latencySamples.add((latency * 1_000_000).toLong())
    }

    /**
     * Registra a duração da injeção
     */
    fun recordInjectionDuration(duration: Float) {
        // Converter para Long e adicionar às amostras de latência
        latencySamples.add((duration * 1_000_000).toLong())
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
        systemMetricsHistory.clear()
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
     * Buffer circular para armazenamento eficiente de métricas
     */
    private class CircularBuffer<T>(private val capacity: Int) {
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
    }

    /**
     * Gera um relatório de métricas para logging
     */
    fun generateMetricsReport(): String {
        val systemMetrics = getCurrentSystemMetrics()
        return buildString {
            appendLine("=== Performance Metrics Report ===")
            appendLine("Events Processed: ${getEventsProcessed()}")
            appendLine("Events Dropped: ${getEventsDropped()}")
            appendLine("Drop Rate: ${if (getEventsProcessed() > 0) (getEventsDropped().toDouble() / getEventsProcessed().toDouble() * 100.0).roundToInt() else 0}%")
            appendLine("Current FPS: ${getCurrentFps()}")
            appendLine("Avg Processing Latency: ${getAverageProcessingLatencyMs().roundToInt()}ms")
            appendLine("Avg Injection Latency: ${getAverageInjectionLatencyMs().roundToInt()}ms")
            appendLine("Latency StdDev: ${getLatencyStdDevMs().roundToInt()}ms")
            appendLine("Avg Jitter: ${getAverageJitterMs().roundToInt()}ms")
            appendLine("Injection Success Rate: ${getInjectionSuccessRate().roundToInt()}%")
            appendLine("Total Injections: ${totalInjections.get()}")
            appendLine("Successful Injections: ${successfulInjections.get()}")
            appendLine("Failed Injections: ${failedInjections.get()}")

            if (systemMetrics != null) {
                appendLine("\n=== System Metrics ===")
                appendLine("CPU Usage: ${systemMetrics.cpuUsage.roundToInt()}%")
                appendLine("Memory Usage: ${systemMetrics.memoryUsageMb.roundToInt()} MB")
                appendLine("Battery Level: ${systemMetrics.batteryLevel}%")
                appendLine("Battery Temp: ${systemMetrics.batteryTemperature}°C")
                appendLine("Storage Available: ${systemMetrics.availableStorageMb} MB")
                appendLine("Process CPU: ${systemMetrics.processCpuUsage.roundToInt()}%")
                appendLine("Charging: ${systemMetrics.isCharging}")
            }
            appendLine("===================================")
        }
    }

    /**
     * Gera um relatório de métricas em formato JSON para integração com sistemas externos
     */
    fun generateMetricsJsonReport(): String {
        val systemMetrics = getCurrentSystemMetrics()
        return buildString {
            appendLine("{")
            appendLine("  \"timestamp\": ${System.currentTimeMillis()},")
            appendLine("  \"events_processed\": ${getEventsProcessed()},")
            appendLine("  \"events_dropped\": ${getEventsDropped()},")
            appendLine("  \"drop_rate\": ${"%.2f".format(getDropRate())},")
            appendLine("  \"events_per_second\": ${"%.2f".format(getEventsPerSecond())},")
            appendLine("  \"current_fps\": ${getCurrentFps()},")
            appendLine("  \"avg_processing_latency_ms\": ${"%.2f".format(getAverageProcessingLatencyMs())},")
            appendLine("  \"avg_injection_latency_ms\": ${"%.2f".format(getAverageInjectionLatencyMs())},")
            appendLine("  \"latency_stddev_ms\": ${"%.2f".format(getLatencyStdDevMs())},")
            appendLine("  \"latency_95th_percentile_ms\": ${"%.2f".format(getLatency95thPercentileMs())},")
            appendLine("  \"latency_99th_percentile_ms\": ${"%.2f".format(getLatency99thPercentileMs())},")
            appendLine("  \"latency_999th_percentile_ms\": ${"%.2f".format(getLatency999thPercentileMs())},")
            appendLine("  \"avg_jitter_ms\": ${"%.2f".format(getAverageJitterMs())},")
            appendLine("  \"stability_index\": ${"%.1f".format(getStabilityIndex())},")
            appendLine("  \"injection_success_rate\": ${"%.1f".format(getInjectionSuccessRate())},")
            appendLine("  \"total_injections\": ${totalInjections.get()},")
            appendLine("  \"successful_injections\": ${successfulInjections.get()},")
            appendLine("  \"failed_injections\": ${failedInjections.get()}")

            if (systemMetrics != null) {
                appendLine(",")
                appendLine("  \"system_metrics\": {")
                appendLine("    \"cpu_usage\": ${"%.1f".format(systemMetrics.cpuUsage)},")
                appendLine("    \"memory_usage_mb\": ${"%.1f".format(systemMetrics.memoryUsageMb)},")
                appendLine("    \"battery_level\": ${systemMetrics.batteryLevel},")
                appendLine("    \"battery_temperature\": ${"%.1f".format(systemMetrics.batteryTemperature)},")
                appendLine("    \"available_storage_mb\": ${systemMetrics.availableStorageMb},")
                appendLine("    \"total_storage_mb\": ${systemMetrics.totalStorageMb},")
                appendLine("    \"is_charging\": ${systemMetrics.isCharging},")
                appendLine("    \"process_cpu_usage\": ${"%.1f".format(systemMetrics.processCpuUsage)},")
                appendLine("    \"timestamp\": ${systemMetrics.timestamp}")
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
     * Obtém o percentil 95 da latência (em milissegundos)
     * Útil para identificar outliers de performance
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
     * Para identificar outliers extremos
     */
    fun getLatency999thPercentileMs(): Double {
        if (latencySamples.isEmpty()) return 0.0

        val sortedLatencies = latencySamples.toList().sorted()
        val index = (sortedLatencies.size * 0.999).toInt().coerceAtMost(sortedLatencies.size - 1)
        return sortedLatencies[index] / 1_000_000.0
    }

    /**
     * Calcula o índice de estabilidade (0-100)
     * Quanto maior, mais estável é a performance
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
     * Obtém o número de eventos processados por segundo
     */
    fun getEventsPerSecond(): Double {
        val totalEvents = eventsProcessed.get()
        val systemMetrics = getCurrentSystemMetrics()
        if (systemMetrics == null || totalEvents == 0) return 0.0

        // Estima o tempo decorrido com base no timestamp das métricas de sistema
        val elapsedSeconds = (System.currentTimeMillis() - systemMetrics.timestamp) / 1000.0
        if (elapsedSeconds <= 0) return 0.0

        return totalEvents / elapsedSeconds
    }

    /**
     * Extensão para converter CircularBuffer para lista
     */
    private fun CircularBuffer<Long>.toList(): List<Long> {
        val list = mutableListOf<Long>()
        for (i in 0 until size()) {
            list.add(get(i))
        }
        return list
    }
}

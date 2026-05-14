package com.splitscreen.inputbridge.metrics

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.os.StatFs
import java.io.BufferedReader
import java.io.FileReader
import java.io.RandomAccessFile

/**
 * Coletor de métricas de sistema (CPU, MEM, Bateria, Armazenamento)
 *
 * Fornece informações detalhadas sobre o estado do sistema para análise de performance
 */
class SystemMetricsCollector(private val context: Context) {

    companion object {
        private const val TAG = "SystemMetricsCollector"
        private const val CPU_STAT_PATH = "/proc/stat"
        private const val PROC_STAT_PATH = "/proc/%d/stat"
        private const val PROC_STATUS_PATH = "/proc/%d/status"
    }

    data class SystemMetrics(
        val cpuUsage: Float,           // Porcentagem de uso de CPU (0-100)
        val memoryUsageMb: Float,     // Uso de memória em MB
        val batteryLevel: Int,        // Nível da bateria (0-100)
        val batteryTemperature: Float, // Temperatura da bateria em Celsius
        val availableStorageMb: Long, // Armazenamento disponível em MB
        val totalStorageMb: Long,     // Armazenamento total em MB
        val isCharging: Boolean,       // Se está carregando
        val processCpuUsage: Float,    // Uso de CPU pelo processo atual
        val timestamp: Long            // Timestamp da coleta
    )

    private var lastCpuStats: LongArray? = null
    private var lastProcessCpuStats: LongArray? = null
    private var lastTimestamp: Long = 0

    /**
     * Coleta métricas completas do sistema
     */
    fun collectMetrics(): SystemMetrics {
        val currentTime = System.currentTimeMillis()

        // Coleta métricas individuais
        val cpuUsage = getCpuUsage()
        val memoryUsage = getMemoryUsageMb()
        val batteryInfo = getBatteryInfo()
        val storageInfo = getStorageInfo()
        val processCpuUsage = getProcessCpuUsage(currentTime)

        return SystemMetrics(
            cpuUsage = cpuUsage,
            memoryUsageMb = memoryUsage,
            batteryLevel = batteryInfo.first,
            batteryTemperature = batteryInfo.second,
            availableStorageMb = storageInfo.first,
            totalStorageMb = storageInfo.second,
            isCharging = batteryInfo.third,
            processCpuUsage = processCpuUsage,
            timestamp = currentTime
        )
    }

    /**
     * Obtém o uso de CPU do sistema
     */
    private fun getCpuUsage(): Float {
        try {
            val reader = BufferedReader(FileReader(CPU_STAT_PATH))
            val load = reader.readLine()
            reader.close()

            val tokens = load.split("\\s+".toRegex())
            if (tokens.size < 5) return 0f

            // Soma todos os tempos de CPU (user, nice, system, idle, iowait, irq, softirq)
            val total = tokens.subList(1, tokens.size).sumOf { it.toLongOrNull() ?: 0 }
            val idle = tokens[4].toLongOrNull() ?: 0

            val busy = total - idle
            return if (total > 0) (busy.toFloat() / total.toFloat() * 100f) else 0f
        } catch (e: Exception) {
            return 0f
        }
    }

    /**
     * Obtém o uso de CPU pelo processo atual
     */
    private fun getProcessCpuUsage(currentTime: Long): Float {
        try {
            val pid = Process.myPid()
            val statPath = String.format(PROC_STAT_PATH, pid)
            val reader = BufferedReader(FileReader(statPath))
            val statLine = reader.readLine()
            reader.close()

            val statTokens = statLine.split("\\s+".toRegex())
            if (statTokens.size < 14) return 0f

            // utime (14) + stime (15) + cutime (16) + cstime (17)
            val processCpuTime = (statTokens[13].toLongOrNull() ?: 0) +
                                (statTokens[14].toLongOrNull() ?: 0) +
                                (statTokens[15].toLongOrNull() ?: 0) +
                                (statTokens[16].toLongOrNull() ?: 0)

            // Obtém o tempo total de CPU do sistema
            val cpuReader = BufferedReader(FileReader(CPU_STAT_PATH))
            val cpuLine = cpuReader.readLine()
            cpuReader.close()

            val cpuTokens = cpuLine.split("\\s+".toRegex())
            if (cpuTokens.size < 5) return 0f

            val totalCpuTime = cpuTokens.subList(1, cpuTokens.size).sumOf { it.toLongOrNull() ?: 0 }

            // Calcula a diferença desde a última leitura
            if (lastProcessCpuStats != null && lastCpuStats != null && lastTimestamp > 0) {
                val deltaProcess = processCpuTime - lastProcessCpuStats!![0]
                val deltaSystem = totalCpuTime - lastCpuStats!![0]
                val deltaTime = currentTime - lastTimestamp

                if (deltaTime > 0 && deltaSystem > 0) {
                    val cpuUsage = (deltaProcess.toFloat() / deltaSystem.toFloat() * 100f)
                        .coerceIn(0f, 100f)
                    return cpuUsage
                }
            }

            // Atualiza os valores para a próxima leitura
            lastProcessCpuStats = longArrayOf(processCpuTime)
            lastCpuStats = longArrayOf(totalCpuTime)
            lastTimestamp = currentTime

            return 0f
        } catch (e: Exception) {
            return 0f
        }
    }

    /**
     * Obtém o uso de memória em MB
     */
    private fun getMemoryUsageMb(): Float {
        try {
            val memoryInfo = ActivityManager.MemoryInfo()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.getMemoryInfo(memoryInfo)

            val totalMemory = memoryInfo.totalMem / (1024 * 1024f)
            val availableMemory = memoryInfo.availMem / (1024 * 1024f)
            val usedMemory = totalMemory - availableMemory

            return usedMemory
        } catch (e: Exception) {
            return 0f
        }
    }

    /**
     * Obtém informações da bateria
     * Retorna: (nível, temperatura, está carregando)
     */
    private fun getBatteryInfo(): Triple<Int, Float, Boolean> {
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryIntent = context.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )

            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryLevel = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

            val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.toFloat() ?: 0f
            val batteryTemp = temperature / 10f // Converte de décimos de grau para Celsius

            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL

            return Triple(batteryLevel, batteryTemp, isCharging)
        } catch (e: Exception) {
            return Triple(0, 0f, false)
        }
    }

    /**
     * Obtém informações de armazenamento
     * Retorna: (disponível em MB, total em MB)
     */
    private fun getStorageInfo(): Pair<Long, Long> {
        try {
            val stat = StatFs(context.filesDir.path)
            val blockSize = stat.blockSizeLong
            val availableBlocks = stat.availableBlocksLong
            val totalBlocks = stat.blockCountLong

            val availableMb = availableBlocks * blockSize / (1024 * 1024)
            val totalMb = totalBlocks * blockSize / (1024 * 1024)

            return Pair(availableMb, totalMb)
        } catch (e: Exception) {
            return Pair(0L, 0L)
        }
    }

    /**
     * Obtém o número de threads do processo atual
     */
    fun getProcessThreadCount(): Int {
        try {
            val pid = Process.myPid()
            val statusPath = String.format(PROC_STATUS_PATH, pid)
            val reader = BufferedReader(FileReader(statusPath))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.startsWith("Threads:")) {
                    val threads = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                    reader.close()
                    return threads
                }
            }
            reader.close()
            return 0
        } catch (e: Exception) {
            return 0
        }
    }

    /**
     * Obtém informações detalhadas de uso de CPU por núcleo
     */
    fun getPerCoreCpuUsage(): List<Float> {
        try {
            val reader = BufferedReader(FileReader(CPU_STAT_PATH))
            val coresUsage = mutableListOf<Float>()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.startsWith("cpu")) {
                    val tokens = line!!.split("\\s+".toRegex())
                    if (tokens.size >= 5 && !line!!.startsWith("cpu ")) { // Ignora a linha agregada
                        val total = tokens.subList(1, tokens.size).sumOf { it.toLongOrNull() ?: 0 }
                        val idle = tokens[4].toLongOrNull() ?: 0
                        val busy = total - idle
                        val usage = if (total > 0) (busy.toFloat() / total.toFloat() * 100f) else 0f
                        coresUsage.add(usage)
                    }
                }
            }
            reader.close()

            return coresUsage
        } catch (e: Exception) {
            return emptyList()
        }
    }

    /**
     * Libera recursos
     */
    fun cleanup() {
        lastCpuStats = null
        lastProcessCpuStats = null
        lastTimestamp = 0
    }
}

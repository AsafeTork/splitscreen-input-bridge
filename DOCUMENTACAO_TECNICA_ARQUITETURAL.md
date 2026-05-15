# Documentação Técnica Arquitetural: SplitScreen Input Bridge

## 1. Visão Geral das Melhorias Implementadas

O projeto SplitScreen Input Bridge foi substancialmente aprimorado com a implementação de quatro sistemas principais que transformaram a arquitetura do serviço em uma solução robusta, monitorável e altamente configurável. Estas melhorias incluem:

### 1.1 Sistema de Métricas de Performance
Implementação completa de coleta de métricas em tempo real, incluindo latência de processamento, latência de injeção, FPS, taxa de sucesso de injeção, desvio padrão, percentis e métricas de sistema (CPU, memória, bateria).

### 1.2 Logging Estruturado com Alta Precisão
Sistema avançado de logging que gera entradas em formato JSON com timestamps de nanosegundos, níveis hierárquicos de log e persistência em arquivos com rotação automática.

### 1.3 Configuração Dinâmica
Gerenciador de configurações reativas que permite ajustes em tempo real sem reiniciar o serviço, com suporte a múltiplos perfis e modos operacionais (Battery Saver, Performance, Adaptive).

### 1.4 Persistência de Perfis
Sistema completo de gerenciamento de perfis de usuário com armazenamento de descritores de dispositivo, configurações específicas por perfil, exportação/importação em JSON e backup automático.

## 2. Detalhes Técnicos das Mudanças no Sistema de Permissões do Shizuku

### 2.1 Interface e Implementação Separadas
O sistema de permissões do Shizuku foi reestruturado para seguir o padrão Repository, separando a interface da implementação concreta:

```kotlin
interface ShizukuServiceInterface {
    fun injectInputEvent(event: InputEvent): Boolean
    fun execShellCommand(command: String): String
    fun getGlobalSetting(key: String): String
    fun isReady(): Boolean
    fun getDeviceMolecularFingerprint(device: InputDevice): String
}
```

Esta abordagem proporciona:
- **Testabilidade**: Facilita a criação de mocks para testes unitários
- **Injeção de dependência**: Permite substituição fácil de implementações
- **Manutenibilidade**: Separação clara entre interface e implementação

### 2.2 Implementação de Fallback
A implementação concreta (`ShizukuServiceRepository`) inclui mecanismos de fallback robustos:

```kotlin
override fun injectInputEvent(event: InputEvent): Boolean {
    return try {
        ShizukuUserService.injectInputEvent(event)
    } catch (e: Exception) {
        Log.e(TAG, "injectInputEvent failed: ${e.message}")
        false
    }
}
```

### 2.3 Tratamento de Exceções Especializadas
O serviço inclui tratamento especializado para diferentes tipos de exceções relacionadas a permissões:

```kotlin
override fun isReady(): Boolean {
    return try {
        val isReady = ShizukuUserService.isReady()
        // ... logica de verificação
    } catch (e: SecurityException) {
        Log.e(TAG, "SecurityException in isReady: ${e.message}")
        false
    } catch (e: IllegalStateException) {
        Log.e(TAG, "IllegalStateException in isReady: ${e.message}")
        false
    } catch (e: Exception) {
        Log.e(TAG, "isReady failed: ${e.message}")
        false
    }
}
```

### 2.4 Verificação Contínua de Permissões
O sistema implementa verificações contínuas de permissões com mecanismos de recuperação automática:

```kotlin
private fun handleCoroutineException(throwable: Throwable) {
    when (throwable) {
        is SecurityException -> {
            Log.w(TAG, "Security exception detected, checking Shizuku permissions")
            if (!shizukuService.isReady()) {
                stateManager.transitionTo(
                    BridgeState.Error("Permissão Shizuku revogada", throwable)
                )
            }
        }
        // ... outros tratamentos
    }
}
```

## 3. Melhorias na Arquitetura do Serviço de Acessibilidade

### 3.1 Padrão State Machine
O serviço implementa uma máquina de estados tipada segura usando sealed classes:

```kotlin
sealed class BridgeState {
    object Idle : BridgeState()
    object Initializing : BridgeState()
    data class Ready(val player1Descriptor: String, val player2Descriptor: String) : BridgeState()
    object Active : BridgeState()
    object Stopping : BridgeState()
    data class Error(val message: String, val cause: Throwable? = null) : BridgeState()
}
```

Benefícios:
- **Estado tipado seguro**: Todos os estados possíveis são representados
- **Transições validadas**: Método `canTransitionTo()` garante apenas transições válidas
- **Observabilidade**: Listeners para notificação de mudanças de estado

### 3.2 Gerenciamento Centralizado de Controladores
Implementação do padrão Repository para gerenciamento de controladores:

```kotlin
class ControllerRegistry(context: Context) {
    val controllersState = MutableStateFlow(ControllerAssignment("", "", false))
    
    fun assignController(player: Int, descriptor: String): Boolean {
        // Lógica de atribuição
    }
    
    fun refreshConnectedDevices() {
        // Atualização automática de dispositivos
    }
}
```

### 3.3 WorkManager para Watchdog Inteligente
Implementação de watchdog adaptativo usando WorkManager:

```kotlin
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Lógica adaptativa baseada em bateria
        val batteryLevel = getBatteryLevel()
        val interval = when {
            batteryLevel < 15 -> 15 * 60 * 1000L // 15 minutos
            batteryLevel < 30 -> 10 * 60 * 1000L // 10 minutos
            else -> 5 * 60 * 1000L // 5 minutos
        }
        
        // Aplicação de configurações do sistema
        applySystemSettings()
        
        return Result.success()
    }
}
```

## 4. Otimizações de Performance e Confiabilidade

### 4.1 Sistema de Métricas Avançado
Implementação completa de coleta de métricas:

```kotlin
class PerformanceMetrics(private val context: Context) {
    private val processingLatencies = CircularBuffer<Long>(1000)
    private val injectionLatencies = CircularBuffer<Long>(1000)
    private val injectionResults = CircularBuffer<Boolean>(1000)
    
    fun recordProcessingLatency(start: Long, end: Long) {
        processingLatencies.add(end - start)
    }
    
    fun recordInjectionLatency(latency: Long) {
        injectionLatencies.add(latency)
    }
    
    fun recordInjectionSuccess(success: Boolean) {
        injectionResults.add(success)
    }
    
    fun getCurrentFps(): Int { /* cálculo de FPS */ }
    fun getAverageProcessingLatencyMs(): Double { /* média de latência */ }
    fun getInjectionSuccessRate(): Double { /* taxa de sucesso */ }
}
```

### 4.2 Logging Estruturado com Alta Precisão
Sistema de logging que gera entradas JSON com timestamps precisos:

```kotlin
class StructuredLogger(
    private val tag: String,
    private val performanceMetrics: PerformanceMetrics? = null,
    private val enableFileLogging: Boolean = false
) {
    fun info(message: String, eventType: String?, context: Map<String, Any>? = null) {
        val logEntry = createLogEntry("INFO", message, eventType, context)
        Log.i(tag, logEntry.toJson())
        
        if (enableFileLogging) {
            writeToFile(logEntry.toJson())
        }
    }
    
    private fun createLogEntry(
        level: String,
        message: String,
        eventType: String?,
        context: Map<String, Any>?
    ): LogEntry {
        return LogEntry(
            timestamp = System.currentTimeMillis(),
            timestampNs = System.nanoTime(),
            level = level,
            message = message,
            eventType = eventType,
            context = context,
            performance = performanceMetrics?.getCurrentMetrics()
        )
    }
}
```

### 4.3 Configuração Dinâmica Reativa
Sistema de configuração que permite ajustes em tempo real:

```kotlin
class DynamicConfigManager(
    context: Context,
    private val logger: StructuredLogger
) {
    val configState = MutableStateFlow(loadConfigFromPreferences())
    
    fun updateConfig(vararg updates: Pair<String, Any>) {
        val currentConfig = configState.value.toMutableMap()
        updates.forEach { (key, value) ->
            currentConfig[key] = value
        }
        
        val newConfig = ConfigState.fromMap(currentConfig)
        configState.value = newConfig
        saveConfigToPreferences(newConfig)
        
        logger.info("Configuration updated", "config_change", currentConfig)
    }
    
    fun switchProfile(profileName: String) {
        val profile = loadProfile(profileName)
        configState.value = profile.config
        logger.info("Switched to profile", "profile_switch", mapOf("profile" to profileName))
    }
}
```

## 5. Impacto na Experiência do Usuário

### 5.1 Personalização Avançada
Os usuários agora podem:
- Criar e alternar entre múltiplos perfis de configuração
- Ajustar parâmetros em tempo real sem reiniciar o serviço
- Configurar modos específicos para diferentes cenários (bateria, performance)

### 5.2 Monitoramento Transparente
- Visualização em tempo real de métricas de performance
- Logs detalhados para diagnóstico de problemas
- Notificações de estado claras na barra de status

### 5.3 Estabilidade Aprimorada
- Recuperação automática de erros
- Verificação contínua de permissões
- Watchdog adaptativo que respeita condições de bateria

### 5.4 Diagnóstico Facilitado
- Exportação de perfis e configurações
- Relatórios de métricas em formato JSON
- Logs estruturados para análise automatizada

## 6. Próximos Passos e Recomendações Futuras

### 6.1 Interface de Usuário
- Desenvolver dashboard para visualização de métricas em tempo real
- Criar editor visual para configurações e perfis
- Implementar gráficos de performance histórica

### 6.2 Integração com Nuvem
- Sincronização de perfis entre dispositivos
- Backup automático em serviços de nuvem
- Compartilhamento de configurações entre usuários

### 6.3 Otimizações Avançadas
- Ajuste automático baseado em padrões de uso
- Detecção inteligente de configurações ótimas
- Sistema de alertas para condições anormais

### 6.4 Análise de Dados
- Coleta anônima de métricas para melhorias
- Detecção de padrões de uso para recomendações
- Machine learning para otimização automática

## Conclusão

As melhorias implementadas transformaram o InputBridgeService de um componente funcional básico em um sistema robusto, monitorável e altamente configurável. A arquitetura modular, com separação clara de responsabilidades e padrões de design bem estabelecidos, proporciona uma base sólida para desenvolvimento futuro e uma experiência de usuário significativamente aprimorada.

A implementação dos quatro sistemas principais (métricas, logging, configuração dinâmica e persistência de perfis) juntamente com as melhorias na arquitetura do serviço e no sistema de permissões do Shizuku estabelece este projeto como uma solução profissional e pronta para produção no ecossistema Android.
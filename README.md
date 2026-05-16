# Split Screen Manager - Android Project

## Build Instructions
Este projeto utiliza o Gradle Wrapper para garantir reprodutibilidade.

### Requisitos
- JDK 17+
- Android SDK (API 35, Build-Tools 35)

### Comandos de Build
Para gerar o APK de Debug:
```powershell
./gradlew assembleDebug
```

Para gerar o APK de Release (Otimizado com R8):
```powershell
./gradlew assembleRelease
```
O APK será gerado em: `app/build/outputs/apk/release/app-release.apk`.

## Arquitetura
- **MVVM**: O estado da UI é gerido pelo `AppViewModel`.
- **PermissionManager**: Gere fluxos de estado (`StateFlow`) reativos para Shizuku e Otimização de Bateria.
- **PerformanceManager**: Aplica otimizações de kernel (CPU Affinity, renice) e GPU via Shizuku.
- **Input Injection**: Pipeline de baixa latência em thread dedicada (`THREAD_PRIORITY_URGENT_DISPLAY`).

## Dependências Críticas
- **Shizuku API**: `dev.rikka.shizuku:api` (para interação com serviços de sistema sem Root).
- **Jetpack Compose**: UI moderna e declarativa.

## Notas de Release
- O build de Release utiliza o R8 para minificação e redução de recursos.
- Configuração de ProGuard incluída em `app/proguard-rules.pro`.

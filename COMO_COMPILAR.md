# SplitScreen Input Bridge — Guia de Compilação e Uso

## Pré-requisitos

| Ferramenta | Versão mínima |
|---|---|
| Android Studio | Hedgehog (2023.1.1+) |
| JDK | 17 |
| Android SDK | API 33 (Tiramisu) |
| Dispositivo / Emulador | Android 13+ |
| Shizuku | v13+ instalado no dispositivo |

---

## 1. Importar o projeto

1. Abra o Android Studio
2. `File → Open` → selecione a pasta `android-project/`
3. Aguarde o Gradle sync finalizar (baixará todas as dependências automaticamente)

---

## 2. Compilar e instalar

```bash
# Debug APK (recomendado para testes)
./gradlew assembleDebug

# O APK estará em:
# app/build/outputs/apk/debug/app-debug.apk

# Instalar diretamente via ADB:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 3. Preparar o dispositivo

### 3a. Ativar o Shizuku
O Shizuku precisa estar rodando no dispositivo. Há dois modos:

**Via ADB (computador):**
```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

**Via ADB Wireless (Android 11+):**  
Nas Opções do Desenvolvedor → Depuração Wi-Fi → Iniciar Shizuku pelo ADB emparelhado.

### 3b. Ativar o Serviço de Acessibilidade
```
Configurações → Acessibilidade → Serviços Instalados → SplitScreen Input Bridge → Ativar
```
> ⚠️ Este passo é **obrigatório**. O `InputBridgeAccessibilityService` é o interceptor de eventos do gamepad.

### 3c. Conceder permissão Shizuku ao app
Abra o app → toque em **"Conceder Permissão Shizuku"** → confirme no dialog do Shizuku.

---

## 4. Configurar os controles

1. Conecte os **dois gamepads Bluetooth** (mesmo modelo/marca)
2. No app, na seção **Player 1** → selecione o primeiro controle no dropdown
3. Na seção **Player 2** → selecione o segundo controle
   > Os gamepads aparecem com nome idêntico mas com **Descriptor diferente** (hash único de hardware). É assim que o app diferencia os dois.
4. Toque em **"Ativar Bridge"**

---

## 5. Abrir o Minecraft em Split-Screen

1. Abra o Minecraft Bedrock
2. Mantenha pressionado o ícone do Minecraft no recents → "Dividir tela"
3. Selecione uma segunda instância do Minecraft (ou o mesmo app na segunda metade)
4. O Player 1 controlará a janela com foco (metade superior)
5. O Player 2 terá seus inputs transformados em toques sintéticos na metade inferior

---

## Arquitetura Técnica

### Por que não é possível rotear por TaskID diretamente?

Pesquisa no AOSP `InputDispatcher.cpp` confirma:
- O `InputDispatcher` usa um `FocusResolver` que mantém **um único token de foco** por vez
- `dispatchMotionLocked()` verifica `inputWindowHandle->hasFocus()` antes de despachar
- Não há API pública para enviar eventos a uma janela sem foco por TaskID

**Solução implementada — Transformação de Coordenadas por Viewport:**

```
Gamepad P2 (eixos -1.0 a +1.0)
        ↓
injetado como toque sintético SOURCE_TOUCHSCREEN
        ↓
  touchX = ((axisX + 1.0) / 2.0) × screenWidth
  touchY = (screenHeight/2) + ((axisY + 1.0) / 2.0) × (screenHeight/2)
        ↓
coordenadas mapeadas para a metade INFERIOR da tela
        ↓
injetado via IInputManager.injectInputEvent() através do Shizuku binder
```

### Diferenciação de hardware idêntico

```kotlin
// InputDevice.getDescriptor() retorna um hash único do hardware físico
// mesmo que name, vendorId e productId sejam idênticos nos dois controles
val descriptor = InputDevice.getDevice(deviceId).descriptor
// Ex: "a0b1c2d3e4f5..." vs "f5e4d3c2b1a0..."
```

### System hack obrigatório

```bash
settings put global multi_window_focus_enabled 1
```

Instrui o `WindowManagerService` a não cancelar o processamento de input na janela secundária quando a primária está em foco — essencial para que ambas as instâncias do Minecraft processem eventos simultaneamente.

---

## Estrutura de Arquivos

```
app/src/main/
├── java/com/splitscreen/inputbridge/
│   ├── MainActivity.kt                    # UI Compose + gerenciamento de estado
│   ├── InputBridgeService.kt              # Foreground service, lógica de roteamento
│   ├── ShizukuUserService.kt              # Acesso privilegiado via Shizuku binder
│   ├── IShizukuUserService.kt             # Interface Kotlin do user service
│   ├── InputBridgeAccessibilityService.kt # Interceptor global de input events
│   └── ui/theme/Theme.kt                  # Tema Material3 escuro
├── aidl/com/splitscreen/inputbridge/
│   └── IShizukuUserService.aidl           # Definição AIDL do serviço privilegiado
├── res/
│   ├── values/strings.xml
│   ├── values/themes.xml
│   └── xml/accessibility_service_config.xml
└── AndroidManifest.xml
```

---

## Limitações Conhecidas

- O Minecraft Bedrock pode usar SurfaceView nativo que não responde a toques sintéticos em algumas versões — neste caso, use o modo **Shizuku Shell fallback** (`input tap x y`) ativado automaticamente
- Em dispositivos sem Shizuku (sem root e sem ADB wireless), a bridge não funcionará — o `INJECT_EVENTS` é uma permissão de sistema
- O split-screen simultâneo do Minecraft requer que ambas as instâncias estejam na mesma conta ou em contas diferentes (use perfis Android)

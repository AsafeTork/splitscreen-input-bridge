# Conexão ADB via WiFi

## Passos para conectar seu dispositivo Android via WiFi

1. **Conecte o dispositivo via USB primeiro**
   - Conecte o cabo USB ao seu dispositivo
   - Execute: `adb devices` para confirmar a conexão

2. **Ative o modo TCP/IP**
   ```bash
   adb tcpip 5555
   ```

3. **Descubra o IP do seu dispositivo**
   - No dispositivo: Configurações → Wi-Fi → Detalhes da rede
   - Ou via ADB: `adb shell ip route | grep wlan0`

4. **Conecte via WiFi**
   ```bash
   adb connect [IP_DO_SEU_DISPOSITIVO]:5555
   ```

5. **Verifique a conexão**
   ```bash
   adb devices
   ```

## Se não funcionar via USB

Se não conseguir conectar via USB, tente:

1. **No dispositivo Android:**
   - Ative "Depuração USB" nas Opções do Desenvolvedor
   - Ative "Depuração sem fio" (Android 11+)

2. **Usar o IP do dispositivo diretamente:**
   ```bash
   adb connect 192.168.1.100:5555  # Substitua pelo IP real
   ```

## Teste de APK no dispositivo

Uma vez conectado:
```bash
# Instalar APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Verificar se está instalado
adb shell pm list packages | grep splitscreen

# Iniciar app (se necessário)
adb shell am start -n com.splitscreen.inputbridge/.MainActivity
```
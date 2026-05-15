# SplitScreen Input Bridge - Monitoramento Automático

Este projeto inclui um sistema de monitoramento automático para verificar continuamente se o APK está sendo construído corretamente, com correção automática de erros e demonstração visual do app funcionando.

## 🚀 Como Usar o Monitoramento Automático

### No Windows (PowerShell)
```powershell
# Executar o script de monitoramento
.\monitor_apk.ps1
```

### No Linux/macOS (Bash)
```bash
# Tornar o script executável
chmod +x monitor_apk.sh

# Executar o script de monitoramento
./monitor_apk.sh
```

## 📋 O Que o Monitoramento Faz

1. **Constrói o APK** a cada 5 minutos usando `./gradlew assembleDebug`
2. **Verifica se o APK foi gerado** corretamente
3. **Valida a estrutura do APK** (se ferramentas disponíveis)
4. **Envia notificações** sobre sucesso ou falha
5. **Registra logs** detalhados para debugging
6. **Corrige automaticamente erros** quando possível
7. **Mostra o app funcionando** no dispositivo conectado

## 🛠️ Configuração do GitHub Actions

O workflow em `.github/workflows/main.yml` é configurado para:

- Executar a cada 10 minutos (`*/10 * * * *`)
- Construir o APK automaticamente
- Validar a estrutura do APK
- Armazenar o APK como artefato

## 📊 Resultados dos Testes

Os resultados são registrados em:
- `build_log.txt` - Logs completos da construção
- `apk_info.txt` - Informações do APK (se validação disponível)
- `auto_fix_log.txt` - Logs da correção automática
- `build_final.log` - Logs da construção final

## 🔧 Solução de Problemas

Se o APK não estiver sendo instalado:

1. **Verifique os logs de build** em `build_log.txt`
2. **Valide as permissões** do APK
3. **Confirme as dependências** do projeto
4. **Teste a construção manual** com `./gradlew assembleDebug`
5. **Execute correção automática** com `./auto_fix_apk.sh`

## 📱 Testes no Dispositivo

Para testar no dispositivo conectado:

```bash
# Instalar APK no dispositivo
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Verificar se o app está instalado
adb shell pm list packages | grep splitscreen

# Iniciar app
adb shell am start -n com.splitscreen.inputbridge/.MainActivity
```

## 🎮 Demonstração Completa

Para ver o app funcionando:

### No Windows (PowerShell)
```powershell
# Mostrar app funcionando completamente
.\show_app_working.ps1
```

### No Linux/macOS (Bash)
```bash
# Tornar o script executável
chmod +x show_app_working.sh

# Mostrar app funcionando completamente
./show_app_working.sh
```

## 🛠️ Scripts Disponíveis

- `monitor_apk.ps1` / `monitor_apk.sh` - Monitoramento contínuo
- `auto_fix_apk.ps1` / `auto_fix_apk.sh` - Correção automática de erros
- `diagnostic_apk.ps1` / `diagnostic_apk.sh` - Diagnóstico completo
- `demo_app.ps1` / `demo_app.sh` - Demonstração visual
- `show_app_working.ps1` / `show_app_working.sh` - Demonstração completa
- `WIFI_ADB_GUIDE.md` - Guia de conexão WiFi

## 🔄 Sistema de Correção Automática

O sistema detecta e corrige automaticamente:

- **Erros de dependência** - Limpa cache e atualiza dependências
- **Erros de compilação** - Reconstrói o projeto
- **Erros de memória** - Ajusta configurações do Gradle
- **Erros de instalação** - Reinstala o APK no dispositivo

## 📡 Conexão WiFi

Veja `WIFI_ADB_GUIDE.md` para instruções completas de conexão WiFi.
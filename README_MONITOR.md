# SplitScreen Input Bridge - Monitoramento Automático

Este projeto inclui um sistema de monitoramento automático para verificar continuamente se o APK está sendo construído corretamente.

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

## 🔧 Solução de Problemas

Se o APK não estiver sendo instalado:

1. **Verifique os logs de build** em `build_log.txt`
2. **Valide as permissões** do APK
3. **Confirme as dependências** do projeto
4. **Teste a construção manual** com `./gradlew assembleDebug`

## 📱 Testes no Dispositivo

Para testar no dispositivo conectado:

```bash
# Instalar APK no dispositivo
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Verificar se o app está instalado
adb shell pm list packages | grep splitscreen
```
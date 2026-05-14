#!/bin/bash

# Script para configurar e iniciar o loop de monitoramento automático
# Este script cria um serviço systemd para monitoramento contínuo

echo "🔄 Configurando loop de monitoramento automático..."

# Verificar se estamos no Linux
if [[ "$OSTYPE" != "linux-gnu"* ]]; then
    echo "⚠️  Este script é para Linux. Use monitor_apk.ps1 no Windows."
    exit 1
fi

# Criar serviço systemd
sudo tee /etc/systemd/system/apk-monitor.service > /dev/null <<EOF
[Unit]
Description=APK Monitor Service
After=network.target

[Service]
Type=simple
User=$USER
WorkingDirectory=$(pwd)
ExecStart=$(pwd)/monitor_apk.sh
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# Recarregar systemd e habilitar serviço
sudo systemctl daemon-reload
sudo systemctl enable apk-monitor.service

echo "✅ Serviço configurado!"

# Perguntar se deseja iniciar agora
read -p "Deseja iniciar o serviço agora? (s/n): " -n 1 -r
echo
if [[ $REPLY =~ ^[Ss]$ ]]; then
    sudo systemctl start apk-monitor.service
    echo "🚀 Serviço iniciado!"
    echo "📊 Status: sudo systemctl status apk-monitor.service"
    echo "🛑 Parar: sudo systemctl stop apk-monitor.service"
    echo "📄 Logs: sudo journalctl -u apk-monitor.service -f"
fi

echo "🔧 Para configurar notificações, edite monitor_apk.sh e adicione integração com Slack/Email/etc."
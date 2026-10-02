#!/bin/bash
# ============================================================
# ⚡ Rejoin Cloud: One-Click Installer for Termux / Redfinger
# ใช้งาน: curl -sL http://YOUR_SERVER:3000/install.sh | bash -s -- --key YOUR_KEY
# ============================================================

KEY=""
SERVER_URL=""

while [[ "$#" -gt 0 ]]; do
    case $1 in
        --key) KEY="$2"; shift ;;
        --server) SERVER_URL="$2"; shift ;;
        *) echo "Unknown parameter: $1"; exit 1 ;;
    esac
    shift
done

if [ -z "$KEY" ]; then
    echo "[!] กรุณาระบุ License Key เช่น: bash install.sh --key RJ-XXXX"
    read -p "ป้อน License Key ของคุณ: " KEY
fi

if [ -z "$SERVER_URL" ]; then
    SERVER_URL="http://localhost:3000"
fi

echo "=============================================="
echo "🚀 กำลังติดตั้ง Rejoin Cloud Agent ในเครื่อง..."
echo "🔑 Key: $KEY"
echo "🌐 Server: $SERVER_URL"
echo "=============================================="

# 1. ติดตั้ง Python และแพ็กเกจที่จำเป็น
pkg update -y > /dev/null 2>&1
pkg install python -y > /dev/null 2>&1
pip install python-socketio[client] requests websocket-client > /dev/null 2>&1

# 2. ดาวน์โหลด agent.py
mkdir -p ~/rejoin
curl -sL "$SERVER_URL/agent/agent.py" -o ~/rejoin/agent.py

# 3. รันโปรแกรมในเบื้องหลัง
echo "[✓] เริ่มต้นการทำงานของ Agent..."
nohup python ~/rejoin/agent.py --server "$SERVER_URL" --key "$KEY" > ~/rejoin/agent.log 2>&1 &

echo "=============================================="
echo "🎉 ติดตั้งและเริ่มทำงานสำเร็จแล้ว!"
echo "คุณสามารถปิดหน้าต่างนี้ แล้วกลับไปดูหน้า Dashboard บนเว็บได้เลย"
echo "=============================================="

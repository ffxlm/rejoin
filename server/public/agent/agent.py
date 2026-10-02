#!/usr/bin/env python3
"""
⚡ ROBLOX AUTO-REJOIN: ANDROID AGENT
รันสคริปต์นี้ในเครื่อง Redfinger (ผ่าน Termux ด้วยสิทธิ์ Root)
หน้าที่:
1. เชื่อมต่อ WebSocket ไปยังเซิร์ฟเวอร์หลักด้วย License Key
2. เปิด Local HTTP Server (พอร์ต 8080) รับสัญญาณ Ping จากสคริปต์ในเกม
3. ระบบ Watchdog เฝ้าดูสัญญาณชีพ ถ้าหลุดจะสั่งคำสั่ง Root (su) เพื่อ Rejoin ทันที
"""

import os
import sys
import time
import json
import base64
import socket
import threading
import subprocess
import argparse
from http.server import HTTPServer, BaseHTTPRequestHandler

try:
    import socketio
except ImportError:
    print("[!] กำลังติดตั้ง python-socketio และ websocket-client...")
    subprocess.run([sys.executable, "-m", "pip", "install", "python-socketio[client]", "requests", "websocket-client"])
    import socketio

# --- Arguments & Config ---
parser = argparse.ArgumentParser(description="Roblox Auto-Rejoin Device Agent")
parser.add_argument("--server", default="http://localhost:3000", help="URL ของเซิร์ฟเวอร์หลัก")
parser.add_argument("--key", required=True, help="License Key สำหรับเชื่อมต่อ")
parser.add_argument("--name", default="", help="ชื่อเรียกเครื่องนี้ (ถ้าไม่ใส่จะตั้งให้อัตโนมัติ)")
parser.add_argument("--port", type=int, default=8080, help="พอร์ตสำหรับรับ Ping จากสคริปต์ในเกม")
parser.add_argument("--timeout", type=int, default=35, help="วินาทีที่สัญญาณชีพหายแล้วถือว่าหลุด")
parser.add_argument("--grace", type=int, default=60, help="วินาทีที่รอให้เกมโหลดก่อนเริ่มจับเวลา")
args = parser.parse_args()

SERVER_URL = args.server
LICENSE_KEY = args.key
DEVICE_NAME = args.name or f"Redfinger-{socket.gethostname()[:8]}"
LOCAL_PORT = args.port
HEARTBEAT_TIMEOUT = args.timeout
GRACE_PERIOD = args.grace

# Device Unique ID
DEVICE_ID = f"dev_{socket.gethostname()}_{abs(hash(LICENSE_KEY)) % 10000}"

# State variables
sio = socketio.Client(reconnection=True, reconnection_attempts=0, reconnection_delay=2)
last_heartbeat_time = 0
is_active = False
target_place_id = ""
rejoin_count = 0
in_grace_period_until = 0

# --- Root Command Execution Helper ---
def run_root_command(cmd):
    """รันคำสั่งด้วยสิทธิ์ su (Root) หรือรันปกติถ้าไม่มี Root"""
    try:
        # ลองรันด้วย su ก่อน
        full_cmd = f'su -c "{cmd}"'
        res = subprocess.run(full_cmd, shell=True, capture_output=True, text=True, timeout=10)
        if res.returncode == 0:
            return True, res.stdout.strip()
    except Exception:
        pass

    # ถ้ารัน su ไม่ได้ ให้ลองรันตรงๆ
    try:
        res = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=10)
        return res.returncode == 0, res.stdout.strip()
    except Exception as e:
        return False, str(e)

def perform_rejoin(place_id):
    """คำสั่งเปิดเข้าแมพ Roblox ใหม่"""
    global in_grace_period_until, rejoin_count
    print(f"\n[⚡] กำลังเริ่มกระบวนการ AUTO-REJOIN (Place ID: {place_id})...")

    # 1. แจ้งเซิร์ฟเวอร์ว่ากำลัง Rejoin
    if sio.connected:
        sio.emit('agent:status', {'status': 'rejoining'})

    # 2. ปิดแอปเดิมที่ค้าง
    print("[*] บังคับปิด Roblox เดิม (Force Stop)...")
    run_root_command("am force-stop com.roblox.client")
    time.sleep(2)

    # 3. สั่งเปิดเข้าแมพใหม่ผ่าน Deep Link Intent
    print(f"[*] เปิด Roblox เข้าสู่ Place ID: {place_id}...")
    run_root_command(f'am start -a android.intent.action.VIEW -d "roblox://experiences/start?placeId={place_id}"')

    # 4. ตั้งเวลาผ่อนผันรอเกมโหลด (Grace Period)
    in_grace_period_until = time.time() + GRACE_PERIOD
    rejoin_count += 1

    # 5. แจ้งรายงานขึ้นเซิร์ฟเวอร์
    if sio.connected:
        sio.emit('agent:rejoined', {'count': rejoin_count})
    print(f"[✓] ส่งคำสั่งเปิดเกมแล้ว! เข้าสู่ Grace Period รอเกมโหลด ({GRACE_PERIOD} วินาที)\n")

def capture_screenshot_base64():
    """แคปภาพหน้าจอ: รองรับทั้ง Android (screencap) และ PC (Pillow ImageGrab/Placeholder)"""
    try:
        # 1. ถ้าอยู่บน Android (มี screencap)
        is_android = os.path.exists("/system/bin/screencap") or os.path.exists("/system/bin/sh")
        if is_android:
            tmp_path = "/sdcard/rejoin_snap.png"
            run_root_command(f"screencap -p {tmp_path}")
            if os.path.exists(tmp_path):
                with open(tmp_path, "rb") as f:
                    encoded = base64.b64encode(f.read()).decode('utf-8')
                    return f"data:image/png;base64,{encoded}"

        # 2. ถ้ากำลังทดสอบบน Windows / PC
        try:
            from PIL import ImageGrab
            import io
            screenshot = ImageGrab.grab()
            screenshot.thumbnail((854, 480)) # ย่อขนาดเพื่อความเร็ว
            buf = io.BytesIO()
            screenshot.save(buf, format="JPEG", quality=65)
            encoded = base64.b64encode(buf.getvalue()).decode('utf-8')
            return f"data:image/jpeg;base64,{encoded}"
        except ImportError:
            # ถ้าไม่มี Pillow ให้ติดตั้งอัตโนมัติ
            subprocess.run([sys.executable, "-m", "pip", "install", "pillow"])
            from PIL import ImageGrab
            import io
            screenshot = ImageGrab.grab()
            screenshot.thumbnail((854, 480))
            buf = io.BytesIO()
            screenshot.save(buf, format="JPEG", quality=65)
            encoded = base64.b64encode(buf.getvalue()).decode('utf-8')
            return f"data:image/jpeg;base64,{encoded}"
    except Exception as e:
        print(f"[!] ไม่สามารถแคปภาพหน้าจอได้: {e}")
        return ""

# --- Local HTTP Server (รับ Ping จาก Lua) ---
class PingHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        global last_heartbeat_time, target_place_id
        if self.path == '/ping':
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length).decode('utf-8')
            
            try:
                data = json.loads(body) if body else {}
            except Exception:
                data = {}

            last_heartbeat_time = time.time()
            if data.get('placeId') and not target_place_id:
                target_place_id = str(data.get('placeId'))

            # ส่งข้อมูลต่อไปยังเซิร์ฟเวอร์หลักแบบเรียลไทม์
            if sio.connected:
                sio.emit('agent:heartbeat', data)

            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
        else:
            self.send_response(404)
            self.end_headers()

    def do_GET(self):
        global last_heartbeat_time
        if self.path == '/ping':
            last_heartbeat_time = time.time()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        # ปิด log ธรรมดาไม่ให้รกหน้าจอ
        return

def run_http_server():
    server = HTTPServer(('0.0.0.0', LOCAL_PORT), PingHandler)
    print(f"[✓] เปิดตัวรับสัญญาณชีพ (Local Server) ที่พอร์ต {LOCAL_PORT} สำเร็จ")
    server.serve_forever()

# --- Watchdog Thread ---
def watchdog_loop():
    global last_heartbeat_time, in_grace_period_until
    while True:
        time.sleep(2)
        now = time.time()

        if not is_active:
            continue

        # ถ้าอยู่ในช่วงรอเกมโหลด (Grace Period) ให้ข้าม
        if now < in_grace_period_until:
            continue

        # ถ้าไม่เคยมี ping เข้ามาเลย หรือ ping หายไปเกิน TIMEOUT
        elapsed = now - last_heartbeat_time
        if last_heartbeat_time > 0 and elapsed > HEARTBEAT_TIMEOUT:
            print(f"[⚠️] สัญญาณชีพหายไปแล้ว {int(elapsed)} วินาที! (เกินเกณฑ์ {HEARTBEAT_TIMEOUT} วินาที)")
            if target_place_id:
                perform_rejoin(target_place_id)
            else:
                print("[!] ไม่พบ Place ID กรุณากรอก Place ID บนหน้าเว็บ")

# --- Socket.io Event Handlers ---
@sio.event
def connect():
    print(f"[✓] เชื่อมต่อกับเซิร์ฟเวอร์หลักสำเร็จ! (Device: {DEVICE_NAME})")

@sio.event
def disconnect():
    print("[!] ขาดการเชื่อมต่อจากเซิร์ฟเวอร์หลัก กำลังพยายามเชื่อมต่อใหม่...")

@sio.on('auth:error')
def on_auth_error(data):
    print(f"[❌] เซิร์ฟเวอร์ปฏิเสธการเชื่อมต่อ: {data.get('reason')}")
    sys.exit(1)

@sio.on('command:start')
def on_start(data):
    global is_active, target_place_id, in_grace_period_until
    is_active = True
    if data.get('placeId'):
        target_place_id = str(data.get('placeId'))
    print(f"[▶️] ได้รับคำสั่ง: เริ่มเฝ้าจอ (Target Place ID: {target_place_id})")
    in_grace_period_until = time.time() + 10  # ให้เวลา 10 วิ
    if sio.connected:
        sio.emit('agent:status', {'status': 'farming'})

@sio.on('command:stop')
def on_stop():
    global is_active
    is_active = False
    print("[⏹️] ได้รับคำสั่ง: หยุดการเฝ้าจอ")
    if sio.connected:
        sio.emit('agent:status', {'status': 'idle'})

@sio.on('command:rejoin')
def on_force_rejoin(data):
    place_id = data.get('placeId') or target_place_id
    if place_id:
        perform_rejoin(place_id)

@sio.on('command:request_snapshot')
def on_request_snapshot():
    print("[📸] ได้รับคำสั่ง: ถ่ายภาพหน้าจอ...")
    img_b64 = capture_screenshot_base64()
    if img_b64 and sio.connected:
        sio.emit('agent:snapshot', {'imageBase64': img_b64})
        print("[✓] ส่งภาพหน้าจอขึ้นเว็บสำเร็จ")

# --- Main Entry Point ---
if __name__ == '__main__':
    print("=" * 55)
    print(f"🚀 ROBLOX AUTO-REJOIN AGENT กำลังเริ่มทำงาน...")
    print(f"🔑 License Key: {LICENSE_KEY}")
    print(f"📱 Device ID:   {DEVICE_ID} ({DEVICE_NAME})")
    print(f"🌐 Server:      {SERVER_URL}")
    print("=" * 55)

    # 1. Start Local HTTP Server for Lua Pings in background
    t_http = threading.Thread(target=run_http_server, daemon=True)
    t_http.start()

    # 2. Start Watchdog thread
    t_watchdog = threading.Thread(target=watchdog_loop, daemon=True)
    t_watchdog.start()

    # 3. Connect to main Web Server
    connect_url = f"{SERVER_URL}?role=agent&key={LICENSE_KEY}&deviceId={DEVICE_ID}&deviceName={DEVICE_NAME}"
    try:
        sio.connect(connect_url)
        sio.wait()
    except KeyboardInterrupt:
        print("\n[*] ปิดโปรแกรม Agent")
    except Exception as e:
        print(f"[!] เกิดข้อผิดพลาด: {e}")

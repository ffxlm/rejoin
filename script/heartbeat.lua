-- ============================================================
-- ⚡ ROBLOX AUTO-REJOIN: HEARTBEAT SCRIPT
-- นำไฟล์นี้ไปวางในโฟลเดอร์ autoexecute ของตัวรัน (Delta, Arceus, Fluxus ฯลฯ)
-- ============================================================

local HttpService = game:GetService("HttpService")
local Players = game:GetService("Players")

-- รอจนกว่าตัวละครและข้อมูลผู้เล่นจะโหลดเสร็จ
repeat task.wait(1) until Players.LocalPlayer

local player = Players.LocalPlayer

-- ============================================================
-- ⚙️ การตั้งค่า IP:
-- 1. ถ้าเล่นในเครื่องเดียวกันกับที่รันแอป (เช่น ใน Redfinger): ใช้ "127.0.0.1"
-- 2. ถ้าเล่นในวงแลน/คนละเครื่อง (เช่น เล่นบนมือถือ/จำลอง แล้วรันแอปบนคอม):
--    ให้เปลี่ยนเป็น IP วงแลนของคอม เช่น "192.168.1.50"
-- ============================================================
local AGENT_HOST = "127.0.0.1" -- เปลี่ยนเป็น IP คอมพิวเตอร์ของคุณถ้าอยู่คนละเครื่องในวงแลน
local AGENT_PORT = 8080
local PING_URL = "http://" .. AGENT_HOST .. ":" .. tostring(AGENT_PORT) .. "/ping"
local PING_INTERVAL = 10 -- ส่งสัญญาณทุกๆ 10 วินาที

-- ตรวจสอบฟังก์ชัน HTTP Request ที่ตัวรันรองรับ
local httpRequest = (syn and syn.request) or (http and http.request) or request or http_request

if not httpRequest then
    warn("[Rejoin] ตัวรันของคุณไม่รองรับคำสั่ง request()")
    return
end

print("[Rejoin] เริ่มต้นระบบส่งสัญญาณชีพ (Heartbeat) เรียบร้อยแล้ว!")

-- ลูปส่งสัญญาณชีพไปหา Agent ในเครื่อง
task.spawn(function()
    while true do
        pcall(function()
            local payload = HttpService:JSONEncode({
                username = player.Name,
                displayName = player.DisplayName,
                userId = player.UserId,
                placeId = tostring(game.PlaceId)
            })

            httpRequest({
                Url = PING_URL,
                Method = "POST",
                Headers = {
                    ["Content-Type"] = "application/json"
                },
                Body = payload
            })
        end)
        task.wait(PING_INTERVAL)
    end
end)

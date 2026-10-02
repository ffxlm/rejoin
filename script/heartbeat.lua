-- ============================================================
-- ⚡ ROBLOX AUTO-REJOIN: HEARTBEAT SCRIPT
-- นำไฟล์นี้ไปวางในโฟลเดอร์ autoexecute ของตัวรัน (Delta, Arceus, Fluxus ฯลฯ)
-- ============================================================

local HttpService = game:GetService("HttpService")
local Players = game:GetService("Players")

-- รอจนกว่าตัวละครและข้อมูลผู้เล่นจะโหลดเสร็จ
repeat task.wait(1) until Players.LocalPlayer

local player = Players.LocalPlayer
local PING_URL = "http://127.0.0.1:8080/ping"
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

package com.rejoin.cloud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;

import io.socket.client.IO;

public class RejoinService extends Service {

    private static final String TAG = "RejoinService";
    private static final String CHANNEL_ID = "rejoin_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static boolean isRunning = false;

    private io.socket.client.Socket mSocket;
    private ServerSocket mHttpServer;
    private Thread mHttpThread;
    private Thread mWatchdogThread;

    private String serverUrl = "";
    private String licenseKey = "";
    private String deviceId = "";
    private String targetPlaceId = "";
    private boolean isActive = false;
    private long lastHeartbeatTime = 0;
    private long gracePeriodUntil = 0;
    private int rejoinCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            serverUrl = intent.getStringExtra("server_url");
            licenseKey = intent.getStringExtra("license_key");
        }

        deviceId = "dev_" + Build.MODEL.replaceAll("\\s+", "_") + "_" + (Math.abs(licenseKey.hashCode()) % 10000);

        startForeground(NOTIFICATION_ID, buildNotification("กำลังเริ่มการทำงาน..."));
        isRunning = true;

        startLocalHttpServer();
        connectWebSocket();
        startWatchdog();

        sendStatusBroadcast("● กำลังเชื่อมต่อเซิร์ฟเวอร์...");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        disconnectWebSocket();
        stopLocalHttpServer();
        sendStatusBroadcast("● ปิดการทำงานแล้ว");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- Socket.io WebSocket Connection ---
    private void connectWebSocket() {
        try {
            IO.Options options = new IO.Options();
            options.reconnection = true;
            options.query = "role=agent&key=" + licenseKey + "&deviceId=" + deviceId + "&deviceName=Redfinger-" + Build.MODEL;

            mSocket = IO.socket(URI.create(serverUrl), options);

            mSocket.on(io.socket.client.Socket.EVENT_CONNECT, args -> {
                Log.d(TAG, "Connected to server!");
                sendStatusBroadcast("● เชื่อมต่อสำเร็จ (Connected)");
            });

            mSocket.on(io.socket.client.Socket.EVENT_DISCONNECT, args -> {
                Log.d(TAG, "Disconnected from server");
                sendStatusBroadcast("● ขาดการเชื่อมต่อ (Reconnecting...)");
            });

            mSocket.on("command:start", args -> {
                isActive = true;
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    JSONObject obj = (JSONObject) args[0];
                    targetPlaceId = obj.optString("placeId", targetPlaceId);
                }
                gracePeriodUntil = System.currentTimeMillis() + 10000;
                sendStatusBroadcast("● กำลังเฝ้าจอ (Farming)");
            });

            mSocket.on("command:stop", args -> {
                isActive = false;
                sendStatusBroadcast("● หยุดการเฝ้าจอ (Idle)");
            });

            mSocket.on("command:rejoin", args -> {
                String placeId = targetPlaceId;
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    JSONObject obj = (JSONObject) args[0];
                    placeId = obj.optString("placeId", targetPlaceId);
                }
                performRejoin(placeId);
            });

            mSocket.on("command:request_snapshot", args -> {
                captureAndSendSnapshot();
            });

            mSocket.connect();
        } catch (Exception e) {
            Log.e(TAG, "Socket connect error", e);
            sendStatusBroadcast("● ข้อผิดพลาด URL: " + e.getMessage());
        }
    }

    private void disconnectWebSocket() {
        if (mSocket != null) {
            mSocket.disconnect();
            mSocket.off();
            mSocket = null;
        }
    }

    // --- Local HTTP Server on Port 8080 (Ping from Lua) ---
    private void startLocalHttpServer() {
        mHttpThread = new Thread(() -> {
            try {
                mHttpServer = new ServerSocket(8080);
                Log.d(TAG, "Local HTTP Server started on port 8080");

                while (isRunning && !mHttpServer.isClosed()) {
                    Socket client = mHttpServer.accept();
                    handleClientRequest(client);
                }
            } catch (Exception e) {
                Log.e(TAG, "HTTP Server error", e);
            }
        });
        mHttpThread.start();
    }

    private void handleClientRequest(Socket client) {
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String line = reader.readLine();
                if (line != null && line.contains("/ping")) {
                    int contentLength = 0;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        }
                    }

                    StringBuilder body = new StringBuilder();
                    if (contentLength > 0) {
                        char[] buf = new char[contentLength];
                        reader.read(buf, 0, contentLength);
                        body.append(buf);
                    }

                    lastHeartbeatTime = System.currentTimeMillis();

                    if (body.length() > 0) {
                        try {
                            JSONObject json = new JSONObject(body.toString());
                            if (json.has("placeId") && targetPlaceId.isEmpty()) {
                                targetPlaceId = json.getString("placeId");
                            }
                            if (mSocket != null && mSocket.connected()) {
                                mSocket.emit("agent:heartbeat", json);
                            }
                        } catch (Exception ignored) {}
                    }

                    // Respond OK
                    OutputStream out = client.getOutputStream();
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\n\r\n{\"status\":\"ok\"}";
                    out.write(response.getBytes());
                    out.flush();
                }
                client.close();
            } catch (Exception ignored) {}
        }).start();
    }

    private void stopLocalHttpServer() {
        try {
            if (mHttpServer != null) mHttpServer.close();
        } catch (Exception ignored) {}
    }

    // --- Watchdog Loop ---
    private void startWatchdog() {
        mWatchdogThread = new Thread(() -> {
            while (isRunning) {
                try {
                    Thread.sleep(2000);
                    long now = System.currentTimeMillis();

                    if (!isActive) continue;
                    if (now < gracePeriodUntil) continue;

                    long elapsed = now - lastHeartbeatTime;
                    if (lastHeartbeatTime > 0 && elapsed > 35000) {
                        Log.w(TAG, "Heartbeat lost for " + (elapsed / 1000) + "s! Triggering Auto-Rejoin...");
                        performRejoin(targetPlaceId);
                    }
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        });
        mWatchdogThread.start();
    }

    // --- Auto-Rejoin Execution via Root (su) ---
    private void performRejoin(String placeId) {
        if (placeId == null || placeId.isEmpty()) return;

        sendStatusBroadcast("● กำลัง Rejoin เข้าแมพ...");
        if (mSocket != null && mSocket.connected()) {
            try {
                JSONObject statusObj = new JSONObject();
                statusObj.put("status", "rejoining");
                mSocket.emit("agent:status", statusObj);
            } catch (Exception ignored) {}
        }

        // 1. Force stop old app
        runRootCommand("am force-stop com.roblox.client");
        try { Thread.sleep(2000); } catch (Exception ignored) {}

        // 2. Launch Roblox with Place ID intent
        runRootCommand("am start -a android.intent.action.VIEW -d \"roblox://experiences/start?placeId=" + placeId + "\"");

        // 3. Grace period (60 seconds)
        gracePeriodUntil = System.currentTimeMillis() + 60000;
        rejoinCount++;

        if (mSocket != null && mSocket.connected()) {
            try {
                JSONObject repObj = new JSONObject();
                repObj.put("count", rejoinCount);
                mSocket.emit("agent:rejoined", repObj);
            } catch (Exception ignored) {}
        }

        sendStatusBroadcast("● Rejoin สำเร็จ (รอเกมโหลด)");
    }

    private void captureAndSendSnapshot() {
        new Thread(() -> {
            try {
                String path = "/sdcard/rejoin_snap.png";
                runRootCommand("screencap -p " + path);
                File file = new File(path);
                if (file.exists() && file.length() > 0) {
                    byte[] bytes = new byte[(int) file.length()];
                    FileInputStream fis = new FileInputStream(file);
                    fis.read(bytes);
                    fis.close();

                    String b64 = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
                    if (mSocket != null && mSocket.connected()) {
                        JSONObject snapObj = new JSONObject();
                        snapObj.put("imageBase64", b64);
                        mSocket.emit("agent:snapshot", snapObj);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Screenshot failed", e);
            }
        }).start();
    }

    private void runRootCommand(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            process.waitFor();
        } catch (Exception e) {
            try {
                Runtime.getRuntime().exec(cmd);
            } catch (Exception ignored) {}
        }
    }

    private void sendStatusBroadcast(String status) {
        Intent intent = new Intent("com.rejoin.cloud.STATUS_UPDATE");
        intent.putExtra("status", status);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Rejoin Cloud Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Rejoin Cloud Service")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_rotate)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}

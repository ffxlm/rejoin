package com.rejoin.cloud;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private EditText etServerUrl;
    private EditText etLicenseKey;
    private TextView tvStatus;
    private Button btnToggleService;
    private SharedPreferences prefs;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            if (status != null) {
                updateStatusUI(status);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("RejoinPrefs", MODE_PRIVATE);

        etServerUrl = findViewById(R.id.etServerUrl);
        etLicenseKey = findViewById(R.id.etLicenseKey);
        tvStatus = findViewById(R.id.tvStatus);
        btnToggleService = findViewById(R.id.btnToggleService);

        // Load saved values
        etServerUrl.setText(prefs.getString("server_url", "http://192.168.1.100:3000"));
        etLicenseKey.setText(prefs.getString("license_key", ""));

        updateButtonState();

        btnToggleService.setOnClickListener(v -> {
            if (RejoinService.isRunning) {
                stopRejoinService();
            } else {
                startRejoinService();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, new IntentFilter("com.rejoin.cloud.STATUS_UPDATE"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, new IntentFilter("com.rejoin.cloud.STATUS_UPDATE"));
        }
        updateButtonState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {}
    }

    private void startRejoinService() {
        String serverUrl = etServerUrl.getText().toString().trim();
        String licenseKey = etLicenseKey.getText().toString().trim();

        if (licenseKey.isEmpty()) {
            Toast.makeText(this, "กรุณากรอก License Key", Toast.LENGTH_SHORT).show();
            return;
        }

        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "กรุณากรอก Server URL", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save preferences
        prefs.edit()
                .putString("server_url", serverUrl)
                .putString("license_key", licenseKey)
                .apply();

        // ⚡ ขอสิทธิ์ Root ทันทีตั้งแต่เริ่ม เพื่อให้ป๊อปอัปเด้งถามผู้ใช้ตั้งแต่ตอนนี้
        updateStatusUI("● กำลังขอสิทธิ์ Root...");
        new Thread(() -> {
            boolean hasRoot = checkAndRequestRoot();
            runOnUiThread(() -> {
                if (hasRoot) {
                    Toast.makeText(MainActivity.this, "ได้รับสิทธิ์ Root เรียบร้อย", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "คำเตือน: ไม่พบสิทธิ์ Root", Toast.LENGTH_LONG).show();
                }

                Intent serviceIntent = new Intent(MainActivity.this, RejoinService.class);
                serviceIntent.putExtra("server_url", serverUrl);
                serviceIntent.putExtra("license_key", licenseKey);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }

                updateStatusUI("กำลังเริ่มการทำงาน...");
                updateButtonState();
            });
        }).start();
    }

    private boolean checkAndRequestRoot() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void stopRejoinService() {
        Intent serviceIntent = new Intent(this, RejoinService.class);
        stopService(serviceIntent);
        updateStatusUI("● หยุดทำงานแล้ว (Stopped)");
        updateButtonState();
    }

    private void updateStatusUI(String status) {
        tvStatus.setText(status);
        if (status.contains("เชื่อมต่อสำเร็จ") || status.contains("Connected") || status.contains("ฟาร์ม")) {
            tvStatus.setTextColor(Color.parseColor("#10B981")); // Emerald
        } else if (status.contains("Rejoin")) {
            tvStatus.setTextColor(Color.parseColor("#F59E0B")); // Amber
        } else {
            tvStatus.setTextColor(Color.parseColor("#A1A1AA")); // Muted
        }
    }

    private void updateButtonState() {
        if (RejoinService.isRunning) {
            btnToggleService.setText("หยุดการทำงาน (Stop Service)");
            btnToggleService.setBackgroundColor(Color.parseColor("#EF4444")); // Red
        } else {
            btnToggleService.setText("เริ่มการทำงาน (Start Service)");
            btnToggleService.setBackgroundColor(Color.parseColor("#4F46E5")); // Indigo
        }
    }
}

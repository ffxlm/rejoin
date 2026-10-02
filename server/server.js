const express = require('express');
const http = require('node:http');
const path = require('node:path');
const cors = require('cors');
const { Server } = require('socket.io');
const db = require('./db');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: '*' },
  maxHttpBufferSize: 5e6 // 5MB buffer for snapshots
});

const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// Mapping active socket connections for devices
// deviceId -> socket
const deviceSockets = new Map();

// --- REST APIs ---

// 1. Generate 3-Day Trial Key
app.post('/api/trial', (req, res) => {
  try {
    const trial = db.createTrialKey(3);
    res.json({ success: true, ...trial });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

// 2. Validate Key & Get Device List
app.post('/api/check-key', (req, res) => {
  const { key } = req.body;
  if (!key) return res.status(400).json({ success: false, error: 'กรุณากรอกคีย์' });

  const validation = db.isKeyValid(key.trim());
  if (!validation.valid) {
    return res.json({ success: false, reason: validation.reason });
  }

  const devices = db.getDevicesByKey(key.trim());
  res.json({
    success: true,
    license: validation.license,
    devices
  });
});

// 3. Web Control Command (Start, Stop, Rejoin, Snapshot)
app.post('/api/control', (req, res) => {
  const { key, deviceId, action, placeId } = req.body;
  if (!key || !deviceId || !action) {
    return res.status(400).json({ success: false, error: 'ข้อมูลไม่ครบถ้วน' });
  }

  const validation = db.isKeyValid(key.trim());
  if (!validation.valid) {
    return res.status(403).json({ success: false, error: validation.reason });
  }

  const targetSocket = deviceSockets.get(deviceId);
  if (!targetSocket) {
    return res.status(404).json({ success: false, error: 'เครื่องนี้ออฟไลน์อยู่' });
  }

  if (action === 'start') {
    db.setDeviceActive(deviceId, true, placeId);
    targetSocket.emit('command:start', { placeId: placeId || '' });
    io.to(`key_${key}`).emit('device:updated', { deviceId, status: 'starting', isActive: 1, placeId });
  } else if (action === 'stop') {
    db.setDeviceActive(deviceId, false);
    targetSocket.emit('command:stop');
    io.to(`key_${key}`).emit('device:updated', { deviceId, status: 'idle', isActive: 0 });
  } else if (action === 'rejoin') {
    targetSocket.emit('command:rejoin', { placeId: placeId || '' });
  } else if (action === 'snapshot') {
    targetSocket.emit('command:request_snapshot');
  }

  res.json({ success: true, message: `ส่งคำสั่ง ${action} สำเร็จ` });
});

// --- WebSocket Handling ---

io.on('connection', (socket) => {
  const role = socket.handshake.query.role; // 'web' or 'agent'
  const key = socket.handshake.query.key;
  const deviceId = socket.handshake.query.deviceId;
  const deviceName = socket.handshake.query.deviceName || 'Redfinger Device';

  if (!key) {
    socket.disconnect();
    return;
  }

  const cleanKey = key.trim();
  const validation = db.isKeyValid(cleanKey);

  // If key is invalid, reject connection
  if (!validation.valid) {
    socket.emit('auth:error', { reason: validation.reason });
    socket.disconnect();
    return;
  }

  // Join license key room for real-time updates
  socket.join(`key_${cleanKey}`);

  // Handling Web Dashboard Client
  if (role === 'web') {
    socket.emit('auth:success', { license: validation.license });
    return;
  }

  // Handling Android Device Agent
  if (role === 'agent') {
    if (!deviceId) {
      socket.disconnect();
      return;
    }

    // Register or update device
    db.registerDevice(deviceId, cleanKey, deviceName);
    deviceSockets.set(deviceId, socket);

    // Notify web clients that a new device is online
    const dev = db.getDevice(deviceId);
    io.to(`key_${cleanKey}`).emit('device:connected', dev);

    // 1. Agent reports in-game heartbeat info (from Roblox script)
    socket.on('agent:heartbeat', (data) => {
      db.updateDeviceHeartbeat(deviceId, data);
      const updated = db.getDevice(deviceId);
      io.to(`key_${cleanKey}`).emit('device:updated', updated);
    });

    // 2. Agent reports status change (e.g. 'farming', 'rejoining', 'idle')
    socket.on('agent:status', ({ status }) => {
      db.updateDeviceStatus(deviceId, status);
      io.to(`key_${cleanKey}`).emit('device:status', { deviceId, status });
    });

    // 3. Agent reports successful auto-rejoin count
    socket.on('agent:rejoined', () => {
      db.incrementRejoinCount(deviceId);
      const updated = db.getDevice(deviceId);
      io.to(`key_${cleanKey}`).emit('device:updated', updated);
    });

    // 4. Agent delivers screenshot
    socket.on('agent:snapshot', ({ imageBase64 }) => {
      db.updateDeviceSnapshot(deviceId, imageBase64);
      io.to(`key_${cleanKey}`).emit('device:snapshot', { deviceId, image: imageBase64 });
    });

    // On Agent Disconnect
    socket.on('disconnect', () => {
      deviceSockets.delete(deviceId);
      db.updateDeviceStatus(deviceId, 'offline');
      io.to(`key_${cleanKey}`).emit('device:status', { deviceId, status: 'offline' });
    });
  }
});

server.listen(PORT, () => {
  console.log(`===============================================`);
  console.log(`🚀 Roblox Auto-Rejoin Server running on port ${PORT}`);
  console.log(`🌐 Web Dashboard: http://localhost:${PORT}`);
  console.log(`💾 Database: SQLite (rejoin.db)`);
  console.log(`===============================================`);
});

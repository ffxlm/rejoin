const { DatabaseSync } = require('node:sqlite');
const path = require('node:path');
const crypto = require('node:crypto');

const dbPath = path.join(__dirname, 'rejoin.db');
const db = new DatabaseSync(dbPath);

// Initialize schema
db.exec(`
  CREATE TABLE IF NOT EXISTS licenses (
    key TEXT PRIMARY KEY,
    created_at INTEGER,
    expires_at INTEGER,
    is_trial INTEGER DEFAULT 0,
    note TEXT
  );

  CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    license_key TEXT,
    device_name TEXT,
    status TEXT DEFAULT 'offline',
    place_id TEXT DEFAULT '',
    roblox_username TEXT DEFAULT '',
    roblox_display_name TEXT DEFAULT '',
    roblox_user_id TEXT DEFAULT '',
    rejoin_count INTEGER DEFAULT 0,
    last_ping INTEGER DEFAULT 0,
    last_snapshot TEXT DEFAULT '',
    is_active INTEGER DEFAULT 0,
    FOREIGN KEY(license_key) REFERENCES licenses(key)
  );
`);

function generateKey(prefix = 'RJ') {
  const rand = crypto.randomBytes(4).toString('hex').toUpperCase();
  const rand2 = crypto.randomBytes(4).toString('hex').toUpperCase();
  return `${prefix}-${rand}-${rand2}`;
}

// Create a trial key valid for N days (default: 3 days)
function createTrialKey(days = 3) {
  const key = generateKey('RJ-TRIAL');
  const now = Date.now();
  const expiresAt = now + (days * 24 * 60 * 60 * 1000);

  const stmt = db.prepare(`
    INSERT INTO licenses (key, created_at, expires_at, is_trial, note)
    VALUES (?, ?, ?, 1, 'Free 3-Day Trial')
  `);
  stmt.run(key, now, expiresAt);

  return { key, created_at: now, expires_at: expiresAt, is_trial: 1 };
}

// Get license info
function getLicense(key) {
  const stmt = db.prepare('SELECT * FROM licenses WHERE key = ?');
  return stmt.get(key);
}

// Validate license
function isKeyValid(key) {
  const lic = getLicense(key);
  if (!lic) return { valid: false, reason: 'ไม่พบคีย์นี้ในระบบ' };
  if (Date.now() > lic.expires_at) return { valid: false, reason: 'คีย์นี้หมดอายุการใช้งานแล้ว' };
  return { valid: true, license: lic };
}

// Register or reconnect a device under a license key
function registerDevice(deviceId, key, deviceName = 'Redfinger Device') {
  const stmt = db.prepare(`
    INSERT INTO devices (device_id, license_key, device_name, status, last_ping, is_active)
    VALUES (?, ?, ?, 'online', ?, 0)
    ON CONFLICT(device_id) DO UPDATE SET
      license_key = excluded.license_key,
      device_name = excluded.device_name,
      status = 'online',
      last_ping = excluded.last_ping
  `);
  stmt.run(deviceId, key, deviceName, Date.now());
}

// Update device heartbeat info coming from in-game script
function updateDeviceHeartbeat(deviceId, info) {
  const stmt = db.prepare(`
    UPDATE devices SET
      roblox_username = ?,
      roblox_display_name = ?,
      roblox_user_id = ?,
      place_id = CASE WHEN ? != '' THEN ? ELSE place_id END,
      status = 'farming',
      last_ping = ?
    WHERE device_id = ?
  `);
  stmt.run(
    info.username || '',
    info.displayName || '',
    String(info.userId || ''),
    info.placeId || '',
    info.placeId || '',
    Date.now(),
    deviceId
  );
}

// Update status (e.g. 'rejoining', 'farming', 'idle', 'offline')
function updateDeviceStatus(deviceId, status) {
  const stmt = db.prepare('UPDATE devices SET status = ? WHERE device_id = ?');
  stmt.run(status, deviceId);
}

// Increment rejoin counter
function incrementRejoinCount(deviceId) {
  const stmt = db.prepare('UPDATE devices SET rejoin_count = rejoin_count + 1 WHERE device_id = ?');
  stmt.run(deviceId);
}

// Update device active state and target place_id
function setDeviceActive(deviceId, isActive, placeId = null) {
  if (placeId !== null) {
    const stmt = db.prepare('UPDATE devices SET is_active = ?, place_id = ? WHERE device_id = ?');
    stmt.run(isActive ? 1 : 0, String(placeId), deviceId);
  } else {
    const stmt = db.prepare('UPDATE devices SET is_active = ? WHERE device_id = ?');
    stmt.run(isActive ? 1 : 0, deviceId);
  }
}

// Update latest screenshot
function updateDeviceSnapshot(deviceId, base64Image) {
  const stmt = db.prepare('UPDATE devices SET last_snapshot = ? WHERE device_id = ?');
  stmt.run(base64Image, deviceId);
}

// Get all devices associated with a license key
function getDevicesByKey(key) {
  const stmt = db.prepare('SELECT * FROM devices WHERE license_key = ? ORDER BY device_id ASC');
  return stmt.all(key);
}

// Get specific device
function getDevice(deviceId) {
  const stmt = db.prepare('SELECT * FROM devices WHERE device_id = ?');
  return stmt.get(deviceId);
}

module.exports = {
  createTrialKey,
  getLicense,
  isKeyValid,
  registerDevice,
  updateDeviceHeartbeat,
  updateDeviceStatus,
  incrementRejoinCount,
  setDeviceActive,
  updateDeviceSnapshot,
  getDevicesByKey,
  getDevice
};

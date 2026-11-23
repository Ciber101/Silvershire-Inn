/**
 * SILVERSHIRE INN SERVER
 * Self-hosted messaging server: relays and stores end-to-end encrypted messages, files and
 * calls for the Silvershire Inn Android app and web app. Messages are never readable here.
 *
 * Copyright (C) 2026 CiberAI. Licensed under the GNU GPL v3 or later (see LICENSE).
 *
 * Start: npm install, then node server.js (or start-server.bat / start-server.sh).
 * First start: the server and admin passwords are generated, printed and saved in
 * data/FIRST-RUN-PASSWORDS.txt. Change them on http://localhost:8081/admin/vault.
 * Guides: docs/SETUP.md
 */
const http      = require('http');
const WebSocket = require('ws');
const fs        = require('fs');
const path      = require('path');
const crypto    = require('crypto');
// Never let one unexpected error take the whole server down for everyone: log and keep going.
process.on('uncaughtException', (e) => { try { console.error('[UNCAUGHT]', e && e.stack || e); } catch (_) {} });
process.on('unhandledRejection', (e) => { try { console.error('[UNHANDLED]', e && e.stack || e); } catch (_) {} });

// ============================================================
//  CONFIG -------- edit this section only
// ============================================================
// Passwords: data/config.json can override these without editing this file, e.g.
//   { "serverPassword": "...", "adminPassword": "..." }
// The admin password should be DIFFERENT from the app password everyone uses: it guards
// publishing update notices, building the branded APK and the admin panel.
const _cfg = (() => { try { return JSON.parse(fs.readFileSync(path.join(__dirname, 'data', 'config.json'), 'utf8')); } catch (_) { return {}; } })();
// Passwords are never kept readable: a plain "serverPassword" / "adminPassword" typed into
// data/config.json is swapped for a one-way scrambled form ("...Hash") the next time the
// server starts. To change one, type the new one in plain again and restart.
const _cfgPath = path.join(__dirname, 'data', 'config.json');
function pwHash(pw) {
  const salt = crypto.randomBytes(16);
  return 'scrypt$' + salt.toString('base64') + '$' + crypto.scryptSync(String(pw), salt, 32).toString('base64');
}
const _pwOkCache = new Map();
function pwOk(pw, stored) {
  pw = String(pw == null ? '' : pw);
  const key = stored + '|' + crypto.createHash('sha256').update(pw).digest('base64');
  if (_pwOkCache.has(key)) return _pwOkCache.get(key);
  let ok = false;
  try {
    const [kind, s, h] = String(stored).split('$');
    if (kind === 'scrypt') {
      const want = Buffer.from(h, 'base64');
      const got = crypto.scryptSync(pw, Buffer.from(s, 'base64'), want.length);
      ok = crypto.timingSafeEqual(got, want);
    }
  } catch (_) {}
  if (_pwOkCache.size > 64) _pwOkCache.clear();
  _pwOkCache.set(key, ok);
  return ok;
}
// The server and admin passwords (and the panic-lock accounts) live in data/secrets.json,
// encrypted with the vault key. They can only be changed on the Vault page, with the current
// password. Typing passwords into config.json does nothing once they've moved (on first start).
let secrets = null;
// First start: random server and admin passwords, shown once in the window and kept in
// data/FIRST-RUN-PASSWORDS.txt until changed on the Vault page.
let _firstRun = null;
function firstRunPasswords() {
  if (_firstRun) return _firstRun;
  const make = () => crypto.randomBytes(12).toString('base64').replace(/[^A-Za-z0-9]/g, '').slice(0, 12);
  _firstRun = { server: make(), admin: make() };
  const text = [
    'Silvershire Inn - first start passwords',
    '',
    'Server password (people need it to create an account): ' + _firstRun.server,
    'Admin password (admin panel, publishing updates):       ' + _firstRun.admin,
    '',
    'Change both on http://localhost:8081/admin/vault (on this computer), then delete this file.',
    ''
  ].join('\n');
  try { fs.mkdirSync(path.join(__dirname, 'data'), { recursive: true }); fs.writeFileSync(path.join(__dirname, 'data', 'FIRST-RUN-PASSWORDS.txt'), text); } catch (_) {}
  console.log('\n==================== FIRST START ====================\n' + text + '=====================================================\n');
  return _firstRun;
}
function writeConfig() { try { fs.writeFileSync(_cfgPath, JSON.stringify(_cfg, null, 2)); } catch (e) { console.error('[CONFIG] could not update config.json:', e.message); } }
function loadSecrets() {
  if (AtRest.locked()) { secrets = null; return; }
  const file = path.join(DATA_DIR, 'secrets.json');
  const had = fs.existsSync(file);
  secrets = had ? loadJSON(file, null) : null;
  const cfgPw = ['serverPassword', 'adminPassword', 'serverPasswordHash', 'adminPasswordHash'].filter(k => k in _cfg);
  if (!secrets) {
    if (had) { console.error('[VAULT] secrets.json could not be opened'); return; }
    // First start with this version: take over the passwords from config.json.
    secrets = {
      serverPasswordHash: _cfg.serverPasswordHash || pwHash(_cfg.serverPassword || firstRunPasswords().server),
      adminPasswordHash:  _cfg.adminPasswordHash  || pwHash(_cfg.adminPassword  || firstRunPasswords().admin),
      panicUsers: AtRest.panicUsers()
    };
    saveJSON(file, secrets);
    try { AtRest.setPanicUsers([]); } catch (_) {}   // no longer kept in the readable keyring
    console.log('[VAULT] Passwords moved into the encrypted vault.');
  } else if (cfgPw.length) {
    console.warn('[VAULT] Passwords found in config.json were IGNORED. Change passwords on the Vault page (needs the current one).');
  }
  if (cfgPw.length) {
    cfgPw.forEach(k => delete _cfg[k]);
    _cfg._help = 'Passwords are kept encrypted in the vault. Change them on the admin Vault page (http://localhost:8081/admin/vault); the current password is needed. Passwords typed here are ignored.';
    writeConfig();
  }
}
function saveSecrets() { if (secrets) saveJSON(path.join(DATA_DIR, 'secrets.json'), secrets); }
const checkServerPassword = (p) => !!secrets && pwOk(p, secrets.serverPasswordHash);
const checkAdminPassword  = (p) => !!secrets && pwOk(p, secrets.adminPasswordHash);
const panicLockUsers = () => (secrets && Array.isArray(secrets.panicUsers)) ? secrets.panicUsers : [];
const PORT             = 8080;
const ADMIN_PORT       = 8081;                       // admin GUI runs on this port
const MAX_FAILED_AUTHS = 10;
const BAN_DURATION_MS  = 24 * 60 * 60 * 1000;       // 24 hours
const MAX_MESSAGE_HISTORY = 200;
const DATA_DIR         = path.join(__dirname, 'data');
// No server-side upload size limit -------- client enforces a 5-minute timeout instead
// ============================================================

// Data directories
const USERS_FILE   = path.join(DATA_DIR, 'users.json');
const FRIENDS_FILE = path.join(DATA_DIR, 'friends.json');
const PENDING_FILE = path.join(DATA_DIR, 'pending.json');
const GROUPS_FILE  = path.join(DATA_DIR, 'groups.json');
const BANNED_FILE  = path.join(DATA_DIR, 'banned.json');
const WIPED_FILE   = path.join(DATA_DIR, 'wiped_accounts.json');
const HISTORY_DIR      = path.join(DATA_DIR, 'history');
const RECEIPTS_DIR     = path.join(DATA_DIR, 'receipts');
const UPLOADS_DIR      = path.join(DATA_DIR, 'uploads');
const PROFILE_PICS_DIR = path.join(DATA_DIR, 'profile_pics');
const BANNER_PICS_DIR  = path.join(DATA_DIR, 'banner_pics');
const DOWNLOAD_DIR    = path.join(DATA_DIR, 'download');
const BRANDING_DIR    = path.join(DATA_DIR, 'branding');
const APP_VERSION     = {
  // Raise versionCode / versionName together with android/app/build.gradle.kts for every update
  // you publish; notes = what people see in the "Silvershire Updates" chat.
  versionCode: 12,
  versionName: '1.0.11',
  notes: '- Borders: dragons curve smoothly around square and diamond pictures, wings stay on the body\n- Web: pictures with borders shrink to fit their space, profile sheet opens at its final size, picture and shape stay in sync with the phone'
};
const UPDATES_USER    = 'Silvershire Updates';

// Ensure directories exist
[DATA_DIR, HISTORY_DIR, RECEIPTS_DIR, UPLOADS_DIR, PROFILE_PICS_DIR, BANNER_PICS_DIR, DOWNLOAD_DIR, BRANDING_DIR].forEach(d => {
  if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true });
});

// Everything kept on this PC is encrypted (see atrest.js). Unlocks by itself with this Windows
// account; with a master password set, the server waits (locked) until it's entered.
const AtRest = require('./atrest.js')(DATA_DIR);
AtRest.setProbe(USERS_FILE);
const VAULT_START = AtRest.start();

// ============================================================
//  DATA HELPERS
// ============================================================

const PROFILE_MEDIA_EXTS = ['jpg', 'jpeg', 'png', 'webp', 'gif', 'mp4', 'webm'];
const MIME_TO_EXT = {
  'image/jpeg': 'jpg', 'image/jpg': 'jpg', 'image/png': 'png', 'image/webp': 'webp',
  'image/gif': 'gif', 'video/mp4': 'mp4', 'video/webm': 'webm'
};
const EXT_TO_MIME = {
  jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp',
  gif: 'image/gif', mp4: 'video/mp4', webm: 'video/webm'
};
const AVATAR_SHAPES = new Set(['circle', 'rounded', 'squircle', 'diamond']);

function normalizeAvatarShape(v) {
  const s = String(v || '').trim().toLowerCase();
  return AVATAR_SHAPES.has(s) ? s : null;
}

const PRESENCE_MODES = new Set(['online', 'afk', 'dnd', 'busy', 'brb', 'invisible']);

function normalizePresenceMode(v) {
  if (v == null || v === '') return null;
  let s = String(v).trim().toLowerCase();
  if (s === 'away') s = 'afk'; // legacy: Away folded into AFK
  if (s === 'do not disturb' || s === 'do_not_disturb') s = 'dnd';
  return PRESENCE_MODES.has(s) ? s : null;
}

/**
 * Build online_status for friends.
 * Invisible appears offline. Unset presence defaults to online display (no presence field forced).
 */
/** Stored presence only when the user has set it; never invent a default key. */
function storedPresenceMode(username) {
  const raw = users[username]?.settings?.presenceMode;
  if (raw == null || raw === '') return null;
  return normalizePresenceMode(raw);
}

function buildOnlineStatusMsg(username, isConnected) {
  const m = buildOnlineStatusMsgRaw(username, isConnected);
  if (m.lastSeen == null) delete m.lastSeen;   // unknown: omit rather than send null
  return m;
}
function buildOnlineStatusMsgRaw(username, isConnected) {
  const mode = storedPresenceMode(username);
  if (!isConnected) {
    return {
      type: 'online_status',
      name: username,
      online: false,
      lastSeen: lastSeenMap.get(username) || users[username]?.lastSeen || null
    };
  }
  if (mode === 'invisible') {
    return {
      type: 'online_status',
      name: username,
      online: false,
      lastSeen: lastSeenMap.get(username) || users[username]?.lastSeen || null
    };
  }
  const msg = { type: 'online_status', name: username, online: true };
  if (mode && mode !== 'online') {
    msg.presence = mode;
    msg.mode = mode;
  } else if (mode === 'online') {
    msg.presence = 'online';
    msg.mode = 'online';
  }
  // If unset: online:true with no presence field (clients default to Online)
  return msg;
}

function persistPresenceMode(username, mode) {
  if (!users[username]) return null;
  if (!users[username].settings || typeof users[username].settings !== 'object') {
    users[username].settings = {};
  }
  const pm = normalizePresenceMode(mode);
  if (!pm) return storedPresenceMode(username);
  // Explicit user choice: store (including online after they picked it)
  users[username].settings.presenceMode = pm;
  saveJSON(USERS_FILE, users);
  return pm;
}

function sniffMediaMime(buf) {
  if (!buf || buf.length < 12) return null;
  if (buf[0] === 0xFF && buf[1] === 0xD8 && buf[2] === 0xFF) return 'image/jpeg';
  if (buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4E && buf[3] === 0x47) return 'image/png';
  if (buf[0] === 0x47 && buf[1] === 0x49 && buf[2] === 0x46) return 'image/gif';
  if (buf[0] === 0x52 && buf[1] === 0x49 && buf[2] === 0x46 && buf[3] === 0x46 &&
      buf[8] === 0x57 && buf[9] === 0x45 && buf[10] === 0x42 && buf[11] === 0x50) return 'image/webp';
  if (buf[4] === 0x66 && buf[5] === 0x74 && buf[6] === 0x79 && buf[7] === 0x70) return 'video/mp4';
  if (buf[0] === 0x1A && buf[1] === 0x45 && buf[2] === 0xDF && buf[3] === 0xA3) return 'video/webm';
  return null;
}

function resolveMediaMime(headerMime, buf) {
  const h = String(headerMime || '').split(';')[0].trim().toLowerCase();
  if (MIME_TO_EXT[h]) return h;
  const sniffed = sniffMediaMime(buf);
  if (sniffed) return sniffed;
  return null;
}

function findUserMediaFile(dir, username) {
  try {
    if (!fs.existsSync(dir)) return null;
    for (const ext of PROFILE_MEDIA_EXTS) {
      const fp = path.join(dir, username + '.' + ext);
      if (fs.existsSync(fp)) {
        const mime = EXT_TO_MIME[ext] || 'application/octet-stream';
        return { path: fp, ext, mime };
      }
    }
  } catch (_) {}
  return null;
}

function hasUserMedia(dir, username) {
  return !!findUserMediaFile(dir, username);
}
/** Changes whenever the person's picture or banner changes (file times), so browsers that
 *  were closed during the change don't keep showing the old one from their cache. */
function mediaVersion(username) {
  let v = 0;
  for (const dir of [PROFILE_PICS_DIR, BANNER_PICS_DIR]) {
    const f = findUserMediaFile(dir, username);
    if (f) try { v = Math.max(v, Math.floor(fs.statSync(f.path).mtimeMs)); } catch (_) {}
  }
  return v;
}

function removeUserMediaSiblings(dir, username, keepExt) {
  for (const ext of PROFILE_MEDIA_EXTS) {
    if (keepExt && ext === keepExt) continue;
    if (keepExt === 'jpg' && ext === 'jpeg') continue;
    if (keepExt === 'jpeg' && ext === 'jpg') continue;
    const fp = path.join(dir, username + '.' + ext);
    try { if (fs.existsSync(fp)) fs.unlinkSync(fp); } catch (_) {}
  }
}

function renameUserMedia(dir, oldName, newName) {
  const found = findUserMediaFile(dir, oldName);
  if (!found) return;
  const dest = path.join(dir, newName + '.' + found.ext);
  if (found.path === dest) return;
  try {
    removeUserMediaSiblings(dir, newName, null);
    fs.renameSync(found.path, dest);
    removeUserMediaSiblings(dir, oldName, null);
  } catch (e) {
    console.error('[RENAME MEDIA]', e.message);
  }
}

function deleteUserMedia(dir, username) {
  removeUserMediaSiblings(dir, username, null);
}

// Profile frames ("gen:<kind>:<seed>", drawn identically on every device from frames.js).
// Kept outside users[u].settings because the app replaces its whole settings object on sync.
const FRAME_ID_RE = /^gen:(random|dragon|weapons|basic|stone|iron|silver|gold|bronze|winged|spiked|none):\d{1,10}$/;
function normalizeFrameId(v) {
  const s = String(v || '').trim();
  return FRAME_ID_RE.test(s) ? s : null;
}
function userFrame(u) { return normalizeFrameId(users[u]?.profileFrame); }
function userSavedFrames(u) {
  const a = Array.isArray(users[u]?.savedFrames) ? users[u].savedFrames : [];
  return a.map(normalizeFrameId).filter(Boolean).slice(0, 60);
}

// ── Public address (for the share / invite link) ──
// Set "publicUrl" in data/config.json to force it (e.g. "https://chat.example.org"); otherwise
// the server learns it from the address people actually reach it on (the Host header, also
// through a reverse proxy like Caddy), but only from connections that signed in successfully, so
// a stranger can't plant a fake address. Private / local addresses are never used.
const PUBLIC_ORIGIN_FILE = path.join(__dirname, 'data', 'public-origin.json');
let learnedOrigin = (() => { try { return JSON.parse(fs.readFileSync(PUBLIC_ORIGIN_FILE, 'utf8')).origin || null; } catch (_) { return null; } })();
function isPrivateHost(h) {
  h = String(h || '').toLowerCase().replace(/^\[|\]$/g, '');
  if (!h || h === 'localhost' || h.endsWith('.local') || h.endsWith('.lan') || h === '::1') return true;
  const m = /^(\d+)\.(\d+)\.\d+\.\d+$/.exec(h);
  if (m) { const a = +m[1], b = +m[2]; return a === 10 || a === 127 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31) || (a === 169 && b === 254) || a === 0; }
  return h.startsWith('fc') || h.startsWith('fd') || h.startsWith('fe80');
}
function learnPublicOrigin(req) {
  try {
    const host = String(req.headers['x-forwarded-host'] || req.headers.host || '').split(',')[0].trim();
    if (!host || !/^[a-z0-9.\-:\[\]]+$/i.test(host)) return;
    const name = host.startsWith('[') ? host.slice(0, host.indexOf(']') + 1) : host.split(':')[0];
    if (isPrivateHost(name)) return;
    const proto = String(req.headers['x-forwarded-proto'] || (req.socket && req.socket.encrypted ? 'https' : 'http')).split(',')[0].trim().toLowerCase() === 'https' ? 'https' : 'http';
    const origin = proto + '://' + host.toLowerCase();
    if (origin === learnedOrigin) return;
    learnedOrigin = origin;
    fs.writeFileSync(PUBLIC_ORIGIN_FILE, JSON.stringify({ origin, learned: new Date().toISOString() }));
    console.log('[SHARE] Public address: ' + origin);
  } catch (_) {}
}
function publicOrigin() {
  const cfg = String(_cfg.publicUrl || '').trim().replace(/\/+$/, '');
  return /^https?:\/\//i.test(cfg) ? cfg : learnedOrigin;
}
/** Link to the download page friends can open on their phone, or null if not known yet. */
function shareLink() { const o = publicOrigin(); return o ? o + '/download' : null; }

// Every chat message must arrive end-to-end encrypted: 1:1 {v:1, enc, key} or group
// {v:2, enc, keys}. Anything else is refused, so nothing readable is ever stored or relayed,
// even from an old or modified client.
function isE2EPayload(p, group) {
  if (typeof p !== 'string' || p[0] !== '{') return false;
  try {
    const j = JSON.parse(p);
    if (!j || typeof j.enc !== 'string' || !j.enc) return false;
    if (group) return j.v === 2 && j.keys && typeof j.keys === 'object';
    return (j.v === 1 && typeof j.key === 'string' && !!j.key) || (j.v === 2 && j.keys && typeof j.keys === 'object');
  } catch (_) { return false; }
}

function userAvatarShape(u) {
  const sh = normalizeAvatarShape(users[u]?.settings?.avatarShape);
  return sh || 'circle';
}
function storedAvatarShape(u) {
  return normalizeAvatarShape(users[u]?.settings?.avatarShape);
}

function broadcastAvatarShape(username) {
  const shape = storedAvatarShape(username);
  if (!shape) return;
  // The person's other devices (phone <-> web) take the new shape too
  sendToUser(username, { type: 'profile_updated', name: username, avatarShape: shape });
  for (const friend of (friends[username] || [])) {
    if (areMutualFriends(username, friend)) {
      sendToUser(friend, { type: 'profile_updated', name: username, avatarShape: shape });
    }
  }
}

function serveMediaFile(req, res, filePath, mime) {
  const stat = fs.statSync(filePath);
  if (AtRest.isSealed(AtRest.fileHead(filePath))) {
    // Encrypted at rest (profile / banner media are small): decrypt in memory.
    let plain;
    try { plain = AtRest.readFile(filePath); } catch (e) { jsonResponse(res, 503, { error: 'locked' }); return; }
    return serveMediaBuffer(req, res, plain, mime, stat);
  }
  const fileSize = stat.size;
  // Validator so apps can skip re-downloading an unchanged picture (304) on every login.
  const etag = `"${fileSize.toString(16)}-${Math.floor(stat.mtimeMs).toString(16)}"`;
  if (req.headers['if-none-match'] === etag) {
    res.writeHead(304, { ETag: etag, 'Cache-Control': 'no-cache' });
    res.end();
    return;
  }
  const rangeHeader = req.headers['range'];
  const isVideo = String(mime || '').startsWith('video/');
  if (rangeHeader && isVideo) {
    const parts = rangeHeader.replace(/bytes=/, '').split('-');
    const start = parseInt(parts[0], 10);
    // Clamp a range past the end (per HTTP spec) instead of failing it.
    const end = parts[1] ? Math.min(parseInt(parts[1], 10), fileSize - 1) : fileSize - 1;
    if (isNaN(start) || start >= fileSize || end >= fileSize || start > end) {
      res.writeHead(416, { 'Content-Range': `bytes */${fileSize}` });
      res.end();
      return;
    }
    const chunkSize = end - start + 1;
    res.writeHead(206, {
      'Content-Range': `bytes ${start}-${end}/${fileSize}`,
      'Accept-Ranges': 'bytes',
      'Content-Length': chunkSize,
      'Content-Type': mime,
      'Cache-Control': 'no-cache',
      ETag: etag
    });
    fs.createReadStream(filePath, { start, end }).pipe(res);
    return;
  }
  res.writeHead(200, {
    'Content-Type': mime,
    'Content-Length': fileSize,
    'Accept-Ranges': isVideo ? 'bytes' : 'none',
    'Cache-Control': 'no-cache',
    ETag: etag
  });
  fs.createReadStream(filePath).pipe(res);
}

const _unreadable = new Set();   // encrypted files that failed to open: never written over

function serveMediaBuffer(req, res, buf, mime, stat) {
  const fileSize = buf.length;
  const etag = `"${fileSize.toString(16)}-${Math.floor(stat.mtimeMs).toString(16)}"`;
  if (req.headers['if-none-match'] === etag) { res.writeHead(304, { ETag: etag, 'Cache-Control': 'no-cache' }); res.end(); return; }
  const isVideo = String(mime || '').startsWith('video/');
  const rangeHeader = req.headers['range'];
  if (rangeHeader && isVideo) {
    const parts = rangeHeader.replace(/bytes=/, '').split('-');
    const start = parseInt(parts[0], 10);
    const end = parts[1] ? Math.min(parseInt(parts[1], 10), fileSize - 1) : fileSize - 1;
    if (isNaN(start) || start >= fileSize || start > end) { res.writeHead(416, { 'Content-Range': `bytes */${fileSize}` }); res.end(); return; }
    res.writeHead(206, { 'Content-Range': `bytes ${start}-${end}/${fileSize}`, 'Accept-Ranges': 'bytes',
      'Content-Length': end - start + 1, 'Content-Type': mime, 'Cache-Control': 'no-cache', ETag: etag });
    res.end(buf.subarray(start, end + 1));
    return;
  }
  res.writeHead(200, { 'Content-Type': mime, 'Content-Length': fileSize, 'Accept-Ranges': isVideo ? 'bytes' : 'none',
    'Cache-Control': 'no-cache', ETag: etag });
  res.end(buf);
}

function loadJSON(file, fallback = {}) {
  try {
    if (fs.existsSync(file)) {
      const raw = fs.readFileSync(file);
      if (AtRest.isSealed(raw) && AtRest.locked()) return fallback;   // locked: nothing readable
      try { return JSON.parse(AtRest.open(raw).toString('utf8')); }
      catch (e) { if (AtRest.isSealed(raw)) { _unreadable.add(file); console.error(`[VAULT] cannot open ${path.basename(file)} - it will not be overwritten`); } throw e; }
    }
  } catch (e) { console.error(`Error loading ${file}:`, e.message); }
  return fallback;
}


function collectBody(req, cb) {
  const chunks = [];
  req.on('data', c => chunks.push(c));
  req.on('end', () => cb(null, Buffer.concat(chunks)));
  req.on('error', cb);
}

function parseMultipart(buf, contentType) {
  const m = /boundary=([^;]+)/i.exec(contentType || '');
  const boundary = m ? m[1].trim() : '';
  const out = {};
  if (!boundary) return out;
  const parts = buf.toString('binary').split('--' + boundary);
  for (const part of parts) {
    const idx = part.indexOf('\r\n\r\n');
    if (idx < 0) continue;
    const head = part.slice(0, idx);
    let body = part.slice(idx + 4);
    if (body.endsWith('\r\n')) body = body.slice(0, -2);
    const nameM = /name="([^"]+)"/i.exec(head);
    if (!nameM) continue;
    const name = nameM[1];
    if (name === 'icon') out.icon = Buffer.from(body, 'binary');
    else out[name] = Buffer.from(body, 'binary').toString('utf8').trim();
  }
  return out;
}

function ensureUpdatesUser() {
  if (!users[UPDATES_USER]) {
    users[UPDATES_USER] = {
      publicKey: 'SYSTEM', system: true,
      registeredAt: new Date().toISOString(),
      signupName: UPDATES_USER, displayName: UPDATES_USER
    };
    saveJSON(USERS_FILE, users);
  }
}

/** What's new for the version being published: its section in UPDATE_NOTES.txt (next to this
 *  file), so every release says what changed. Falls back to APP_VERSION.notes. */
function versionNotes() {
  try {
    const txt = fs.readFileSync(path.join(__dirname, 'UPDATE_NOTES.txt'), 'utf8').replace(/\r/g, '');
    const lines = txt.split('\n');
    const start = lines.findIndex(l => l.trim() === '## ' + APP_VERSION.versionName);
    if (start >= 0) {
      const out = [];
      for (let i = start + 1; i < lines.length && !lines[i].startsWith('## '); i++) if (lines[i].trim() && !lines[i].startsWith('#')) out.push(lines[i].trimEnd());
      if (out.length) return out.join('\n');
    }
  } catch (_) {}
  return APP_VERSION.notes || '';
}

function publishUpdateNotice(customText) {
  ensureUpdatesUser();
  const defaultNotes = [
    'Android app update available (v' + APP_VERSION.versionName + ').',
    '',
    "What's new:",
    versionNotes() || '- Bug fixes and improvements',
    '',
    'Tap Download to install. /download/silvershire-inn.apk'
  ].join('\n');
  const payload = customText || defaultNotes;
  const midBase = crypto.randomBytes(8).toString('hex');
  const ts = new Date().toISOString();
  for (const u of Object.keys(users)) {
    if (u === UPDATES_USER) continue;
    // Blockable: if the user removed/blocked the Updates bot, skip DMing them.
    const removed = (users[u] && Array.isArray(users[u].removedContacts) && users[u].removedContacts.includes(UPDATES_USER));
    const blocked = (users[u] && Array.isArray(users[u].blockedUsers) && users[u].blockedUsers.includes(UPDATES_USER));
    if (removed || blocked) continue;
    if (!friends[u]) friends[u] = [];
    if (!friends[u].includes(UPDATES_USER)) friends[u].push(UPDATES_USER);
    if (!friends[UPDATES_USER]) friends[UPDATES_USER] = [];
    if (!friends[UPDATES_USER].includes(u)) friends[UPDATES_USER].push(u);
    const hist = loadHistory(UPDATES_USER, u);
    const messageId = midBase + '_' + u;
    hist.push({ from: UPDATES_USER, to: u, payload, messageId, timestamp: ts });
    saveHistory(UPDATES_USER, u, hist);
    sendToUser(u, { type: 'private_message', from: UPDATES_USER, payload, messageId, timestamp: ts });
  }
  saveJSON(FRIENDS_FILE, friends);
  console.log('[UPDATES] notice sent to', Object.keys(users).length - 1, 'users');
}

function saveJSON(file, data) {
  // Locked: the in-memory data is empty; writing it would wipe the real (encrypted) files.
  if (AtRest.locked()) { console.warn('[VAULT] locked, not saving', path.basename(file)); return; }
  if (_unreadable.has(file)) { console.error('[VAULT] not saving over unreadable', path.basename(file)); return; }
  try {
    AtRest.writeFile(file, JSON.stringify(data));
  } catch (e) { console.error(`Error saving ${file}:`, e.message); }
}

function historyFile(userA, userB) {
  return path.join(HISTORY_DIR, `${[userA, userB].sort().join('__')}.json`);
}

function groupHistoryFile(groupId) {
  return path.join(HISTORY_DIR, `group__${groupId}.json`);
}

function loadHistory(userA, userB)          { return loadJSON(historyFile(userA, userB), []); }
function saveHistory(userA, userB, msgs)    { saveJSON(historyFile(userA, userB), msgs.slice(-MAX_MESSAGE_HISTORY)); }
function loadGroupHistory(gid)              { return loadJSON(groupHistoryFile(gid), []); }
function saveGroupHistory(gid, msgs)        { saveJSON(groupHistoryFile(gid), msgs.slice(-MAX_MESSAGE_HISTORY)); }

/**
 * Server-authoritative message timestamp. Device clocks drift (a phone a few seconds
 * ahead made its messages sort below replies sent after them), so the server stamps
 * every message with its own clock, strictly after the newest entry already in this
 * conversation. Clients adopt it from the message_sent ack.
 */
function nextServerTimestamp(history) {
  let t = Date.now();
  const last = history && history.length ? Date.parse(history[history.length - 1].timestamp) : NaN;
  // Ignore a wildly future-dated old entry (device clock was way off) so it cannot
  // drag every new message into the future.
  if (!isNaN(last) && t <= last && last - t < 24 * 60 * 60 * 1000) t = last + 1;
  return new Date(t).toISOString();
}
function receiptsFile(userA, userB) {
  return path.join(RECEIPTS_DIR, `${[userA, userB].sort().join('__')}.json`);
}
function loadReceipts(userA, userB) { return loadJSON(receiptsFile(userA, userB), {}); }
function saveReceipts(userA, userB, data) { saveJSON(receiptsFile(userA, userB), data); }

/** Persist cumulative delivery/read from `reader` toward messages in the DM with `peer`. */
function notePeerReceipt(reader, peer, messageId, kind) {
  if (!reader || !peer || !messageId) return;
  const hist = loadHistory(reader, peer);
  const hit = hist.find(m => m.messageId === messageId);
  const ts = hit && hit.timestamp ? hit.timestamp : new Date().toISOString();
  const tsMs = new Date(ts).getTime() || 0;
  const data = loadReceipts(reader, peer);
  if (!data[reader]) data[reader] = {};
  const cur = data[reader];
  const prevD = cur.lastDeliveredTs ? new Date(cur.lastDeliveredTs).getTime() : 0;
  const prevR = cur.lastReadTs ? new Date(cur.lastReadTs).getTime() : 0;
  if (kind === 'delivered' || kind === 'read') {
    if (tsMs >= prevD) {
      cur.lastDeliveredId = messageId;
      cur.lastDeliveredTs = ts;
    }
  }
  if (kind === 'read') {
    if (tsMs >= prevR) {
      cur.lastReadId = messageId;
      cur.lastReadTs = ts;
    }
  }
  data[reader] = cur;
  saveReceipts(reader, peer, data);
}

/** When `reader` sends a message to `peer`, treat their prior messages from peer as read. */
function noteImpliedRead(reader, peer, atTimestamp) {
  if (!reader || !peer) return;
  const hist = loadHistory(reader, peer);
  const cut = new Date(atTimestamp || Date.now()).getTime() || 0;
  let best = null;
  for (const m of hist) {
    if (m.from !== peer) continue; // messages peer sent to reader
    const t = new Date(m.timestamp).getTime() || 0;
    if (t <= cut && (!best || t >= new Date(best.timestamp).getTime())) best = m;
  }
  if (best && best.messageId) notePeerReceipt(reader, peer, best.messageId, 'read');
}

function peerReceiptSnapshot(viewer, peer) {
  const data = loadReceipts(viewer, peer);
  const r = data[peer] || {};
  const me = data[viewer] || {};
  return {
    peerLastDeliveredId: r.lastDeliveredId || null,
    peerLastDeliveredTs: r.lastDeliveredTs || null,
    peerLastReadId: r.lastReadId || null,
    peerLastReadTs: r.lastReadTs || null,
    // Newest of the peer's messages any of the viewer's devices confirmed receiving. The app
    // treats newer history entries as missed while offline (unread + notification).
    selfLastDeliveredTs: me.lastDeliveredTs || null
  };
}


// ============================================================
//  IN-MEMORY STATE
// ============================================================
let users   = loadJSON(USERS_FILE,   {});
let friends = loadJSON(FRIENDS_FILE, {});
let pending = loadJSON(PENDING_FILE, {});
let groups  = loadJSON(GROUPS_FILE,  {});
let banned  = loadJSON(BANNED_FILE,  {});
let wiped   = loadJSON(WIPED_FILE,   {});   // username -> { wipedAt: ISO }

// ── Vault: lock / unlock / encrypt existing files ──
function reloadAllData() {
  users   = loadJSON(USERS_FILE,   {});
  friends = loadJSON(FRIENDS_FILE, {});
  pending = loadJSON(PENDING_FILE, {});
  groups  = loadJSON(GROUPS_FILE,  {});
  banned  = loadJSON(BANNED_FILE,  {});
  wiped   = loadJSON(WIPED_FILE,   {});
  _uploadIndex = null;
}
/** A newly published app version announces itself once (the server may start locked, so
 *  this runs whenever the data is available instead of from the publish script). */
function autoUpdateNotice() {
  try {
    if (!secrets) return;
    const apk = path.join(DOWNLOAD_DIR, 'silvershire-inn.apk');
    if (!fs.existsSync(apk)) return;
    const notes = versionNotes();
    if ((secrets.lastNoticeVersion || 0) >= APP_VERSION.versionCode) {
      // Already announced, but without the notes written for it since: send them once
      if (notes && secrets.lastNoticeNotes !== APP_VERSION.versionName && (secrets.lastNoticeVersion || 0) === APP_VERSION.versionCode) {
        secrets.lastNoticeNotes = APP_VERSION.versionName; saveSecrets();
        publishUpdateNotice();
        console.log('[UPDATES] Notes for ' + APP_VERSION.versionName + ' sent');
      }
      return;
    }
    secrets.lastNoticeVersion = APP_VERSION.versionCode; secrets.lastNoticeNotes = APP_VERSION.versionName; saveSecrets();
    publishUpdateNotice();
    console.log('[UPDATES] Notice for ' + APP_VERSION.versionName + ' sent');
  } catch (e) { console.error('[UPDATES] auto notice failed', e.message); }
}
function onVaultUnlocked(how) {
  console.log('[VAULT] Unlocked (' + how + ')');
  reloadAllData();
  loadSecrets();
  setTimeout(autoUpdateNotice, 2000);
  try { ensureUpdatesUser(); } catch (_) {}
  setTimeout(migrateAtRest, 1000);
}
/** Lock: save, drop the key and every bit of data from memory, disconnect everyone. */
function lockServer(why) {
  if (AtRest.locked()) return;
  console.log('[VAULT] Locking (' + why + ')');
  try { saveJSON(USERS_FILE, users); saveJSON(FRIENDS_FILE, friends); saveJSON(PENDING_FILE, pending);
        saveJSON(GROUPS_FILE, groups); saveJSON(BANNED_FILE, banned); saveJSON(WIPED_FILE, wiped); } catch (_) {}
  AtRest.lock();
  users = {}; friends = {}; pending = {}; groups = {}; wiped = {}; secrets = null;
  _uploadIndex = null;
  try { httpSessions.clear(); } catch (_) {}
  for (const set of connections.values()) for (const s of set) {
    try { s.send(JSON.stringify({ type: 'error', code: 'server_locked', message: 'Server is locked' })); } catch (_) {}
    try { s.terminate(); } catch (_) {}
  }
  connections.clear();
}
let vaultMigrating = '';
/** Encrypt everything still in plain form (first start after this update, or older files). */
async function migrateAtRest() {
  if (AtRest.locked() || vaultMigrating) return;
  vaultMigrating = 'starting';
  let done = 0, failed = 0;
  const pause = () => new Promise(r => setImmediate(r));
  const sealSmall = (file) => {
    try {
      const head = AtRest.fileHead(file);
      if (!head.length || AtRest.isSealed(head) || AtRest.isChunked(head)) return;
      AtRest.writeFile(file, fs.readFileSync(file)); done++;
    } catch (e) { failed++; console.warn('[VAULT] could not encrypt', path.basename(file), e.message); }
  };
  const list = (dir, test) => { try { return fs.readdirSync(dir).filter(test).map(f => path.join(dir, f)); } catch (_) { return []; } };
  try {
    const small = [USERS_FILE, FRIENDS_FILE, PENDING_FILE, GROUPS_FILE, BANNED_FILE, WIPED_FILE]
      .concat(list(HISTORY_DIR, f => f.endsWith('.json')))
      .concat(list(RECEIPTS_DIR, f => f.endsWith('.json')))
      .concat(list(UPLOADS_DIR, f => f.endsWith('.meta.json')))
      .concat(list(PROFILE_PICS_DIR, f => !/\.(etag|tmp\d*)$/.test(f)))
      .concat(list(BANNER_PICS_DIR, f => !/\.(etag|tmp\d*)$/.test(f)))
      .concat(list(path.join(DATA_DIR, 'crash_reports'), f => f.endsWith('.txt')));
    for (const f of small) {
      if (AtRest.locked()) break;
      if (fs.existsSync(f)) sealSmall(f);
      vaultMigrating = `${done} files`;
      if (done % 20 === 0) await pause();
    }
    // Older uploads that weren't end-to-end encrypted by the sender: encrypt in place.
    for (const m of uploadIndexAll()) {
      if (AtRest.locked()) break;
      if (!m || m.e2e || !m.storedName) continue;
      const f = path.join(UPLOADS_DIR, m.storedName);
      const head = AtRest.fileHead(f);
      if (!head.length || AtRest.isChunked(head)) continue;
      try { vaultMigrating = `upload ${m.storedName}`; await AtRest.sealFileChunked(f); done++; }
      catch (e) { failed++; console.warn('[VAULT] upload not encrypted yet (in use?)', m.storedName, e.message); }
    }
  } finally {
    if (done || failed) console.log(`[VAULT] Encrypted ${done} existing file(s)` + (failed ? `, ${failed} to retry next start` : ''));
    vaultMigrating = '';
  }
}
/** In the server window: type the master password to unlock (when started with one set). */
function promptMasterPassword() {
  if (!process.stdin.isTTY) return;
  const readline = require('readline');
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
  rl._writeToOutput = function (s) { if (s.includes('Master password')) rl.output.write(s); else rl.output.write('*'); };
  const ask = () => rl.question('Master password: ', (pw) => {
    rl.output.write('\n');
    if (!AtRest.locked()) { rl.close(); return; }
    try { AtRest.unlockWithPassword(pw); onVaultUnlocked('server window'); rl.close(); }
    catch (e) { console.log('  ' + e.message); setTimeout(ask, 1200); }
  });
  ask();
}

const connections    = new Map();   // username -> Set<ws>  (multi-device: phone + web client)
const connectionIPs  = new Map();   // username -> ip (set on login/register)
const failedAuths    = new Map();   // ip -> count
const lastSeenMap    = new Map();   // username -> ISO timestamp (set on disconnect)
const activeCalls    = new Map();   // username -> partner (both directions, cleared on end)
const adminSessions  = new Set();   // active admin session tokens

// ============================================================
//  UTILITY
// ============================================================
function isBanned(ip) {
  const entry = banned[ip];
  if (!entry) return false;
  if (Date.now() > entry.until) { delete banned[ip]; saveJSON(BANNED_FILE, banned); return false; }
  return true;
}

function recordFailedAuth(ip) {
  const count = (failedAuths.get(ip) || 0) + 1;
  failedAuths.set(ip, count);
  if (count >= MAX_FAILED_AUTHS) {
    banned[ip] = { until: Date.now() + BAN_DURATION_MS, attempts: count };
    saveJSON(BANNED_FILE, banned);
    console.log(`[SECURITY] IP banned for 24h: ${ip}`);
    return true;
  }
  return false;
}

/**
 * Rules for NEW usernames (register / rename): 2-24 letters, numbers, spaces, dots, dashes or
 * underscores; no "__" (it separates the two names in history file names, so "a__b" could
 * collide with another chat), no "..", nothing that looks like a group file. Names went
 * straight into file paths before, unchecked.
 */
function validNewUsername(n) {
  if (typeof n !== 'string') return false;
  if (n !== n.trim() || n.length < 2 || n.length > 24) return false;
  if (!/^[\p{L}\p{N} ._-]+$/u.test(n)) return false;
  if (n.includes('__') || n.includes('..') || n.startsWith('.') || /^group/i.test(n)) return false;
  return true;
}
/** The two names a "userA__userB.json" history / receipts file belongs to (exact match). */
function chatFileUsers(f) {
  if (!f.endsWith('.json') || f.startsWith('group__')) return null;
  const parts = f.slice(0, -5).split('__');
  return parts.length === 2 ? parts : null;
}
const MAX_PAYLOAD_CHARS = 256 * 1024;   // one message (text or file reference), not media

function areMutualFriends(a, b) {
  return (friends[a] || []).includes(b) && (friends[b] || []).includes(a);
}
/** 1:1 chats: friends, or yourself (the Notes chat: messages to yourself, end-to-end encrypted
 *  to your own key and synced between your devices). */
function canChat(a, b) { return !!a && (a === b || areMutualFriends(a, b)); }

/** True when [username] is a member of the group identified by [groupId]. */
/** Public keys of a group's members, so clients can encrypt group messages for every member. */
function groupMemberKeys(groupId) {
  const out = {};
  for (const m of ((groups[groupId] && groups[groupId].members) || [])) {
    if (users[m] && users[m].publicKey) out[m] = users[m].publicKey;
  }
  return out;
}

function isGroupMember(username, groupId) {
  return !!(groups[groupId] && groups[groupId].members.includes(username));
}

function send(ws, data) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(data));
}

// ---------------- Multi-device connection helpers ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
// Each username maps to a SET of sockets so the Android app and the
// web client can be online simultaneously. All sends fan out to every
// device; a user only counts as offline when their LAST socket closes.
function addConnection(username, ws) {
  // A device counts as "active" (online) only while the app / tab is actually in use. Clients
  // send it with login and on every change (app_state). Older clients never say: active.
  if (ws.isActive === undefined) ws.isActive = ws.loginActive !== false;
  if (!connections.has(username)) connections.set(username, new Set());
  connections.get(username).add(ws);
}

/** Send to one specific device of a user (falls back to all of their devices). */
function sendToDevice(username, deviceId, data) {
  if (deviceId) {
    for (const sock of (connections.get(username) || [])) {
      if (sock.deviceId === deviceId) { send(sock, data); return true; }
    }
  }
  sendToUser(username, data);
  return false;
}

// ── Calls: STUN (+ optional built-in TURN relay) for WebRTC ────────────────────
// TURN only works from outside your network after forwarding UDP/TCP 3478 and
// UDP 49160-49200 on the router to this PC. Without it most calls still connect directly.
const TURN_PORT = 3478;
const TURN_USER = 'silvershire';
// TURN relay credential (handed to every caller anyway): its own random secret.
if (!_cfg.turnSecret) { _cfg.turnSecret = crypto.randomBytes(16).toString('hex'); writeConfig(); }
const TURN_PASS = crypto.createHash('sha256').update('turn:' + _cfg.turnSecret).digest('hex').slice(0, 24);
let turnRunning = false;
try {
  const Turn = require('node-turn');
  const turn = new Turn({
    authMech: 'long-term', credentials: { [TURN_USER]: TURN_PASS }, realm: 'silvershire',
    listeningPort: TURN_PORT, minPort: 49160, maxPort: 49200, debugLevel: 'OFF'
  });
  turn.start();
  turnRunning = true;
  console.log(`[TURN] relay listening on ${TURN_PORT} (forward UDP/TCP ${TURN_PORT} + UDP 49160-49200 to use it from outside)`);
} catch (e) { console.log('[TURN] not running (' + (e.code || e.message) + ') - direct (STUN) calls only'); }

function iceServersFor(ws) {
  const list = [
    { urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302', 'stun:stun.cloudflare.com:3478'] }
  ];
  if (turnRunning && ws && ws.publicHost && ws.publicHost !== 'localhost') {
    list.push({ urls: [`turn:${ws.publicHost}:${TURN_PORT}?transport=udp`, `turn:${ws.publicHost}:${TURN_PORT}?transport=tcp`],
                username: TURN_USER, credential: TURN_PASS });
  }
  return list;
}

/** True while at least one of the user's devices is actually in use (not just connected). */
function isUserActive(username) {
  if (panicLocked(username)) return false;   // locked: shown as away / offline
  const set = connections.get(username);
  if (!set) return false;
  for (const sock of set) if (sock.isActive !== false) return true;
  return false;
}

/** Last time a user was actually using the app (kept across server restarts). */
let _lastSeenSaveTimer = null;
function markLastSeen(username) {
  const iso = new Date().toISOString();
  lastSeenMap.set(username, iso);
  if (users[username]) {
    users[username].lastSeen = iso;
    if (!_lastSeenSaveTimer) _lastSeenSaveTimer = setTimeout(() => { _lastSeenSaveTimer = null; saveJSON(USERS_FILE, users); }, 5000);
  }
}

/** Remove one socket. Returns true if the user is now fully offline. */
function removeConnection(username, ws) {
  const set = connections.get(username);
  if (!set) return true;
  set.delete(ws);
  if (set.size === 0) { connections.delete(username); return true; }
  return false;
}

/** Close every socket a user has and forget them. */
function closeAllConnections(username) {
  for (const sock of (connections.get(username) || [])) {
    try { sock.close(); } catch (_) {}
  }
  connections.delete(username);
}

// Panic-locked account: only these reach its devices (no messages, calls, names, pictures...).
const PANIC_OUT_OK = new Set(['account_locked', 'account_unlocked', 'panic_config', 'panic_unlock_result', 'error']);
// ...and only these are accepted from them.
const PANIC_IN_OK = new Set(['panic_unlock', 'panic_lock', 'app_state']);
function panicLocked(u) { return !!(u && users[u] && users[u].panicLocked); }
/** What devices may know of the panic phrases: hashes to spot the lock phrase, salts. Never the unlock hash. */
function publicPanicConfig(u) {
  const p = users[u] && users[u].panic;
  if (!p) return null;
  return { lockSalt: p.lockSalt, lockHash: p.lockHash, lockLen: p.lockLen, lockWords: p.lockWords,
           unlockSalt: p.unlockSalt, unlockIter: p.unlockIter, unlockWords: p.unlockWords };
}
const panicTries = new Map();   // username -> { next, fails }
const panicServerKeys = new Map();   // username -> data key wrapped by their unlock phrase (server panic-locked)
function panicKeyFrom(unlockHash) { return crypto.createHash('sha256').update('silvershire-panic-server:' + String(unlockHash)).digest(); }
/** Unlock with the unlock phrase: checks the proofs; works while the server is panic-locked too. */
function panicUnlockHttp(name, proofs) {
  if (AtRest.locked()) {
    const wrapped = panicServerKeys.get(name);
    if (!wrapped) return { ok: false, error: 'Server is locked: open the Vault page' };
    for (const p of proofs) {
      if (AtRest.unwrapKeyWith(wrapped, panicKeyFrom(p))) {
        panicServerKeys.clear();
        onVaultUnlocked('unlock phrase from ' + name);
        if (users[name]) { users[name].panicLocked = false; delete users[name].panicLockedAt; saveJSON(USERS_FILE, users); }
        for (const pr of chatPeersOf(name)) sendToUser(pr, { type: 'peer_unlocked', name });
        return { ok: true };
      }
    }
    return { ok: false };
  }
  const pc = users[name] && users[name].panic;
  if (!pc || !proofs.some(x => safeEq(x, pc.unlockHash))) return { ok: false };
  if (users[name].panicLocked) {
    users[name].panicLocked = false; delete users[name].panicLockedAt; saveJSON(USERS_FILE, users);
    console.log(`[PANIC] ${name} unlocked the account`);
  }
  sendToUser(name, { type: 'account_unlocked' });
  for (const pr of chatPeersOf(name)) sendToUser(pr, { type: 'peer_unlocked', name });
  return { ok: true };
}
/** Everyone who shares a chat with [u]: friends and group members. Their copies lock too. */
function chatPeersOf(u) {
  const out = new Set((friends[u] || []).filter(f => f !== u && areMutualFriends(u, f)));
  for (const g of Object.values(groups)) {
    if (g && Array.isArray(g.members) && g.members.includes(u)) g.members.forEach(m => { if (m !== u) out.add(m); });
  }
  return Array.from(out);
}
/** Locked people [viewer] shares chats with (sent at sign in so their chats stay hidden). */
function lockedPeersFor(viewer) { return chatPeersOf(viewer).filter(panicLocked); }

function sendToUser(username, data) {
  if (panicLocked(username) && !PANIC_OUT_OK.has(data && data.type)) return;
  for (const sock of (connections.get(username) || [])) send(sock, data);
}

/** Send to all of a user's devices EXCEPT the given socket (echo to other devices). */
function sendToOtherDevices(username, exceptWs, data) {
  for (const sock of (connections.get(username) || [])) {
    if (sock !== exceptWs) send(sock, data);
  }
}

function notifyOnlineStatus(username, online) {
  const msg = buildOnlineStatusMsg(username, !!online && isUserActive(username));
  for (const friend of (friends[username] || [])) {
    if (areMutualFriends(username, friend)) {
      sendToUser(friend, msg);
    }
  }
}

function jsonResponse(res, statusCode, obj) {
  res.writeHead(statusCode, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(obj));
}

function parseQuery(urlStr) {
  try { return Object.fromEntries(new URL(urlStr, 'http://localhost').searchParams); }
  catch { return {}; }
}

// Session cookie + HMAC media tokens. Never accept SERVER_PASSWORD from the query string
// (Caddy/access logs were leaking ?password=). Clients send x-password headers, a
// short-lived mt= grant, or the HttpOnly gs_sid cookie issued by POST /session.
const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const MEDIA_TOKEN_TTL_MS = 12 * 60 * 60 * 1000;
const MEDIA_HMAC_KEY = crypto.randomBytes(32);
const httpSessions = new Map();

function parseCookies(reqOrHeader) {
  const out = {};
  let raw = '';
  if (reqOrHeader == null) raw = '';
  else if (typeof reqOrHeader === 'string') raw = reqOrHeader;
  else if (Array.isArray(reqOrHeader)) raw = reqOrHeader.join('; ');
  else if (reqOrHeader.headers) {
    const h = reqOrHeader.headers.cookie;
    raw = Array.isArray(h) ? h.join('; ') : String(h || '');
  } else {
    raw = String(reqOrHeader || '');
  }
  String(raw).split(';').forEach(part => {
    const i = part.indexOf('=');
    if (i < 0) return;
    const k = part.slice(0, i).trim();
    const v = part.slice(i + 1).trim();
    if (k) {
      try { out[k] = decodeURIComponent(v); } catch { out[k] = v; }
    }
  });
  return out;
}

function issueSession(username) {
  const sid = crypto.randomBytes(24).toString('hex');
  httpSessions.set(sid, { username, exp: Date.now() + SESSION_TTL_MS });
  return sid;
}

function mintMediaToken(username) {
  const exp = Date.now() + MEDIA_TOKEN_TTL_MS;
  const sig = crypto.createHmac('sha256', MEDIA_HMAC_KEY).update(username + '|' + exp).digest('base64url');
  return Buffer.from(JSON.stringify({ u: username, e: exp, s: sig })).toString('base64url');
}

function verifyMediaToken(token) {
  try {
    const j = JSON.parse(Buffer.from(String(token), 'base64url').toString('utf8'));
    if (!j || !j.u || !j.e || !j.s || Date.now() > Number(j.e)) return null;
    const expected = crypto.createHmac('sha256', MEDIA_HMAC_KEY).update(j.u + '|' + j.e).digest('base64url');
    const a = Buffer.from(String(j.s));
    const b = Buffer.from(expected);
    if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
    if (!users[j.u]) return null;
    return j.u;
  } catch { return null; }
}

function cookieHeader(sid, req) {
  const xf = String(req.headers['x-forwarded-proto'] || '');
  const secure = xf === 'https' || !!req.socket.encrypted;
  return 'gs_sid=' + sid + '; HttpOnly; Path=/; SameSite=Lax; Max-Age=' +
    Math.floor(SESSION_TTL_MS / 1000) + (secure ? '; Secure' : '');
}

// Per-user HTTP tokens. HTTP requests used to prove identity with the SHARED app password
// plus a self-declared x-username, so anyone with the app password could act as any other
// user over HTTP (download their files, replace their profile picture...). After the
// WebSocket login (proven with the user's own key) the server now hands each device a
// random token; requests send it as x-auth-token. Once a user has a token-capable device,
// the old password + username way is refused for that user; old app versions keep working
// only for users who have never logged in with an updated app.
function hashToken(t) { return crypto.createHash('sha256').update(String(t)).digest('hex'); }
function mintHttpToken(username, existing) {
  const u = users[username]; if (!u) return null;
  const list = Array.isArray(u.httpTokens) ? u.httpTokens : [];
  // A device that already has a valid token keeps it (most recently used last), so each
  // reconnect doesn't push other devices' tokens out of the list.
  if (existing) {
    const h = hashToken(existing);
    if (list.includes(h)) {
      u.httpTokens = list.filter(x => x !== h).concat(h);
      if (!u.strictHttp) { u.strictHttp = true; saveJSON(USERS_FILE, users); }
      return existing;
    }
  }
  const tok = crypto.randomBytes(32).toString('hex');
  u.httpTokens = list.concat(hashToken(tok)).slice(-20);
  u.strictHttp = true;
  saveJSON(USERS_FILE, users);
  return tok;
}
function userForToken(tok) {
  if (!tok) return null;
  const h = hashToken(tok);
  for (const [name, u] of Object.entries(users)) {
    if (Array.isArray(u.httpTokens) && u.httpTokens.includes(h)) return name;
  }
  return null;
}
/** Legacy shared-password identity, only for users who never got a token. */
function legacyHttpUser(req) {
  const password = req.headers['x-password'] || '';
  const username = req.headers['x-username'] || '';
  if (username && users[username] && !users[username].strictHttp && checkServerPassword(password)) return username;
  return null;
}

/** Cookie, HMAC media token, x-auth-token, or (legacy) x-password + x-username. Query password is ignored. */
function httpAuth(req, qs) {
  const r = httpAuthRaw(req, qs);
  if (r && panicLocked(r.username)) return null;   // locked account: no files, pictures, nothing
  return r;
}
function httpAuthRaw(req, qs) {
  const cookies = parseCookies(req);
  const sid = cookies.gs_sid;
  if (sid && httpSessions.has(sid)) {
    const s = httpSessions.get(sid);
    if (s.exp > Date.now() && s.username && users[s.username]) return { username: s.username };
    httpSessions.delete(sid);
  }
  const mt = (qs && qs.mt) || req.headers['x-media-token'] || '';
  if (mt) {
    const u = verifyMediaToken(mt);
    if (u) return { username: u };
  }
  const tokUser = userForToken(req.headers['x-auth-token']);
  if (tokUser) return { username: tokUser };
  const legacy = legacyHttpUser(req);
  if (legacy) return { username: legacy };
  return null;
}


// In-memory list of upload details (fileId -> meta), loaded once, kept current on upload.
let _uploadIndex = null;
function uploadIndexLoad() {
  if (_uploadIndex) return _uploadIndex;
  _uploadIndex = new Map();
  try {
    for (const f of fs.readdirSync(UPLOADS_DIR)) {
      if (!f.endsWith('.meta.json')) continue;
      const m = loadJSON(path.join(UPLOADS_DIR, f), null);
      if (m && m.fileId) _uploadIndex.set(m.fileId, m);
    }
  } catch (_) {}
  return _uploadIndex;
}
function uploadIndexAdd(meta) { uploadIndexLoad().set(meta.fileId, meta); }
function uploadIndexAll() { return Array.from(uploadIndexLoad().values()); }

// ============================================================
//  HTTP SERVER -------- file upload / download / list
// ============================================================
const httpServer = http.createServer((req, res) => {
  const ip  = req.socket.remoteAddress;

  // CORS -------- lets the web client work when opened as a local file (file:// has a
  // "null" origin) or from another host. The Android app never sends an Origin
  // header, so this changes nothing for it. Auth is still enforced on every route.
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, PUT, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers',
    'Content-Type, x-password, x-username, x-media-token, x-auth-token, x-conversation-with, x-filename, x-mime-type, x-message-id, x-group-e2e, x-e2e, Range');
  if (req.method === 'OPTIONS') {   // preflight for PUT /upload with custom headers
    res.writeHead(204);
    res.end();
    return;
  }

  if (isBanned(ip)) {
    jsonResponse(res, 403, { error: 'Banned' });
    return;
  }

  const url  = req.url || '/';
  const qs   = parseQuery(url);
  const base = url.split('?')[0];
  if (req.method === 'POST' && base === '/panic-unlock') {
    // Unlock phrase from a device (proofs only). Works even when the account's panic phrase
    // locked the whole server. Slowed down per address.
    const t = panicHttpTries.get(ip) || { next: 0, fails: 0 };
    if (Date.now() < t.next) { jsonResponse(res, 429, { ok: false, wait: t.next - Date.now() }); return; }
    let b = ''; req.on('data', c => { if (b.length < 20000) b += c; });
    req.on('end', () => {
      let j = {}; try { j = JSON.parse(b || '{}'); } catch (_) {}
      const name = String(j.name || '').slice(0, 64);
      const proofs = Array.isArray(j.proofs) ? j.proofs.slice(0, 16).map(String) : [];
      let r = { ok: false };
      try { r = panicUnlockHttp(name, proofs); } catch (e) { console.error('[PANIC] unlock', e.message); }
      if (r.ok) panicHttpTries.delete(ip);
      else { t.fails++; t.next = Date.now() + (t.fails >= 30 ? 60000 : 1200); panicHttpTries.set(ip, t); }
      jsonResponse(res, r.ok ? 200 : 403, r);
    });
    return;
  }
  // The download page, the APK and the shared drawing code are public, so they work while locked too
  // (invite links used to show "Server is locked" after every restart until the vault was opened).
  if (AtRest.locked() && !(req.method === 'GET' && (base === '/' || base === '/web' || base === '/web/' || base === '/api/version' ||
      base === '/download' || base === '/download/' || base === '/download/index.html' || base === '/download/silvershire-inn.apk' ||
      base === '/frames.js' || base === '/web/frames.js'))) {
    jsonResponse(res, 503, { error: 'Server is locked' });
    return;
  }


  // ?? GET /download  &  /download/silvershire-inn.apk  (public APK invite) ??
  // No sign in: people can install the app before they have an account.
  if (req.method === 'GET' && (base === '/download' || base === '/download/' || base === '/download/index.html')) {
    const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Silvershire Inn - Download</title>
<style>
body{font-family:system-ui,sans-serif;background:#1A1A1E;color:#F0F0F5;display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0}
.card{background:#27272C;border-radius:16px;padding:32px 28px;max-width:420px;text-align:center;box-shadow:0 8px 32px #0008}
h1{margin:0 0 8px;font-size:22px;color:#A8A9AD}
p{color:#A8A8BA;font-size:14px;line-height:1.45}
a.btn{display:inline-block;margin-top:18px;padding:14px 28px;background:#A8A9AD;color:#1A1A1E;text-decoration:none;border-radius:10px;font-weight:700;font-size:16px}
.note{margin-top:16px;font-size:12px;color:#757575}
</style></head><body><div class="card">
<h1>Silvershire Inn</h1>
<p>Download the Android app to chat on your phone.</p>
<a class="btn" href="/download/silvershire-inn.apk">Download APK</a>
<p class="note">On Android: open the file, then allow installs from this source if asked.</p>
</div></body></html>`;
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' });
    res.end(html);
    return;
  }
  if (req.method === 'GET' && base === '/download/silvershire-inn.apk') {
    const apkPath = path.join(DOWNLOAD_DIR, 'silvershire-inn.apk');
    if (!fs.existsSync(apkPath)) {
      jsonResponse(res, 404, { error: 'APK not published yet. Build the app and copy app-debug.apk to data/download/silvershire-inn.apk' });
      return;
    }
    const stat = fs.statSync(apkPath);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': stat.size,
      'Content-Disposition': 'attachment; filename="silvershire-inn.apk"',
      'Cache-Control': 'no-cache'
    });
    fs.createReadStream(apkPath).pipe(res);
    return;
  }


  if (req.method === 'POST' && base === '/session/end') {
    // Web logout: forget this browser's cookie session.
    const sid = parseCookies(req).gs_sid;
    if (sid) httpSessions.delete(sid);
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store',
      'Set-Cookie': 'gs_sid=; HttpOnly; Path=/; SameSite=Lax; Max-Age=0' });
    res.end('{"ok":true}');
    return;
  }

  if ((req.method === 'POST' || req.method === 'GET') && base === '/session') {
    let username = null;
    const already = httpAuth(req, qs);
    if (already) username = already.username;
    if (!username) { jsonResponse(res, 401, { error: 'Unauthorized' }); return; }
    const sid = issueSession(username);
    const mt = mintMediaToken(username);
    res.writeHead(200, {
      'Content-Type': 'application/json',
      'Set-Cookie': cookieHeader(sid, req),
      'Cache-Control': 'no-store'
    });
    res.end(JSON.stringify({ ok: true, mt, username }));
    return;
  }

  if (req.method === 'GET' && base === '/api/version') {
    let apkMtime = 0;
    const apkPath = path.join(DOWNLOAD_DIR, 'silvershire-inn.apk');
    try { if (fs.existsSync(apkPath)) apkMtime = fs.statSync(apkPath).mtimeMs; } catch (_) {}
    jsonResponse(res, 200, { ...APP_VERSION, apkUrl: '/download/silvershire-inn.apk', apkMtime });
    return;
  }

  if (req.method === 'GET' && base === '/branding/status') {
    const stPath = path.join(BRANDING_DIR, 'status.json');
    if (!fs.existsSync(stPath)) { jsonResponse(res, 200, { status: 'idle', message: 'No branding build yet' }); return; }
    jsonResponse(res, 200, loadJSON(stPath, { status: 'idle' }));
    return;
  }

  if (req.method === 'POST' && base === '/branding/build') {
    // Admin only: this rebuilds the app with a new name / icon and REPLACES the APK everyone
    // downloads. Any user could trigger it before.
    const adminPw = req.headers['x-admin-password'] || '';
    const auth = httpAuth(req, qs);
    const usr = auth ? auth.username : '';
    if (!checkAdminPassword(adminPw) || !usr) {
      jsonResponse(res, 401, { error: 'Admin password required' }); return;
    }
    collectBody(req, (err, buf) => {
      if (err) { jsonResponse(res, 400, { error: 'body' }); return; }
      try {
        const ct = String(req.headers['content-type'] || '');
        let appName = 'Silvershire Inn';
        let iconBuf = null;
        if (ct.includes('application/json')) {
          const j = JSON.parse(buf.toString('utf8') || '{}');
          appName = j.appName || appName;
          if (j.iconBase64) iconBuf = Buffer.from(j.iconBase64, 'base64');
        } else {
          const parsed = parseMultipart(buf, ct);
          if (parsed.appName) appName = parsed.appName;
          if (parsed.icon) iconBuf = parsed.icon;
        }
        fs.writeFileSync(path.join(BRANDING_DIR, 'branding.json'), JSON.stringify({ appName, requestedBy: usr }, null, 2));
        if (iconBuf) fs.writeFileSync(path.join(BRANDING_DIR, 'icon.png'), iconBuf);
        fs.writeFileSync(path.join(BRANDING_DIR, 'status.json'), JSON.stringify({ status: 'building', message: 'Stamp started' }, null, 2));
        const { spawn } = require('child_process');
        const child = spawn(process.execPath, [path.join(__dirname, 'tools', 'stamp-branded-apk.js')], {
          cwd: __dirname, detached: true, stdio: 'ignore', windowsHide: true
        });
        child.unref();
        jsonResponse(res, 202, { status: 'building', message: 'Branded APK build started' });
      } catch (e) {
        jsonResponse(res, 500, { error: e.message });
      }
    });
    return;
  }

  if (req.method === 'POST' && base === '/admin/publish-update') {
    // Admin password only (the shared app password let any user message everyone).
    const password = req.headers['x-admin-password'] || req.headers['x-password'] || '';
    if (!checkAdminPassword(password)) {
      jsonResponse(res, 401, { error: 'auth' }); return;
    }
    publishUpdateNotice();
    jsonResponse(res, 200, { ok: true });
    return;
  }


  // ---------------- PUT /upload  ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  // ---------------- POST /crash_report (app error reports -> data/crash_reports) ----------------
  if (req.method === 'POST' && base === '/crash_report') {
    const password = req.headers['x-password'] || '';
    const tokUser  = userForToken(req.headers['x-auth-token']);
    const who      = String(tokUser || req.headers['x-username'] || 'unknown').replace(/[^\w.-]/g, '_').slice(0, 40);
    if (!tokUser && !checkServerPassword(password)) { jsonResponse(res, 401, { error: 'Unauthorized' }); return; }
    let body = ''; let tooBig = false;
    req.on('data', c => { if (body.length > 200000) { tooBig = true; return; } body += c; });
    req.on('end', () => {
      if (tooBig) { jsonResponse(res, 413, { error: 'too large' }); return; }
      try {
        const dir = path.join(DATA_DIR, 'crash_reports');
        fs.mkdirSync(dir, { recursive: true });
        const file = path.join(dir, `${new Date().toISOString().replace(/[:.]/g, '-')}_${who}.txt`);
        AtRest.writeFile(file, body);   // device details: encrypted like everything else
        // Keep the folder small: newest 300 reports.
        const all = fs.readdirSync(dir).sort();
        all.slice(0, Math.max(0, all.length - 300)).forEach(f => { try { fs.unlinkSync(path.join(dir, f)); } catch (_) {} });
        console.log(`[CRASH] report from ${who}: ${body.split('\n').slice(0, 2).join(' | ')}`);
        jsonResponse(res, 200, { ok: true });
      } catch (e) { jsonResponse(res, 500, { error: 'write failed' }); }
    });
    return;
  }

  if (req.method === 'PUT' && base === '/upload') {
    const auth        = httpAuth(req, qs);
    const uploaderUsr = auth ? auth.username : '';
    const convWith    = req.headers['x-conversation-with'] || '';
    // A malformed %-escape in the name used to throw here.
    let fileName = 'file';
    try { fileName = decodeURIComponent(req.headers['x-filename'] || 'file'); } catch (_) { fileName = String(req.headers['x-filename'] || 'file'); }
    fileName = fileName.replace(/[\u0000-\u001f\\/:*?"<>|]/g, '_').slice(0, 200) || 'file';
    const mimeType    = String(req.headers['x-mime-type'] || 'application/octet-stream').slice(0, 100);
    // Optional client message id so group uploads keep the sender's local id (no duplicates).
    const clientMsgId = String(req.headers['x-message-id'] || '').slice(0, 64);

    if (!uploaderUsr) {
      jsonResponse(res, 401, { error: 'Unauthorized' }); return;
    }
    if (!convWith || (!canChat(uploaderUsr, convWith) && !isGroupMember(uploaderUsr, convWith))) {   // canChat: friends, or Notes (yourself)
      jsonResponse(res, 403, { error: 'Forbidden: not mutual contacts or group member' }); return;
    }

    const fileId     = crypto.randomUUID();
    const rawExt     = path.extname(fileName);
    const ext        = /^\.[A-Za-z0-9]{1,8}$/.test(rawExt) ? rawExt : '';   // nothing odd in the stored name
    const storedName = fileId + ext;
    const filePath   = path.join(UPLOADS_DIR, storedName);
    const metaPath   = path.join(UPLOADS_DIR, fileId + '.meta.json');

    // Stream directly to disk -------- never buffer the whole file in memory.
    // pipe() respects backpressure (writing without it buffered a big upload in memory when
    // the disk was slower than the network). Capped at 2 GB; a cancelled upload is removed
    // instead of leaving a half file behind forever.
    const MAX_UPLOAD = 2 * 1024 * 1024 * 1024;
    let size       = 0;
    let errorSent  = false;
    const writeStream = fs.createWriteStream(filePath);
    const abortUpload = (why, code) => {
      if (errorSent) return;
      errorSent = true;
      try { req.unpipe(writeStream); } catch (_) {}
      writeStream.destroy();
      setTimeout(() => { try { fs.unlinkSync(filePath); } catch (_) {} }, 200);
      if (code && !res.writableEnded) jsonResponse(res, code, { error: why });
    };
    req.on('data', chunk => {
      size += chunk.length;
      if (size > MAX_UPLOAD) { abortUpload('File too large (max 2 GB)', 413); try { req.destroy(); } catch (_) {} }
    });
    req.on('aborted', () => abortUpload('aborted', 0));
    // End-to-end encrypted by the sender (x-e2e): already noise, stored as it is. Anything
    // else (older apps) is encrypted here before it touches the disk.
    const clientE2E = String(req.headers['x-e2e'] || '') === '1';
    // Chat files must be end-to-end encrypted by the app or web app; anything else is refused.
    if (!clientE2E) { abortUpload('Files must be end-to-end encrypted (update the app)', 400); try { req.destroy(); } catch (_) {} return; }
    if (clientE2E) req.pipe(writeStream);
    else {
      const sealer = AtRest.chunkSealer();
      sealer.on('error', () => abortUpload('Server error during write', 500));
      req.pipe(sealer).pipe(writeStream);
    }

    writeStream.on('finish', () => {
      if (errorSent) return;
      {
        try {
          const meta = { fileId, originalName: fileName, storedName, mimeType,
                         uploadedBy: uploaderUsr, conversationWith: convWith,
                         uploadedAt: new Date().toISOString(), size, e2e: clientE2E };
          saveJSON(metaPath, meta);

          // Notify recipient(s) via WebSocket
          if (groups[convWith] && String(req.headers['x-group-e2e'] || '') === '1') {
            // Updated clients send the (encrypted) file message themselves: the server must
            // not announce the file in plain text.
          } else if (groups[convWith]) {
            // Group upload (older clients): save to group history (it used to be relay-only, so group files
            // vanished on reinstall / other devices), relay to members, echo to the uploader's
            // other devices. Uses the client's message id when given so nothing duplicates.
            const attPayload = JSON.stringify({ _att: { fileId, name: fileName, mime: mimeType, size } });
            const gHist = loadGroupHistory(convWith);
            const gMsgId = clientMsgId || fileId;
            if (!gHist.some(m => m.messageId === gMsgId)) {
              const gMessage = { messageId: gMsgId, groupId: convWith, from: uploaderUsr,
                                 payload: attPayload, timestamp: nextServerTimestamp(gHist) };
              gHist.push(gMessage);
              saveGroupHistory(convWith, gHist);
              groups[convWith].members.forEach(m => {
                if (m !== uploaderUsr) sendToUser(m, { type: 'group_message', ...gMessage });
              });
              if (clientMsgId) sendToUser(uploaderUsr, { type: 'message_echo', ...gMessage });
            }
          } else if (convWith !== uploaderUsr) {
            sendToUser(convWith, {
              type: 'file_shared', fileId, fileName, mimeType, size, from: uploaderUsr
            });
          }

          uploadIndexAdd(meta);
          console.log(`[UPLOAD] ${uploaderUsr} -------- ${convWith}: "${fileName}" (${size} bytes)`);
          jsonResponse(res, 200, { fileId, fileName, size });
        } catch (e) {
          console.error('[UPLOAD ERROR]', e.message);
          if (!res.writableEnded) jsonResponse(res, 500, { error: 'Server error during write' });
        }
      }
    });

    req.on('error', err => {
      console.error('[UPLOAD REQ ERROR]', err.message);
      abortUpload('Upload aborted', 500);
    });

    writeStream.on('error', err => {
      console.error('[UPLOAD WRITE ERROR]', err.message);
      abortUpload('Server write error', 500);
    });
    return;
  }

  // ---------------- GET /file/:fileId  ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  const fileMatch = base.match(/^\/file\/([0-9a-f-]{36})$/i);
  if (req.method === 'GET' && fileMatch) {
    const fileId  = fileMatch[1];
    const auth = httpAuth(req, qs);
    if (!auth) { jsonResponse(res, 401, { error: 'Unauthorized' }); return; }
    const requester = auth.username;

    const metaPath = path.join(UPLOADS_DIR, fileId + '.meta.json');
    if (!fs.existsSync(metaPath)) {
      jsonResponse(res, 404, { error: 'File not found' }); return;
    }

    const meta = loadJSON(metaPath, null);
    if (!meta) { jsonResponse(res, 404, { error: 'Metadata missing' }); return; }

    // Only uploader, their contact, or a group member may download
    const isGroupUpload = !!groups[meta.conversationWith];
    if (requester !== meta.uploadedBy && (panicLocked(meta.uploadedBy) || (!isGroupUpload && panicLocked(meta.conversationWith)))) {
      jsonResponse(res, 423, { error: 'Locked' }); return;
    }
    if (requester === meta.uploadedBy && !isGroupUpload && panicLocked(meta.conversationWith)) {
      jsonResponse(res, 423, { error: 'Locked' }); return;
    }
    if (requester !== meta.uploadedBy &&
        requester !== meta.conversationWith &&
        !(isGroupUpload && isGroupMember(requester, meta.conversationWith))) {
      jsonResponse(res, 403, { error: 'Forbidden' }); return;
    }

    const filePath = path.join(UPLOADS_DIR, meta.storedName);
    if (!fs.existsSync(filePath)) {
      jsonResponse(res, 404, { error: 'File data missing' }); return;
    }

    const stat     = fs.statSync(filePath);
    // Encrypted here (older uploads / older apps): serve the plain bytes, any range.
    const sealedHere = !meta.e2e && AtRest.isChunked(AtRest.fileHead(filePath));
    const fileSize = sealedHere ? AtRest.plainSizeOfChunked(stat.size) : stat.size;
    const readRange = (start, end) => sealedHere ? AtRest.openChunkedRange(filePath, start, end)
                                                 : fs.createReadStream(filePath, { start, end });
    const safeName = encodeURIComponent(meta.originalName).replace(/'/g, "%27");
    const rangeHeader = req.headers['range'];

    if (rangeHeader) {
      // HTTP 206 Partial Content -------- required for video seeking and progressive streaming
      const parts = rangeHeader.replace(/bytes=/, '').split(',')[0].split('-');
      let start, end;
      if (parts[0] === '' && parts[1]) {            // suffix range: last N bytes
        start = Math.max(0, fileSize - parseInt(parts[1], 10)); end = fileSize - 1;
      } else {
        start = parseInt(parts[0], 10);
        // A range past the end is clamped (per HTTP spec), e.g. "first 2 MB" of a 1 MB file.
        end = parts[1] ? Math.min(parseInt(parts[1], 10), fileSize - 1) : fileSize - 1;
      }
      if (isNaN(start) || isNaN(end) || start >= fileSize || start > end) {
        res.writeHead(416, { 'Content-Range': `bytes */${fileSize}` });
        res.end();
        return;
      }
      const chunkSize = end - start + 1;
      res.writeHead(206, {
        'Content-Range':  `bytes ${start}-${end}/${fileSize}`,
        'Accept-Ranges':  'bytes',
        'Content-Length': chunkSize,
        'Content-Type':   meta.mimeType,
        // Let clients (esp. the web video element) cache media so pre-fetched videos
        // play instantly. Private -------- these URLs are authenticated per user.
        'Cache-Control':  'private, max-age=86400',
      });
      readRange(start, end).on('error', () => { try { res.destroy(); } catch (_) {} }).pipe(res);
    } else {
      // Full file -------- also advertise range support so clients know they can seek
      res.writeHead(200, {
        'Content-Type':        meta.mimeType,
        'Content-Length':      fileSize,
        'Accept-Ranges':       'bytes',
        'Cache-Control':       'private, max-age=86400',
        'Content-Disposition': `attachment; filename*=UTF-8''${safeName}`
      });
      if (fileSize > 0) readRange(0, fileSize - 1).on('error', () => { try { res.destroy(); } catch (_) {} }).pipe(res);
      else res.end();
    }
    return;
  }

  // ---------------- GET /files/:userA/:userB  ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  const listMatch = base.match(/^\/files\/([^/]+)\/([^/]+)$/);
  if (req.method === 'GET' && listMatch) {
    let userA = '', userB = '';
    try { userA = urlName(listMatch[1]); userB = urlName(listMatch[2]); } catch (_) {}
    const auth = httpAuth(req, qs);
    if (!auth) { jsonResponse(res, 401, { error: 'Unauthorized' }); return; }
    const requester = auth.username;
    // A group's files: every member's uploads (it only listed your own before).
    const groupId = groups[userB] ? userB : (groups[userA] ? userA : null);
    if (groupId ? !isGroupMember(requester, groupId) : (requester !== userA && requester !== userB)) {
      jsonResponse(res, 403, { error: 'Forbidden' }); return;
    }

    try {
      // From the in-memory index (it re-read every upload's details file on every request).
      const files = uploadIndexAll()
        .filter(m => m && (groupId ? m.conversationWith === groupId : (
          (m.uploadedBy === userA && m.conversationWith === userB) ||
          (m.uploadedBy === userB && m.conversationWith === userA)
        )))
        .sort((a, b) => new Date(a.uploadedAt) - new Date(b.uploadedAt));

      jsonResponse(res, 200, { files });
    } catch (e) {
      jsonResponse(res, 500, { error: 'Server error' });
    }
    return;
  }

  // ---------------- PUT /profile_pic  ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  // Upload the authenticated user's profile picture or short video.
  // Stored as data/profile_pics/{username}.{ext} and broadcast to mutual friends.
  if (req.method === 'PUT' && base === '/profile_pic') {
    const auth        = httpAuth(req, qs);
    const uploaderUsr = auth ? auth.username : '';

    if (!uploaderUsr) {
      jsonResponse(res, 401, { error: 'Unauthorized' }); return;
    }

    const headerMime = req.headers['x-mime-type'] || req.headers['content-type'] || '';
    const chunks   = [];
    let   size     = 0;
    let   tooBig   = false;
    const HARD_MAX = 12 * 1024 * 1024;

    req.on('data', chunk => {
      size += chunk.length;
      if (size > HARD_MAX) {
        tooBig = true;
        req.destroy();
        if (!res.writableEnded) jsonResponse(res, 413, { error: 'Profile media too large (max 12 MB video / 5 MB image)' });
      } else {
        chunks.push(chunk);
      }
    });

    req.on('end', () => {
      if (res.writableEnded || tooBig) return;
      try {
        const buf = Buffer.concat(chunks);
        const mime = resolveMediaMime(headerMime, buf);
        if (!mime || !MIME_TO_EXT[mime]) {
          jsonResponse(res, 415, { error: 'Unsupported media type' }); return;
        }
        const isVideo = mime.startsWith('video/');
        const max = isVideo ? 12 * 1024 * 1024 : 5 * 1024 * 1024;
        if (buf.length > max) {
          jsonResponse(res, 413, { error: isVideo ? 'Profile video too large (max 12 MB)' : 'Profile picture too large (max 5 MB)' });
          return;
        }
        const ext = MIME_TO_EXT[mime];
        const filePath = path.join(PROFILE_PICS_DIR, uploaderUsr + '.' + ext);
        AtRest.writeFile(filePath, buf);
        removeUserMediaSiblings(PROFILE_PICS_DIR, uploaderUsr, ext);
        console.log(`[PROFILE PIC] ${uploaderUsr} updated profile media (${mime})`);
        for (const friend of (friends[uploaderUsr] || [])) {
          if (areMutualFriends(uploaderUsr, friend)) {
            ((ash) => { const payload = { type: 'profile_updated', name: uploaderUsr, hasPic: true }; if (ash) payload.avatarShape = ash; sendToUser(friend, payload); })(storedAvatarShape(uploaderUsr));
          }
        }
        // The uploader's other devices refresh their own picture too.
        sendToUser(uploaderUsr, { type: 'profile_updated', name: uploaderUsr, hasPic: true });
        jsonResponse(res, 200, { ok: true, mime });
      } catch (e) {
        console.error('[PROFILE PIC UPLOAD ERROR]', e.message);
        jsonResponse(res, 500, { error: 'Server error' });
      }
    });

    req.on('error', err => {
      if (!res.writableEnded) jsonResponse(res, 500, { error: 'Upload aborted' });
    });
    return;
  }

  // ---------------- GET /profile_pic/:username  ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  // Download a user's profile picture/video. Requires auth (must be a mutual friend or the user themselves).
  const profilePicMatch = base.match(/^\/profile_pic\/([^/]+)$/);
  if (req.method === 'GET' && profilePicMatch) {
    const targetUser = urlName(profilePicMatch[1]);
    const auth = httpAuth(req, qs);
    if (!auth) { jsonResponse(res, 401, { error: 'Unauthorized' }); return; }
    const requester = auth.username;
    if (requester !== targetUser && !areMutualFriends(requester, targetUser)) {
      jsonResponse(res, 403, { error: 'Forbidden' }); return;
    }

    const found = findUserMediaFile(PROFILE_PICS_DIR, targetUser);
    if (!found) {
      jsonResponse(res, 404, { error: 'No profile picture' }); return;
    }
    serveMediaFile(req, res, found.path, found.mime);
    return;
  }

  // ---------------- PUT /banner_pic  ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  // Upload the authenticated user's profile banner (image or video).
  // Stored as data/banner_pics/{username}.{ext} and broadcast to mutual friends
  // (unless the user has bannerHiddenFromContacts set in their settings).
  if (req.method === 'PUT' && base === '/banner_pic') {
    const auth        = httpAuth(req, qs);
    const uploaderUsr = auth ? auth.username : '';

    if (!uploaderUsr) {
      jsonResponse(res, 401, { error: 'Unauthorized' }); return;
    }

    const headerMime = req.headers['x-mime-type'] || req.headers['content-type'] || '';
    const chunks   = [];
    let   size     = 0;
    let   tooBig   = false;
    const HARD_MAX = 25 * 1024 * 1024;

    req.on('data', chunk => {
      size += chunk.length;
      if (size > HARD_MAX) {
        tooBig = true;
        req.destroy();
        if (!res.writableEnded) jsonResponse(res, 413, { error: 'Banner media too large (max 25 MB video / 8 MB image)' });
      } else {
        chunks.push(chunk);
      }
    });

    req.on('end', () => {
      if (res.writableEnded || tooBig) return;
      try {
        const buf = Buffer.concat(chunks);
        const mime = resolveMediaMime(headerMime, buf);
        if (!mime || !MIME_TO_EXT[mime]) {
          jsonResponse(res, 415, { error: 'Unsupported media type' }); return;
        }
        const isVideo = mime.startsWith('video/');
        const max = isVideo ? 25 * 1024 * 1024 : 8 * 1024 * 1024;
        if (buf.length > max) {
          jsonResponse(res, 413, { error: isVideo ? 'Banner video too large (max 25 MB)' : 'Banner picture too large (max 8 MB)' });
          return;
        }
        const ext = MIME_TO_EXT[mime];
        const filePath = path.join(BANNER_PICS_DIR, uploaderUsr + '.' + ext);
        AtRest.writeFile(filePath, buf);
        removeUserMediaSiblings(BANNER_PICS_DIR, uploaderUsr, ext);
        console.log(`[BANNER PIC] ${uploaderUsr} updated banner media (${mime})`);
        const hidden = users[uploaderUsr]?.settings?.bannerHiddenFromContacts;
        if (!hidden) {
          for (const friend of (friends[uploaderUsr] || [])) {
            if (areMutualFriends(uploaderUsr, friend)) {
              sendToUser(friend, { type: 'banner_updated', name: uploaderUsr });
            }
          }
        }
        jsonResponse(res, 200, { ok: true, mime });
      } catch (e) {
        console.error('[BANNER PIC UPLOAD ERROR]', e.message);
        jsonResponse(res, 500, { error: 'Server error' });
      }
    });

    req.on('error', () => {
      if (!res.writableEnded) jsonResponse(res, 500, { error: 'Upload aborted' });
    });
    return;
  }

  // ---------------- GET /banner_pic/:username  --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  // Download a user's banner picture/video. Requires auth (must be a mutual friend or the user themselves).
  // Returns 403 if the target has bannerHiddenFromContacts and requester is not self.
  const bannerPicMatch = base.match(/^\/banner_pic\/([^/]+)$/);
  if (req.method === 'GET' && bannerPicMatch) {
    const targetUser = urlName(bannerPicMatch[1]);
    const auth = httpAuth(req, qs);
    if (!auth) { jsonResponse(res, 401, { error: 'Unauthorized' }); return; }
    const requester = auth.username;
    if (requester !== targetUser && !areMutualFriends(requester, targetUser)) {
      jsonResponse(res, 403, { error: 'Forbidden' }); return;
    }
    if (requester !== targetUser && users[targetUser]?.settings?.bannerHiddenFromContacts) {
      jsonResponse(res, 403, { error: 'Banner hidden' }); return;
    }

    const found = findUserMediaFile(BANNER_PICS_DIR, targetUser);
    if (!found) {
      jsonResponse(res, 404, { error: 'No banner picture' }); return;
    }
    serveMediaFile(req, res, found.path, found.mime);
    return;
  }

  // ---------------- GET /web -------- browser messaging client --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  // Serves webclient.html from the server folder. The page connects back to
  // this same host/port over WebSocket, so no extra configuration is needed.
  // Shared frame drawing code (the app ships its own copy in assets/frames.js)
  if (req.method === 'GET' && (base === '/web/frames.js' || base === '/frames.js')) {
    const fp = path.join(__dirname, 'frames.js');
    if (fs.existsSync(fp)) {
      res.writeHead(200, { 'Content-Type': 'application/javascript; charset=utf-8', 'Cache-Control': 'no-cache' });
      fs.createReadStream(fp).pipe(res);
    } else jsonResponse(res, 404, { error: 'frames.js not found' });
    return;
  }

  if (req.method === 'GET' && (base === '/web' || base === '/web/' || base === '/')) {
    const webPath = path.join(__dirname, 'webclient.html');
    if (fs.existsSync(webPath)) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' });
      fs.createReadStream(webPath).pipe(res);
    } else {
      jsonResponse(res, 404, { error: 'webclient.html not found on server' });
    }
    return;
  }

  // 404 default
  jsonResponse(res, 404, { error: 'Not found' });
});

// ============================================================
//  WEBSOCKET SERVER (attached to the HTTP server)
// ============================================================
const wss = new WebSocket.Server({ server: httpServer, maxPayload: 64 * 1024 * 1024 });

// Heartbeat: drop connections that stopped answering (phone lost signal, app killed, laptop
// slept). Without this a dead connection kept people "online" for a long time afterwards.
setInterval(() => {
  wss.clients.forEach((sock) => {
    if (sock.isAlive === false) { try { sock.terminate(); } catch (_) {} return; }
    sock.isAlive = false;
    try { sock.ping(); } catch (_) {}
  });
}, 30000);

wss.on('connection', (ws, req) => {
  const ip = req.socket.remoteAddress;
  // Every socket = one device; calls are routed to the exact device that answered.
  ws.deviceId = crypto.randomUUID();
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  ws.publicHost = String(req.headers['x-forwarded-host'] || req.headers.host || '').split(':')[0];
  ws.upgradeReq = { headers: req.headers, socket: { encrypted: !!(req.socket && req.socket.encrypted) } };   // learned only after a real sign in

  if (AtRest.locked()) {
    try { ws.send(JSON.stringify({ type: 'error', code: 'server_locked', message: 'Server is locked' })); } catch (_) {}
    ws.close();
    return;
  }
  if (isBanned(ip)) {
    console.log(`[BLOCKED] Banned IP: ${ip}`);
    ws.close();
    return;
  }

  let authenticated = false;
  let username = null;
  let pendingChallenge = null;   // { username, nonce, loginMsg } — set during RSA challenge

  console.log(`[CONNECT] New connection from ${ip}`);

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }

    // ---- AUTH must be first ----
    if (!authenticated) {
      if (msg.type !== 'auth') {
        send(ws, { type: 'error', message: 'Send auth first' });
        ws.close();
        return;
      }
      // A device's own sign-in code (from an earlier login) works instead of the password,
      // so browsers don't have to keep the password stored.
      if (msg.token && userForToken(String(msg.token).slice(0, 128))) {
        authenticated = true;
        send(ws, { type: 'auth_ok' });
        return;
      }
      if (!checkServerPassword(msg.password)) {
        recordFailedAuth(ip);
        console.log(`[AUTH FAIL] Wrong password from ${ip}`);
        send(ws, { type: 'error', message: 'Wrong server password' });
        ws.close();
        return;
      }
      authenticated = true;
      console.log(`[AUTH OK] ${ip}`);
      send(ws, { type: 'auth_ok' });
      return;
    }

    if (username && panicLocked(username) && !PANIC_IN_OK.has(msg.type)) return;

    switch (msg.type) {

      case 'panic_set': {
        // Phrases are set once for the account and shared by all its devices. The client sends
        // only salted hashes: SHA-256 of the lock phrase (devices spot it as it's typed or said)
        // and PBKDF2 of the unlock phrase (checked only here, never sent to devices).
        if (!username || !users[username]) return;
        const p = msg.panic || {};
        const str = (v, n) => typeof v === 'string' && v.length > 0 && v.length <= n;
        const num = (v, lo, hi) => Number.isInteger(v) && v >= lo && v <= hi;
        const cur = users[username].panic;
        if (cur && !(Array.isArray(msg.proofs) && msg.proofs.some(x => safeEq(String(x), cur.unlockHash)))) {
          send(ws, { type: 'panic_set_result', ok: false, error: 'Enter the current unlock phrase to change the phrases' }); return;
        }
        if (msg.clear) {
          delete users[username].panic;
          saveJSON(USERS_FILE, users);
          sendToUser(username, { type: 'panic_config', panic: null });
          send(ws, { type: 'panic_set_result', ok: true, cleared: true });
          return;
        }
        if (!str(p.lockSalt, 64) || !str(p.lockHash, 64) || !num(p.lockLen, 4, 500) || !num(p.lockWords, 1, 60) ||
            !str(p.unlockSalt, 64) || !str(p.unlockHash, 64) || !num(p.unlockIter, 50000, 2000000) || !num(p.unlockWords, 1, 60)) {
          send(ws, { type: 'panic_set_result', ok: false, error: 'Bad phrase data' }); return;
        }
        users[username].panic = { lockSalt: p.lockSalt, lockHash: p.lockHash, lockLen: p.lockLen, lockWords: p.lockWords,
          unlockSalt: p.unlockSalt, unlockHash: p.unlockHash, unlockIter: p.unlockIter, unlockWords: p.unlockWords,
          setAt: new Date().toISOString() };
        saveJSON(USERS_FILE, users);
        sendToUser(username, { type: 'panic_config', panic: publicPanicConfig(username) });
        send(ws, { type: 'panic_set_result', ok: true });
        console.log(`[PANIC] ${username} set panic phrases`);
        return;
      }

      case 'panic_lock': {
        if (!username || !users[username] || !users[username].panic) return;
        if (!users[username].panicLocked) {
          users[username].panicLocked = true;
          users[username].panicLockedAt = new Date().toISOString();
          saveJSON(USERS_FILE, users);
          console.log(`[PANIC] ${username} locked the account`);
          notifyOnlineStatus(username, false);
        }
        // Every device of this account locks now (phone, web, anything signed in).
        sendToUser(username, { type: 'account_locked' });
        // ...and the chats with this person lock on everyone else's devices too.
        for (const p of chatPeersOf(username)) sendToUser(p, { type: 'peer_locked', name: username });
        // Optionally the whole server too (Vault page: chosen accounts, master password set).
        // The data key is kept in memory wrapped by this account's unlock phrase (its PBKDF2 hash,
        // which only a device that hears the phrase can produce), so saying the unlock phrase
        // opens the server again without a restart or the master password.
        try {
          if (AtRest.status().hasPassword && panicLockUsers().includes(username) && users[username].panic) {
            const wrapped = AtRest.wrapKeyFor(panicKeyFrom(users[username].panic.unlockHash));
            setTimeout(() => { lockServer('panic phrase from ' + username); panicServerKeys.set(username, wrapped); }, 800);
          }
        } catch (e) { console.error('[PANIC] server lock failed', e.message); }
        return;
      }

      case 'panic_unlock': {
        if (!username || !users[username]) return;
        const pc = users[username].panic;
        const t = panicTries.get(username) || { next: 0, fails: 0 };
        const now = Date.now();
        if (now < t.next) { send(ws, { type: 'panic_unlock_result', ok: false, wait: t.next - now }); return; }
        const proofs = Array.isArray(msg.proofs) ? msg.proofs.slice(0, 16).map(String) : [];
        const ok = !!pc && proofs.some(x => safeEq(x, pc.unlockHash));
        if (!ok) {
          t.fails++;
          t.next = now + (t.fails >= 30 ? 60000 : 1500);   // slows guessing (typing mistakes count too)
          panicTries.set(username, t);
          send(ws, { type: 'panic_unlock_result', ok: false });
          return;
        }
        panicTries.delete(username);
        users[username].panicLocked = false;
        delete users[username].panicLockedAt;
        saveJSON(USERS_FILE, users);
        console.log(`[PANIC] ${username} unlocked the account`);
        sendToUser(username, { type: 'account_unlocked' });   // devices reconnect and sync
        for (const p of chatPeersOf(username)) sendToUser(p, { type: 'peer_unlocked', name: username });
        return;
      }

      case 'register': {
        const name = (msg.name || '').trim();
        const publicKey = msg.publicKey;
        if (!name || !publicKey) return;
        if (users[name]) { send(ws, { type: 'error', message: 'Username already taken' }); return; }
        if (!validNewUsername(name)) {
          send(ws, { type: 'error', message: 'Usernames are 2-24 letters, numbers, spaces, dots, dashes or underscores' }); return;
        }
        // If this name was previously wiped/deleted, clear the entry so the new account is clean
        if (wiped[name]) { delete wiped[name]; saveJSON(WIPED_FILE, wiped); }
        users[name] = { publicKey, registeredAt: new Date().toISOString(), signupName: name, displayName: name };
        saveJSON(USERS_FILE, users);
        username = name;
        addConnection(username, ws);
        connectionIPs.set(username, ip);
        console.log(`[REGISTER] ${username}`);
        if (ws.upgradeReq) learnPublicOrigin(ws.upgradeReq);
        send(ws, { type: 'register_ok', name: username, settings: null, httpToken: mintHttpToken(username), shareUrl: shareLink() });
        send(ws, { type: 'contacts_list', contacts: [] }); // new account always starts empty
        notifyOnlineStatus(username, true);
        break;
      }

      case 'login': {
        ws.loginActive = msg.active;
        ws.prevHttpToken = typeof msg.httpToken === 'string' ? msg.httpToken.slice(0, 128) : null;
        const name = (msg.name || '').trim();
        if (users[name] && users[name].system) { send(ws, { type: 'login_error', message: 'System account' }); return; }
        if (!name || !users[name]) { send(ws, { type: 'error', message: 'User not found' }); return; }
        // If this account was wiped, tell the client to clear local data (one-time UX signal),
        // then free the username so anyone can register it fresh going forward.
        if (wiped[name]) {
          send(ws, { type: 'account_wiped' });
          delete wiped[name];
          saveJSON(WIPED_FILE, wiped);
          ws.close();
          return;
        }
        const storedPubKey = users[name].publicKey;
        // SECURITY: the challenge used to be made with whatever NEW key the client sent, so
        // anyone with the app password could log in as anyone by name with their own key -
        // and the server then swapped in that key and sent it to all friends (account
        // takeover: future messages encrypted to the attacker). Now the stored key decides.
        // A genuinely new device key (reinstall without the identity key) is only accepted
        // while the admin has allowed it for this account (admin panel, 15 minutes).
        const resetAllowed = (users[name].allowKeyResetUntil || 0) > Date.now();
        if (msg.publicKey && storedPubKey && msg.publicKey !== storedPubKey && !resetAllowed) {
          console.log(`[KEY MISMATCH] login for ${name} with a different key from ${ip} (refused)`);
          send(ws, { type: 'login_error', code: 'key_mismatch',
                     message: "This account is secured by its key on your other device. Sign in there, or ask the server admin to allow a new device for 15 minutes, then try again." });
          ws.close();
          return;
        }
        const challengeKey = (resetAllowed && msg.publicKey) ? msg.publicKey : (storedPubKey || msg.publicKey);
        if (!challengeKey) {
          send(ws, { type: 'error', message: 'No public key available for this account' });
          ws.close();
          return;
        }
        // RSA challenge-response: send a random nonce as plaintext.
        // The client signs it with SHA256withRSA (their private key) and returns the signature.
        // We verify the signature against the challenge key, proving private key ownership
        // without any cross-provider OAEP parameter ambiguity.
        const nonce = crypto.randomBytes(32);
        pendingChallenge = { username: name, nonce, loginMsg: msg, challengeKey };
        send(ws, { type: 'auth_challenge', nonce: nonce.toString('base64') });
        console.log(`[CHALLENGE] Issued sign challenge to ${name}${msg.publicKey !== storedPubKey ? ' (using new client key)' : ''}`);
        break;
      }

      case 'auth_response': {
        if (!pendingChallenge) {
          send(ws, { type: 'error', message: 'No pending challenge' });
          ws.close();
          return;
        }
        const { username: challengeUser, nonce, loginMsg, challengeKey } = pendingChallenge;
        pendingChallenge = null;

        // Verify the RSA signature the client sent back.
        const sigBase64 = msg.signature || '';
        let sigValid = false;
        try {
          const keyDer    = Buffer.from(challengeKey, 'base64');
          const publicKey = crypto.createPublicKey({ key: keyDer, format: 'der', type: 'spki' });
          const verifier  = crypto.createVerify('SHA256');
          verifier.update(nonce);
          sigValid = verifier.verify(publicKey, Buffer.from(sigBase64, 'base64'));
        } catch (e) {
          console.error('[AUTH FAIL] Signature verification error:', e.message);
        }
        if (!sigValid) {
          recordFailedAuth(ip);
          console.log(`[AUTH FAIL] Bad signature from ${ip} for ${challengeUser}`);
          send(ws, { type: 'login_error', message: 'Authentication failed: incorrect key' });
          ws.close();
          return;
        }

        // Challenge passed -------- complete login
        username = challengeUser;
        addConnection(username, ws);
        connectionIPs.set(username, ip);

        // New device key (only possible while the admin allowed a reset): store it now that
        // the client proved it holds the matching private key, and close the window.
        if (loginMsg.publicKey && loginMsg.publicKey !== users[username].publicKey && challengeKey === loginMsg.publicKey) {
          users[username].publicKey = loginMsg.publicKey;
          delete users[username].allowKeyResetUntil;
          saveJSON(USERS_FILE, users);
          console.log(`[KEY_UPDATE] ${username} rotated public key`);
          const myFriendsForKey = (friends[username] || []).filter(f => f !== username);
          for (const f of myFriendsForKey) {
            if (areMutualFriends(username, f) && connections.has(f)) {
              sendToUser(f, {
                type: 'public_key_updated',
                name: username,
                publicKey: loginMsg.publicKey
              });
            }
          }
        }

        console.log(`[LOGIN] ${username} (RSA verified)`);
        if (users[username] && !users[username].signupName) users[username].signupName = username;
        if (ws.upgradeReq) learnPublicOrigin(ws.upgradeReq);
        send(ws, { type: 'login_ok', name: username, settings: users[username]?.settings || null, shareUrl: shareLink(),
                   httpToken: mintHttpToken(username, ws.prevHttpToken),
          hasPic:    hasUserMedia(PROFILE_PICS_DIR, username),
          hasBanner: hasUserMedia(BANNER_PICS_DIR,  username),
          picVersion: mediaVersion(username),
          avatarShape: normalizeAvatarShape(users[username]?.settings?.avatarShape) || undefined,
          profileFrame: userFrame(username) || 'none', savedFrames: userSavedFrames(username),
          displayName: users[username]?.displayName || username,
          signupName: users[username]?.signupName || username,
          panic: publicPanicConfig(username), panicLocked: panicLocked(username),
          lockedPeers: lockedPeersFor(username) });
        if (panicLocked(username)) {
          // Stays locked on every device, fresh installs and new browsers included, until the
          // unlock phrase: nothing else is sent (no contacts, groups, requests, statuses).
          console.log(`[PANIC] ${username} signed in while locked`);
          break;
        }

        const removedContacts = users[username]?.removedContacts || [];
        const myFriends = (friends[username] || []).filter(f => f !== username && !removedContacts.includes(f));
        const publicKeys = {};
        const contactPics    = {};
        const contactBanners = {};
        const contactAvatarShapes = {};
        const contactFrames = {};
        const picVersions = {};
        myFriends.forEach(f => {
          const _pv = mediaVersion(f); if (_pv) picVersions[f] = _pv;
          const _fr = userFrame(f); if (_fr) contactFrames[f] = _fr;
          if (users[f]?.publicKey) publicKeys[f] = users[f].publicKey;
          contactPics[f] = hasUserMedia(PROFILE_PICS_DIR, f);
          const bannerHidden = users[f]?.settings?.bannerHiddenFromContacts;
          contactBanners[f] = !bannerHidden && hasUserMedia(BANNER_PICS_DIR, f);
          const _ash = normalizeAvatarShape(users[f]?.settings?.avatarShape);
          if (_ash) contactAvatarShapes[f] = _ash;
        });
        const displayNames = {};
        myFriends.forEach(f => { displayNames[f] = (users[f] && users[f].displayName) || f; });
        send(ws, { type: 'contacts_list', contacts: myFriends, publicKeys, contactPics, contactBanners, contactAvatarShapes, contactFrames, picVersions, displayNames });
        // Group memberships are the server's truth. Clients reconcile against this list so a
        // phone that lost its local group rows (reinstall / old update wipe) gets them back,
        // and groups the user left are removed.
        send(ws, {
          type: 'groups_list',
          groups: Object.entries(groups)
            .filter(([, g]) => g && Array.isArray(g.members) && g.members.includes(username))
            .map(([gid, g]) => ({ groupId: gid, name: g.name || 'Group', members: g.members,
                                  createdBy: g.createdBy || '', createdAt: g.createdAt || '',
                                  memberKeys: groupMemberKeys(gid) }))
        });
        for (const r of (pending[username] || [])) {
          send(ws, { type: 'friend_request', from: r, to: username });
        }
        for (const friend of (friends[username] || [])) {
          if (areMutualFriends(username, friend)) {
            send(ws, buildOnlineStatusMsg(friend, isUserActive(friend)));
          }
        }
        notifyOnlineStatus(username, true);
        break;
      }

      case 'check_username': {
        // Check if a username is available before the user commits to a change.
        // Returns { type: 'username_check_result', available: bool, name: string }
        if (!username) return;
        const checkName = (msg.name || '').trim();
        const available = validNewUsername(checkName) &&
                          checkName !== username &&
                          !users[checkName];
        send(ws, { type: 'username_check_result', name: checkName, available });
        break;
      }

      case 'search': {
        if (!username) return;
        const query = (msg.query || '').trim();
        if (!query || query === username) { send(ws, { type: 'search_result', found: false }); return; }
        const target = users[query];
        if (target && target.discoverable !== false) {
          send(ws, { type: 'search_result', found: true, name: query, publicKey: target.publicKey });
        } else {
          send(ws, { type: 'search_result', found: false });
        }
        break;
      }

      case 'set_discoverable': {
        if (!username || !users[username]) return;
        users[username].discoverable = msg.discoverable === true;
        saveJSON(USERS_FILE, users);
        break;
      }

      case 'friend_request': {
        if (!username) return;
        const to = msg.to;
        if (!to || !users[to] || to === username || areMutualFriends(username, to)) return;
        if (!pending[to]) pending[to] = [];
        if (!pending[to].includes(username)) { pending[to].push(username); saveJSON(PENDING_FILE, pending); }
        console.log(`[FRIEND REQUEST] ${username} -> ${to}`);
        sendToUser(to, { type: 'friend_request', from: username, to });
        break;
      }

      case 'friend_response': {
        if (!username) return;
        const requester = msg.to;
        const accepted  = msg.accepted === true;
        // Only answer a request that was actually made: "accept" used to make ANYONE a mutual
        // friend of anyone (messages, presence, pictures) without asking them.
        if (!requester || !(pending[username] || []).includes(requester) || !users[requester]) {
          if (requester) send(ws, { type: 'friend_declined', name: requester });
          break;
        }
        if (pending[username]) {
          pending[username] = pending[username].filter(r => r !== requester);
          saveJSON(PENDING_FILE, pending);
        }
        if (accepted) {
          if (!friends[username])  friends[username]  = [];
          if (!friends[requester]) friends[requester] = [];
          if (!friends[username].includes(requester))  friends[username].push(requester);
          if (!friends[requester].includes(username))  friends[requester].push(username);
          saveJSON(FRIENDS_FILE, friends);
          console.log(`[FRIENDS] ${username} <-> ${requester}`);
          // Include each party's public key so they can start E2E-encrypted messaging immediately
          send(ws, { type: 'friend_accepted', name: requester, publicKey: users[requester]?.publicKey || null });
          sendToUser(requester, { type: 'friend_accepted', name: username, publicKey: users[username]?.publicKey || null });
          send(ws, { type: 'contacts_list', contacts: friends[username] });
          sendToUser(requester, { type: 'contacts_list', contacts: friends[requester] });
          // New friends see each other's profile frame straight away
          { const fa = userFrame(requester), fb = userFrame(username);
            if (fa) send(ws, { type: 'profile_updated', name: requester, frame: fa });
            if (fb) sendToUser(requester, { type: 'profile_updated', name: username, frame: fb }); }
          send(ws, buildOnlineStatusMsg(requester, isUserActive(requester)));
          sendToUser(requester, buildOnlineStatusMsg(username, true));
        } else {
          sendToUser(requester, { type: 'friend_declined', name: username });
        }
        break;
      }

      // ---------------- Contact removal ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
      // Sent when the user deletes or blocks a contact. Removes them from the
      // server-side friends list on BOTH sides so they won't reappear on login.
      case 'remove_contact': {
        if (!username) break;
        const target = msg.target;
        if (!target) break;
        // Remove target from this user's list
        if (friends[username]) {
          friends[username] = friends[username].filter(f => f !== target);
          if (friends[username].length === 0) delete friends[username];
        }
        // Remove this user from target's list (symmetric unfriend)
        if (friends[target]) {
          friends[target] = friends[target].filter(f => f !== username);
          if (friends[target].length === 0) delete friends[target];
        }
        saveJSON(FRIENDS_FILE, friends);
        // Persist the removal so reinstalls don't resurrect deleted contacts.
        // Store in users[username].removedContacts -------- filtered out at login.
        if (users[username]) {
          if (!users[username].removedContacts) users[username].removedContacts = [];
          if (!users[username].removedContacts.includes(target)) {
            users[username].removedContacts.push(target);
          }
          saveJSON(USERS_FILE, users);
        }
        console.log(`[REMOVE_CONTACT] ${username} removed ${target}`);
        send(ws, { type: 'remove_contact_ok', name: target });
        // Notify the target (if online) so they also delete the conversation
        sendToUser(target, { type: 'contact_removed_by_peer', name: username });
        break;
      }

      // ---------------- Settings sync ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
      // Client pushes its current settings whenever a preference changes.
      // Server stores them in users.json so they can be restored on any device.
      case 'save_settings': {
        if (!username || !users[username]) break;
        if (msg.settings && typeof msg.settings === 'object') {
          const prev = users[username].settings || {};
          const incoming = msg.settings;
          // Full replace keeps Android sync; preserve unknown prior keys only when
          // the client sends a sparse patch (few keys).
          const keys = Object.keys(incoming);
          if (keys.length && keys.length <= 4) {
            users[username].settings = Object.assign({}, prev, incoming);
          } else {
            users[username].settings = incoming;
          }
          // Normalize avatarShape if present; never invent a default key when absent
          if (Object.prototype.hasOwnProperty.call(incoming, 'avatarShape')) {
            const sh = normalizeAvatarShape(incoming.avatarShape);
            if (sh) users[username].settings.avatarShape = sh;
            else delete users[username].settings.avatarShape;
            broadcastAvatarShape(username);
          }
          // Normalize presenceMode only when client sends the key
          if (Object.prototype.hasOwnProperty.call(incoming, 'presenceMode')) {
            const pm = normalizePresenceMode(incoming.presenceMode);
            if (pm) users[username].settings.presenceMode = pm;
            else delete users[username].settings.presenceMode;
            notifyOnlineStatus(username, isUserActive(username));
          }
          saveJSON(USERS_FILE, users);
          // Other devices (the web app) learn about changes that matter to them (Notes lock)
          if (Object.prototype.hasOwnProperty.call(incoming, 'notesLock'))
            sendToOtherDevices(username, ws, { type: 'settings_updated', settings: { notesLock: users[username].settings.notesLock } });
        }
        break;
      }

      case 'set_presence': {
        if (!username || !users[username]) break;
        const pm = persistPresenceMode(username, msg.presence || msg.mode || msg.presenceMode);
        send(ws, { type: 'presence_ok', presence: pm || 'online' });
        notifyOnlineStatus(username, isUserActive(username));
        break;
      }

      case 'app_state': {
        // Device says whether the app is on screen / the tab is in use.
        if (!username) break;
        const wasActive = isUserActive(username);
        ws.isActive = msg.active !== false;
        const nowActive = isUserActive(username);
        if (wasActive && !nowActive) markLastSeen(username);
        if (wasActive !== nowActive) notifyOnlineStatus(username, nowActive);
        break;
      }

      case 'set_profile_frame': {
        if (!username || !users[username]) break;
        const fr = normalizeFrameId(msg.frame);
        const clear = msg.frame == null || msg.frame === '' || msg.frame === 'none' || (fr && fr.startsWith('gen:none'));
        if (!fr && !clear) break;                       // malformed: leave the current frame alone
        if (clear) delete users[username].profileFrame;
        else users[username].profileFrame = fr;
        saveJSON(USERS_FILE, users);
        const now = userFrame(username) || 'none';
        sendToUser(username, { type: 'profile_frame_ok', frame: now });     // every device of this user
        for (const f of (friends[username] || [])) {
          if (areMutualFriends(username, f)) sendToUser(f, { type: 'profile_updated', name: username, frame: now });
        }
        break;
      }

      case 'save_frame':
      case 'remove_saved_frame': {
        if (!username || !users[username]) break;
        const fr = normalizeFrameId(msg.frame);
        let list = userSavedFrames(username).filter(x => x !== fr);
        if (fr && msg.type === 'save_frame') list.unshift(fr);
        users[username].savedFrames = list.slice(0, 60);
        saveJSON(USERS_FILE, users);
        sendToUser(username, { type: 'saved_frames', frames: users[username].savedFrames });
        break;
      }

      case 'set_avatar_shape': {
        if (!username || !users[username]) break;
        if (!users[username].settings || typeof users[username].settings !== 'object') {
          users[username].settings = {};
        }
        const sh = normalizeAvatarShape(msg.avatarShape);
        if (sh) users[username].settings.avatarShape = sh;
        else delete users[username].settings.avatarShape;
        saveJSON(USERS_FILE, users);
        send(ws, { type: 'avatar_shape_ok', avatarShape: sh || 'circle' });
        broadcastAvatarShape(username);
        break;
      }

      case 'set_display_name': {
        if (!username || !users[username]) break;
        const dn = String(msg.displayName || '').trim().slice(0, 40);
        users[username].displayName = dn || username;
        saveJSON(USERS_FILE, users);
        send(ws, { type: 'display_name_ok', displayName: users[username].displayName });
        // The user's other devices (phone / other browser) update their own name too.
        sendToOtherDevices(username, ws, { type: 'profile_updated', name: username, displayName: users[username].displayName });
        const myFriendsDn = (friends[username] || []).filter(f => f !== username);
        for (const f of myFriendsDn) {
          ((ash) => { const payload = { type: 'profile_updated', name: username, displayName: users[username].displayName }; if (ash) payload.avatarShape = ash; sendToUser(f, payload); })(storedAvatarShape(username));
        }
        break;
      }

      case 'get_public_key': {
        if (!username) return;
        const target = msg.target;
        if (!target || !users[target] || !areMutualFriends(username, target)) return;
        // Send the requested key back to the requester
        send(ws, { type: 'public_key_response', name: target, publicKey: users[target].publicKey });
        // Also push the requester's current key to the target (if online).
        // This heals the stale-key scenario: if the requester reinstalled and got a
        // new keypair, the target's cached key is stale.  Sending public_key_updated
        // here ensures the target re-encrypts future messages with the correct key.
        if (users[username]?.publicKey && connections.has(target)) {
          sendToUser(target, {
            type: 'public_key_updated',
            name: username,
            publicKey: users[username].publicKey
          });
        }
        break;
      }

      case 'private_message': {
        if (!username) return;
        const to        = msg.to;
        const payload   = msg.payload;
        const messageId = String(msg.messageId || crypto.randomUUID()).slice(0, 64);
        if (!to || !payload || !canChat(username, to)) return;
        if (typeof payload !== 'string' || payload.length > MAX_PAYLOAD_CHARS) {
          send(ws, { type: 'error', message: 'Message too large' }); return;
        }
        if (!isE2EPayload(payload, false)) {
          send(ws, { type: 'message_rejected', messageId, reason: 'not_encrypted' });
          console.warn(`[E2E] refused an unencrypted message from ${username}`);
          return;
        }

        const history = loadHistory(username, to);
        // Idempotent resend: clients retry unacked messages after reconnects. If we already
        // stored this one, just re-ack it (no duplicate delivery or history entry).
        const dup = history.find(m => m.messageId === messageId);
        if (dup) { send(ws, { type: 'message_sent', messageId, timestamp: dup.timestamp }); break; }
        const timestamp = nextServerTimestamp(history);
        const message = { messageId, from: username, to, payload, timestamp };
        history.push(message);
        saveHistory(username, to, history);
        const self = to === username;              // Notes: only this account's other devices get it
        // Sending a message implies the sender has read the peer's earlier messages.
        if (!self) noteImpliedRead(username, to, timestamp);
        if (!self) sendToUser(to, { type: 'private_message', ...message });
        send(ws, { type: 'message_sent', messageId, timestamp });
        // Echo to the sender's OTHER devices (e.g. phone sees what was sent from the
        // web client). Uses a distinct type so existing clients safely ignore it.
        sendToOtherDevices(username, ws, { type: 'message_echo', ...message });
        break;
      }

      case 'history_request': {
        if (!username) return;
        const to = msg.to;
        if (!to || !canChat(username, to)) return;
        // Optional "since" (ISO time): only newer messages. Clients that already have the chat
        // ask for just what they missed instead of all 200 messages on every reconnect.
        const sinceMs = msg.since ? Date.parse(msg.since) : NaN;
        // The other person panic-locked: this chat stays closed until they unlock.
        if (panicLocked(to)) { send(ws, { type: 'history_response', with: to, messages: [], peerLocked: true }); break; }
        const all = loadHistory(username, to);
        send(ws, {
          type: 'history_response',
          with: to,
          messages: isNaN(sinceMs) ? all : all.filter(m => (Date.parse(m.timestamp) || 0) > sinceMs),
          ...peerReceiptSnapshot(username, to)
        });
        break;
      }

      case 'typing': {
        if (!username) return;
        const to = msg.to;
        if (!to || !areMutualFriends(username, to)) return;
        sendToUser(to, { type: 'typing', from: username, typing: msg.typing === true });
        break;
      }

      // ---------------- Voice / video call signaling --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
      case 'call_offer':
      case 'call_accept':
      case 'call_reject':
      case 'call_end': {
        if (!username) return;
        const to = msg.to;
        if (!to || !areMutualFriends(username, to)) return;
        const fwd = { type: msg.type, from: username, fromDevice: ws.deviceId, rtc: !!msg.rtc };
        if (msg.type === 'call_offer') {
          // Ring every device of the callee; each gets the connection helpers it needs.
          for (const sock of (connections.get(to) || [])) send(sock, { ...fwd, iceServers: iceServersFor(sock) });
          send(ws, { type: 'call_config', iceServers: iceServersFor(ws) });
        } else if (msg.type === 'call_accept') {
          sendToDevice(to, msg.toDevice, { ...fwd, iceServers: iceServersFor(ws) });
          // Stop the ringing on this person's other devices.
          sendToOtherDevices(username, ws, { type: 'call_answered_elsewhere', from: to });
        } else {
          sendToDevice(to, msg.toDevice, fwd);
          if (msg.type === 'call_reject') sendToOtherDevices(username, ws, { type: 'call_answered_elsewhere', from: to });
        }
        if (msg.type === 'call_accept') {
          activeCalls.set(username, to);
          activeCalls.set(to, username);
        } else if (msg.type === 'call_end' || msg.type === 'call_reject') {
          activeCalls.delete(username);
          activeCalls.delete(to);
        }
        break;
      }

      // ---------------- Real-time audio frame relay (WebSocket audio calling) ------------------------------------------------------------------------------------------------------------------------
      // WebRTC offer / answer / ICE candidates, device to device. Never stored or logged.
      case 'rtc_signal': {
        if (!username) return;
        const to = msg.to;
        if (!to || !areMutualFriends(username, to)) return;
        sendToDevice(to, msg.toDevice, { type: 'rtc_signal', from: username, fromDevice: ws.deviceId,
          kind: msg.kind, sdp: msg.sdp, candidate: msg.candidate, video: msg.video });
        break;
      }

      case 'audio_frame': {
        if (!username) return;
        const to = msg.to;
        if (!to || !areMutualFriends(username, to)) return;
        // Old relay calling: current apps use WebRTC and ignore these frames, so they are no
        // longer forwarded (they only wasted bandwidth).
        break;
      }

      // ---------------- Real-time video frame relay (WebSocket video calling) --------------------------------------------------------------------------------------------------------------------------------
      case 'video_frame': {
        if (!username) return;
        const to = msg.to;
        if (!to || !areMutualFriends(username, to)) return;
        // Old relay calling: no longer forwarded (see audio_frame).
        break;
      }

      // ---------------- Real-time video audio frame relay ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
      case 'video_audio_frame': {
        if (!username) return;
        const to = msg.to;
        if (!to || !areMutualFriends(username, to)) return;
        sendToUser(to, { type: 'video_audio_frame', from: username, data: msg.data });
        break;
      }

      // ---------------- Video call signaling (separate type so receiver can open VideoCallActivity) ----------------
      case 'video_call_offer':
      case 'video_call_accept':
      case 'video_call_reject':
      case 'video_call_end': {
        if (!username) return;
        const to = msg.to;
        if (!to || !areMutualFriends(username, to)) return;
        const fwd = { type: msg.type, from: username, fromDevice: ws.deviceId, rtc: !!msg.rtc };
        if (msg.type === 'video_call_offer') {
          for (const sock of (connections.get(to) || [])) send(sock, { ...fwd, iceServers: iceServersFor(sock) });
          send(ws, { type: 'call_config', iceServers: iceServersFor(ws) });
        } else if (msg.type === 'video_call_accept') {
          sendToDevice(to, msg.toDevice, { ...fwd, iceServers: iceServersFor(ws) });
          sendToOtherDevices(username, ws, { type: 'call_answered_elsewhere', from: to });
        } else {
          sendToDevice(to, msg.toDevice, fwd);
          if (msg.type === 'video_call_reject') sendToOtherDevices(username, ws, { type: 'call_answered_elsewhere', from: to });
        }
        // Track active calls so disconnect can notify partner
        if (msg.type === 'video_call_accept') {
          activeCalls.set(username, to);
          activeCalls.set(to, username);
        } else if (msg.type === 'video_call_end' || msg.type === 'video_call_reject') {
          activeCalls.delete(username);
          activeCalls.delete(to);
        }
        break;
      }

      case 'message_delivered': {
        if (!username) return;
        const to = msg.to;
        if (!to || !areMutualFriends(username, to)) return;
        notePeerReceipt(username, to, msg.messageId, 'delivered');
        sendToUser(to, { type: 'message_delivered', from: username, messageId: msg.messageId });
        break;
      }

      case 'read_receipt': {
        if (!username) return;
        const to = msg.to;
        if (!to || !areMutualFriends(username, to)) return;
        notePeerReceipt(username, to, msg.messageId, 'read');
        sendToUser(to, { type: 'read_receipt', from: username, messageId: msg.messageId });
        break;
      }

      // Own-device sync: I read a chat on this device — clear badge/notif on my other devices.
      case 'conversation_read': {
        if (!username) return;
        const to = msg.to || msg.with;
        if (!to) return;
        // Groups: membership check; 1:1: friends check
        if (String(to).startsWith('group:') || (groups[to] && groups[to].members)) {
          if (!groups[to] || !(groups[to].members || []).includes(username)) return;
        } else if (!areMutualFriends(username, to)) {
          return;
        }
        sendToOtherDevices(username, ws, {
          type: 'conversation_read',
          with: to,
          from: username
        });
        break;
      }

      case 'delete_message': {
        if (!username) return;
        const to        = msg.to;
        const messageId = msg.messageId;
        if (!to || !messageId) return;
        // Groups: delete your own message for every member (it was refused before: groups
        // aren't "friends", so the message stayed for everyone else).
        if (groups[to]) {
          if (!isGroupMember(username, to) || !msg.deleteForEveryone) return;
          const gh = loadGroupHistory(to);
          const target = gh.find(m => m.messageId === messageId);
          if (!target || target.from !== username) return;
          saveGroupHistory(to, gh.filter(m => m.messageId !== messageId));
          for (const m of groups[to].members) sendToUser(m, { type: 'message_deleted', messageId, groupId: to, by: username, forEveryone: true });
          break;
        }
        if (!canChat(username, to)) return;
        if (msg.deleteForEveryone || to === username) {      // Notes: a delete is always for good
          // Remove from server history and notify both sides. Only your OWN messages: anyone
          // could delete the other person's messages before.
          const history  = loadHistory(username, to);
          const target = history.find(m => m.messageId === messageId);
          if (target && target.from !== username) return;
          saveHistory(username, to, history.filter(m => m.messageId !== messageId));
          sendToUser(to, { type: 'message_deleted', messageId, by: username, forEveryone: true });
          // All of the sender's devices, not just this one (the phone kept showing it).
          sendToUser(username, { type: 'message_deleted', messageId, by: username, forEveryone: true });
        } else {
          // Delete for me only -------- just confirm to sender, don't touch history or notify recipient
          send(ws, { type: 'message_deleted', messageId, by: username, forEveryone: false });
        }
        break;
      }

      case 'wipe_notes': {
        // Notes: delete every note at once (only your own chat with yourself), on all your devices
        if (!username) return;
        saveHistory(username, username, []);
        sendToUser(username, { type: 'notes_wiped' });
        break;
      }

      case 'create_group': {
        if (!username) return;
        const groupId = crypto.randomUUID();
        const members = [username, ...(msg.members || []).filter(m => areMutualFriends(username, m))];
        const gName = String(msg.name || 'Group').trim().slice(0, 50) || 'Group';
        groups[groupId] = { name: gName, createdBy: username, members: Array.from(new Set(members)), createdAt: new Date().toISOString() };
        saveJSON(GROUPS_FILE, groups);
        console.log(`[GROUP] ${username} created "${groups[groupId].name}"`);
        for (const m of members) {
          sendToUser(m, { type: 'group_created', groupId, name: groups[groupId].name, members: groups[groupId].members, createdBy: username, timestamp: groups[groupId].createdAt, memberKeys: groupMemberKeys(groupId) });
        }
        break;
      }

      case 'group_message': {
        if (!username) return;
        const { groupId, payload } = msg;
        const messageId = String(msg.messageId || crypto.randomUUID()).slice(0, 64);
        if (!groupId || !payload || !groups[groupId]) return;
        if (typeof payload !== 'string' || payload.length > MAX_PAYLOAD_CHARS) {
          send(ws, { type: 'error', message: 'Message too large' }); return;
        }
        if (!isE2EPayload(payload, true)) {
          send(ws, { type: 'message_rejected', messageId, reason: 'not_encrypted' });
          console.warn(`[E2E] refused an unencrypted group message from ${username}`);
          return;
        }
        if (!groups[groupId].members.includes(username)) return;
        const history = loadGroupHistory(groupId);
        const dup = history.find(m => m.messageId === messageId);
        if (dup) { send(ws, { type: 'message_sent', messageId, timestamp: dup.timestamp }); break; }
        const timestamp = nextServerTimestamp(history);
        const message = { messageId, groupId, from: username, payload, timestamp };
        history.push(message);
        saveGroupHistory(groupId, history);
        for (const m of groups[groupId].members) {
          if (m !== username) sendToUser(m, { type: 'group_message', ...message });
        }
        send(ws, { type: 'message_sent', messageId, timestamp });
        // Echo to the sender's other devices (multi-device sync)
        sendToOtherDevices(username, ws, { type: 'message_echo', ...message });
        break;
      }

      case 'group_keys_request': {
        // Current member keys (a member's key can change; clients refresh before sending).
        if (!username) return;
        const gid = msg.groupId;
        if (!gid || !isGroupMember(username, gid)) return;
        send(ws, { type: 'group_keys', groupId: gid, keys: groupMemberKeys(gid) });
        break;
      }

      case 'group_history_request': {
        if (!username) return;
        const { groupId } = msg;
        if (!groupId || !groups[groupId] || !groups[groupId].members.includes(username)) return;
        // Optional "since" like history_request: only what this device is missing.
        const gSince = msg.since ? Date.parse(msg.since) : NaN;
        // Messages from panic-locked members are held back until they unlock.
        const gAll = loadGroupHistory(groupId).filter(m => !panicLocked(m.from));
        send(ws, { type: 'group_history_response', groupId,
                   messages: isNaN(gSince) ? gAll : gAll.filter(m => (Date.parse(m.timestamp) || 0) > gSince) });
        break;
      }

      case 'leave_group': {
        if (!username) return;
        const { groupId } = msg;
        if (!groupId || !groups[groupId]) return;
        if (!groups[groupId].members.includes(username)) return;
        // Remove user from group
        groups[groupId].members = groups[groupId].members.filter(m => m !== username);
        saveJSON(GROUPS_FILE, groups);
        console.log(`[GROUP] ${username} left "${groups[groupId].name}"`);
        // Broadcast system message to remaining members
        const sysPayload = JSON.stringify({ _sys: `${username} left the group` });
        const sysMessage = {
          messageId: crypto.randomUUID(),
          groupId,
          from: '',
          payload: sysPayload,
          timestamp: new Date().toISOString()
        };
        const history = loadGroupHistory(groupId);
        history.push(sysMessage);
        saveGroupHistory(groupId, history);
        for (const m of groups[groupId].members) {
          sendToUser(m, { type: 'group_message', ...sysMessage });
        }
        // Notify remaining members of updated member list
        for (const m of groups[groupId].members) {
          sendToUser(m, { type: 'group_updated', groupId, members: groups[groupId].members, memberKeys: groupMemberKeys(groupId) });
        }
        break;
      }

      case 'username_change': {
        if (!username) return;
        const newName = (msg.newName || '').trim();
        if (!newName || newName === username || users[newName] || !validNewUsername(newName)) {
          send(ws, { type: 'username_change_error', message: users[newName] ? 'Name already taken' : 'Usernames are 2-24 letters, numbers, spaces, dots, dashes or underscores' });
          return;
        }
        // Clone user record under new name
        users[newName] = { ...users[username], renamedFrom: username };
        delete users[username];
        // Update friends lists
        for (const u of Object.keys(friends)) {
          const idx = friends[u].indexOf(username);
          if (idx >= 0) friends[u][idx] = newName;
        }
        if (friends[username]) { friends[newName] = friends[username]; delete friends[username]; }
        // Update pending
        for (const u of Object.keys(pending)) {
          const idx = pending[u].indexOf(username);
          if (idx >= 0) pending[u][idx] = newName;
        }
        if (pending[username]) { pending[newName] = pending[username]; delete pending[username]; }
        // Rename all history files that reference the old username
        try {
          // Always the signed-in user's own name. It used to trust msg.oldName from the client,
          // which let someone rename ANOTHER user's chat files into their own.
          const oldName_local = username;
          const histFiles = fs.readdirSync(HISTORY_DIR);
          for (const f of histFiles) {
            if (!f.endsWith('.json') || f.startsWith('group__')) continue;
            // History filenames are "userA__userB.json" (sorted alphabetically)
            const base = f.replace('.json', '');
            const parts = base.split('__');
            if (parts.length === 2 && parts.includes(oldName_local)) {
              const otherUser = parts[0] === oldName_local ? parts[1] : parts[0];
              const newFileName = [newName, otherUser].sort().join('__') + '.json';
              const oldPath = path.join(HISTORY_DIR, f);
              const newPath = path.join(HISTORY_DIR, newFileName);
              if (oldPath !== newPath) fs.renameSync(oldPath, newPath);
            }
          }
        } catch (e) {
          console.error('[RENAME] Error renaming history files:', e.message);
        }
        // Rename receipt files the same way
        try {
          const oldRcpt = username;
          if (fs.existsSync(RECEIPTS_DIR)) {
            for (const f of fs.readdirSync(RECEIPTS_DIR)) {
              if (!f.endsWith('.json')) continue;
              const base = f.replace('.json', '');
              const parts = base.split('__');
              if (parts.length === 2 && parts.includes(oldRcpt)) {
                const otherUser = parts[0] === oldRcpt ? parts[1] : parts[0];
                const newFileName = [newName, otherUser].sort().join('__') + '.json';
                const oldPath = path.join(RECEIPTS_DIR, f);
                const newPath = path.join(RECEIPTS_DIR, newFileName);
                if (oldPath !== newPath) fs.renameSync(oldPath, newPath);
                try {
                  const data = loadJSON(newPath, {});
                  if (data[oldRcpt]) {
                    data[newName] = data[oldRcpt];
                    delete data[oldRcpt];
                    saveJSON(newPath, data);
                  }
                } catch (_) {}
              }
            }
          }
        } catch (e) {
          console.error('[RENAME] Error renaming receipt files:', e.message);
        }
        // Groups (membership, creator, message senders) and uploads follow the new name; they
        // didn't before, so a renamed user dropped out of every group.
        try {
          for (const [gid, g] of Object.entries(groups)) {
            if (!g) continue;
            let touched = false;
            if (Array.isArray(g.members) && g.members.includes(username)) { g.members = g.members.map(m => m === username ? newName : m); touched = true; }
            if (g.createdBy === username) { g.createdBy = newName; touched = true; }
            if (touched) {
              const gh = loadGroupHistory(gid);
              if (gh.some(m => m.from === username)) saveGroupHistory(gid, gh.map(m => m.from === username ? { ...m, from: newName } : m));
            }
          }
          saveJSON(GROUPS_FILE, groups);
          for (const m of uploadIndexAll()) {
            if (m.uploadedBy === username || m.conversationWith === username) {
              if (m.uploadedBy === username) m.uploadedBy = newName;
              if (m.conversationWith === username) m.conversationWith = newName;
              saveJSON(path.join(UPLOADS_DIR, m.fileId + '.meta.json'), m);
            }
          }
          for (const [sid, sess] of httpSessions) if (sess.username === username) sess.username = newName;
          if (connectionIPs.has(username)) { connectionIPs.set(newName, connectionIPs.get(username)); connectionIPs.delete(username); }
          if (lastSeenMap.has(username)) { lastSeenMap.set(newName, lastSeenMap.get(username)); lastSeenMap.delete(username); }
        } catch (e) {
          console.error('[RENAME] Error updating groups / uploads:', e.message);
        }
        // Rename profile pic and banner files (any supported ext)
        try {
          renameUserMedia(PROFILE_PICS_DIR, username, newName);
          renameUserMedia(BANNER_PICS_DIR, username, newName);
        } catch (e) {
          console.error('[RENAME] Error renaming picture files:', e.message);
        }
        saveJSON(USERS_FILE, users);
        saveJSON(FRIENDS_FILE, friends);
        saveJSON(PENDING_FILE, pending);
        // Re-register under new name -------- move ALL of this user's device sockets
        const socketSet = connections.get(username);
        connections.delete(username);
        const oldUsername = username;
        username = newName;
        if (socketSet) connections.set(username, socketSet);
        else addConnection(username, ws);
        send(ws, { type: 'username_change_ok', newName: username });
        // Notify mutual friends
        for (const friend of (friends[username] || [])) {
          if (areMutualFriends(username, friend)) {
            sendToUser(friend, { type: 'contact_renamed', oldName: oldUsername, newName: username });
          }
        }
        console.log(`[RENAME] ${oldUsername} -> ${newName}`);
        break;
      }

      case 'panic_wipe': {
        if (!username) return;
        // No console.log -------- user-initiated wipes leave zero trace in server logs.

        // Collect friends BEFORE deleting so we can notify them (ephemeral in-memory only)
        const wipeTargets = (friends[username] || []).filter(f => f !== username);

        // Erase all history files that mention this username
        try {
          // Exact names only: "includes" deleted OTHER people's chats whose names merely
          // contained this username (wiping "al" erased "alice__bob").
          for (const f of fs.readdirSync(HISTORY_DIR)) {
            const pair = chatFileUsers(f);
            if (pair && pair.includes(username)) fs.unlinkSync(path.join(HISTORY_DIR, f));
          }
          if (fs.existsSync(RECEIPTS_DIR)) for (const f of fs.readdirSync(RECEIPTS_DIR)) {
            const pair = chatFileUsers(f);
            if (pair && pair.includes(username)) fs.unlinkSync(path.join(RECEIPTS_DIR, f));
          }
          // Erase all uploads sent to or from this user
          for (const f of fs.readdirSync(UPLOADS_DIR)) {
            if (f.endsWith('.meta.json')) {
              const m = loadJSON(path.join(UPLOADS_DIR, f), null);
              if (m && (m.uploadedBy === username || m.conversationWith === username)) {
                fs.unlinkSync(path.join(UPLOADS_DIR, f));
                uploadIndexLoad().delete(m.fileId);
                const dataFile = path.join(UPLOADS_DIR, m.storedName);
                if (fs.existsSync(dataFile)) fs.unlinkSync(dataFile);
              }
            }
          }
        } catch (_) {}

        // Remove from all relationship maps
        for (const u of Object.keys(friends)) friends[u] = friends[u].filter(f => f !== username);
        delete friends[username]; saveJSON(FRIENDS_FILE, friends);
        for (const u of Object.keys(pending)) pending[u] = pending[u].filter(f => f !== username);
        delete pending[username]; saveJSON(PENDING_FILE, pending);
        for (const g of Object.keys(groups)) groups[g].members = groups[g].members.filter(m => m !== username);
        saveJSON(GROUPS_FILE, groups);

        // Erase profile pic and banner (any supported ext)
        try {
          deleteUserMedia(PROFILE_PICS_DIR, username);
          deleteUserMedia(BANNER_PICS_DIR, username);
        } catch (_) {}

        // Erase user record -------- this is the last step so the username leaves no trace on disk
        delete users[username]; saveJSON(USERS_FILE, users);

        // Do NOT write to wiped_accounts.json -------- that would leave a permanent record.
        // The username is now completely free for anyone to register.

        // Notify former friends in-memory only (ephemeral WebSocket push, nothing written to disk)
        for (const friend of wipeTargets) {
          sendToUser(friend, { type: 'contact_removed_by_peer', name: username });
        }

        // Confirm to the initiating client so it can clear its own local data.
        // Other devices of the same user get account_wiped so they also clear.
        send(ws, { type: 'wipe_complete' });
        sendToOtherDevices(username, ws, { type: 'account_wiped' });
        closeAllConnections(username);
        break;
      }

      default: break;
    }
  });

  ws.on('close', () => {
    if (username) {
      const wasActive = isUserActive(username);
      const fullyOffline = removeConnection(username, ws);
      if (fullyOffline) {
        connectionIPs.delete(username);
        if (wasActive) markLastSeen(username);
        console.log(`[DISCONNECT] ${username}`);
        notifyOnlineStatus(username, false);
      } else if (wasActive && !isUserActive(username)) {
        // The device that was in use went away; the others are only background connections.
        markLastSeen(username);
        notifyOnlineStatus(username, false);
      } else {
        console.log(`[DISCONNECT] ${username} (device -------- still online elsewhere)`);
      }
      // If this user was in an active video or audio call, notify their partner.
      // Calls are per-device, so end the call even if another device is still online.
      const callPartner = activeCalls.get(username);
      if (callPartner) {
        // Both call kinds (the voice one used a type no client handles, so a voice call
        // stayed "connected" on the other phone when this one dropped).
        sendToUser(callPartner, { type: 'video_call_end', from: username, rtc: true });
        sendToUser(callPartner, { type: 'call_end',       from: username, rtc: true });
        activeCalls.delete(username);
        activeCalls.delete(callPartner);
      }
    } else {
      console.log(`[DISCONNECT] Unauthenticated from ${ip}`);
    }
  });

  ws.on('error', err => console.error(`[WS ERROR] ${err.message}`));
});

// ============================================================
//  ADMIN PANEL
// ============================================================


const VAULT_HTML = `<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Silvershire Inn - Vault</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}body{background:#0d0d14;color:#d4d4d8;font-family:system-ui,sans-serif;font-size:14px;padding:24px}
.card{background:#13131f;border:1px solid #222;border-radius:12px;padding:20px;max-width:620px;margin:0 auto 16px}
h1{font-size:20px;color:#A8A9AD;margin-bottom:6px}h2{font-size:13px;color:#A8A9AD;text-transform:uppercase;letter-spacing:.08em;margin-bottom:10px}
p,.hint{color:#999;line-height:1.5;margin-bottom:10px}input{width:100%;background:#0d0d14;border:1px solid #333;border-radius:8px;padding:10px 12px;color:#ddd;font-size:14px;margin-bottom:10px}
button{cursor:pointer;border:none;border-radius:8px;padding:9px 14px;font-weight:600;background:#A8A9AD;color:#000;margin-right:8px}
button.danger{background:#3a1414;color:#f66}.ok{color:#4caf50}.bad{color:#f66}.mono{font-family:monospace;word-break:break-all;background:#0d0d14;padding:10px;border-radius:8px;border:1px solid #333;margin:8px 0}
a{color:#A8A9AD}
</style></head><body><div id="app">Loading...</div>
<script>
const $ = (id) => document.getElementById(id);
async function api(path, body) {
  const r = await fetch(path, body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  let j = {}; try { j = await r.json(); } catch (_) {}
  if (!r.ok) throw new Error(j.error || ('HTTP ' + r.status));
  return j;
}
function esc(s){ return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
async function login() {
  try { await api('/admin/login', { password: $('apw').value }); load(); } catch (e) { $('msg').textContent = e.message; }
}
async function load() {
  let s;
  try { s = await api('/admin/api/vault'); } catch (e) {
    $('app').innerHTML = '<div class="card"><h1>Vault</h1><p>Sign in with the admin password.</p><input type="password" id="apw" placeholder="Admin password" onkeydown="if(event.key===\\'Enter\\')login()"><button onclick="login()">Sign in</button><p class="bad" id="msg"></p></div>';
    return;
  }
  const lockedBox = s.locked ? '<div class="card"><h1 class="bad">Locked</h1><p>Nothing on this PC can be read and nobody can use the server until it is unlocked.</p>' +
      (s.error ? '<p class="bad">' + esc(s.error) + '</p>' : '') +
      '<input type="password" id="upw" placeholder="Master password" onkeydown="if(event.key===\\'Enter\\')unlock()"><button onclick="unlock()">Unlock</button>' +
      '<p class="hint" style="margin-top:14px">Moved to another PC or Windows account? Paste the recovery key instead:</p><input id="rk" placeholder="Recovery key"><button onclick="recover()">Use recovery key</button><p id="umsg" class="bad"></p></div>' : '';
  const openBox = s.locked ? '' :
    '<div class="card"><h1 class="ok">Unlocked</h1><p>Everything the server keeps on this PC is encrypted. ' +
    (s.windows ? 'The key is tied to this Windows account' : 'Not on Windows: the key file is NOT protected') +
    (s.hasPassword ? ' and your master password.' : '. No master password is set, so the server unlocks by itself when it starts.') + '</p>' +
    (s.migrating ? '<p class="hint">Encrypting existing files: ' + esc(s.migrating) + '</p>' : '') +
    (s.hasPassword ? '<button class="danger" onclick="lockNow()">Lock now</button>' : '') + '</div>' +
    '<div class="card"><h2>Master password</h2><p>' + (s.hasPassword ? 'Change it, or leave the new one empty to remove it.' : 'Optional. With one set, the server stays locked after every start until it is entered here (or in the server window), and you can lock it any time.') + '</p>' +
    (s.hasPassword ? '<input type="password" id="cur" placeholder="Current master password">' : '') +
    '<input type="password" id="np1" placeholder="New master password (a long phrase is best)"><input type="password" id="np2" placeholder="New master password again"><button onclick="setPw()">Save</button><p id="pmsg"></p></div>' +
    '<div class="card"><h2>Server and admin passwords</h2><p>Kept encrypted in the vault, so nobody can change them by editing a file. The current password is needed.</p>' +
    '<select id="pwWhich" style="width:100%;background:#0d0d14;color:#ddd;border:1px solid #333;border-radius:8px;padding:10px;margin-bottom:10px"><option value="server">Server password (phones and browsers)</option><option value="admin">Admin password (this panel)</option></select>' +
    '<input type="password" id="spCur" placeholder="Current password"><input type="password" id="spN1" placeholder="New password"><input type="password" id="spN2" placeholder="New password again">' +
    '<button onclick="setSp()">Change</button><p id="spmsg" class="hint"></p></div>' +
    '<div class="card"><h2>Recovery key</h2><p>The only way back in if Windows is reinstalled, this PC dies, or the keyring file is lost. Write it down and keep it somewhere safe, away from this PC. Anyone with it (and the files) can read everything.</p>' +
    (s.hasPassword ? '<input type="password" id="rkpw" placeholder="Master password">' : '') + '<button onclick="showRk()">Show recovery key</button><div id="rkout"></div></div>' +
    '<div class="card"><h2>Panic phrase locks the server</h2><p>' + (s.hasPassword ? 'When one of these accounts uses its panic phrase, the whole server locks too (master password to open).' : 'Needs a master password first (without one the server would just unlock itself again).') + '</p>' +
    '<input id="pu" placeholder="Usernames (not phrases), separated by commas" value="' + esc((s.panicUsers || []).join(', ')) + '"><button onclick="savePu()">Save</button><p id="pumsg"></p></div>';
  $('app').innerHTML = '<div class="card" style="background:none;border:none;padding:0 0 4px"><a href="/admin">&larr; Admin panel</a></div>' + lockedBox + openBox;
}
async function unlock() { try { await api('/admin/api/vault/unlock', { password: $('upw').value }); load(); } catch (e) { $('umsg').textContent = e.message; } }
async function recover() { try { await api('/admin/api/vault/unlock', { recovery: $('rk').value }); alert('Unlocked. Set a master password again if you had one.'); load(); } catch (e) { $('umsg').textContent = e.message; } }
async function lockNow() { if (!confirm('Lock the server now? Everyone is disconnected until the master password is entered.')) return; await api('/admin/api/vault/lock', {}); load(); }
async function setPw() {
  const a = $('np1').value, b = $('np2').value;
  if (a !== b) { $('pmsg').textContent = "The new passwords don't match"; return; }
  if (a && a.length < 8) { $('pmsg').textContent = 'At least 8 characters'; return; }
  try { await api('/admin/api/vault/password', { current: $('cur') ? $('cur').value : '', next: a }); $('pmsg').textContent = a ? 'Saved' : 'Removed'; setTimeout(load, 900); }
  catch (e) { $('pmsg').textContent = e.message; }
}
async function setSp() {
  const w = $('pwWhich').value, a = $('spN1').value, b = $('spN2').value;
  if (a !== b) { $('spmsg').textContent = "The new passwords don't match"; return; }
  try {
    await api('/admin/api/vault/' + w + '-password', { current: $('spCur').value, next: a });
    $('spmsg').textContent = w === 'server' ? 'Changed. Phones and browsers ask for the new one when they reconnect (browsers that stay signed in keep working).' : 'Changed. Use it the next time you sign in here.';
    $('spCur').value = $('spN1').value = $('spN2').value = '';
  } catch (e) { $('spmsg').textContent = e.message; }
}
async function showRk() {
  try { const j = await api('/admin/api/vault/recovery', { password: $('rkpw') ? $('rkpw').value : '' });
    $('rkout').innerHTML = '<div class="mono">' + esc(j.key) + '</div><p class="hint">Copy it now. It is not shown again unless you ask.</p>'; }
  catch (e) { $('rkout').innerHTML = '<p class="bad">' + esc(e.message) + '</p>'; }
}
async function savePu() { try { await api('/admin/api/vault/panic-users', { users: $('pu').value.split(',') }); $('pumsg').textContent = 'Saved'; } catch (e) { $('pumsg').textContent = e.message; } }
load(); setInterval(() => { if (!document.activeElement || document.activeElement.tagName !== 'INPUT') load(); }, 15000);
</script></body></html>`;

/** A username from a URL. The app encoded spaces as '+' ("Silvershire+Updates"), so its
 *  picture never loaded on phones; accept both. */
function urlName(seg) {
  let s = String(seg || '');
  try { s = decodeURIComponent(s); } catch (_) {}
  if (!users[s] && !groups[s] && s.includes('+')) { const t = s.replace(/\+/g, ' '); if (users[t] || groups[t]) return t; }
  return s;
}

function safeEq(a, b) {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b || ''));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

const vaultTries = new Map();   // ip -> { next, fails } (vault unlock while locked)
const panicHttpTries = new Map();   // ip -> { next, fails } (unlock phrase over HTTP)

function isAdminAuthed(req) {
  const cookies = parseCookies(req.headers['cookie']);
  return adminSessions.has(cookies['admin_token']);
}

function adminJson(res, code, data) {
  res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(data));
}

// Helper: purge all data for a deleted account
function purgeUser(username) {
  // Notify the user if they're currently online -------- give them a popup before we kick them
  if (connections.has(username)) {
    try {
      sendToUser(username, {
        type: 'account_deleted',
        message: 'Your account has been manually deleted by the server administrator.'
      });
    } catch (_) {}
    // Give the message a moment to reach the client(s) before forcibly closing
    setTimeout(() => {
      closeAllConnections(username);
      connectionIPs.delete(username);
    }, 500);
  }

  // Remove from wiped list (if they were previously panic-wiped) so the username
  // can be freely re-registered. Admin deletion is a full cleanup, NOT a security lock.
  // The wiped list is only for user-initiated panic wipes.
  if (wiped[username]) {
    delete wiped[username];
    saveJSON(WIPED_FILE, wiped);
  }

  // Remove from users
  delete users[username];
  saveJSON(USERS_FILE, users);

  // Notify each online mutual friend that this contact was removed
  const deletedFriends = (friends[username] || []).filter(f => f !== username);
  for (const friend of deletedFriends) {
    sendToUser(friend, { type: 'contact_removed_by_peer', name: username });
  }

  // Remove from friends lists
  for (const u of Object.keys(friends)) {
    friends[u] = (friends[u] || []).filter(f => f !== username);
  }
  delete friends[username];
  saveJSON(FRIENDS_FILE, friends);

  // Remove from pending
  for (const u of Object.keys(pending)) {
    pending[u] = (pending[u] || []).filter(f => f !== username);
  }
  delete pending[username];
  saveJSON(PENDING_FILE, pending);

  // Remove profile/banner pics (any supported ext)
  try {
    deleteUserMedia(PROFILE_PICS_DIR, username);
    deleteUserMedia(BANNER_PICS_DIR, username);
  } catch (_) {}

  // Remove all direct-message history files involving this user.
  // History filenames are sorted: "alice__bob.json". Check if either segment matches.
  // Without this, re-created accounts with the same username inherit old chat history.
  try {
    const historyFiles = fs.readdirSync(HISTORY_DIR);
    for (const file of historyFiles) {
      if (file.startsWith('group__')) continue; // leave group history alone
      const base  = file.endsWith('.json') ? file.slice(0, -5) : file;
      const parts = base.split('__');
      if (parts.includes(username)) {
        try { fs.unlinkSync(path.join(HISTORY_DIR, file)); } catch (_) {}
      }
    }
  } catch (_) {}

  console.log(`[ADMIN] Account deleted: ${username}`);
}

const ADMIN_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Silvershire Inn — Admin</title>
<style>
  *{box-sizing:border-box;margin:0;padding:0}
  body{background:#0d0d14;color:#d4d4d8;font-family:system-ui,sans-serif;font-size:14px}
  h1{font-size:22px;font-weight:700;color:#A8A9AD;letter-spacing:1px}
  h2{font-size:14px;font-weight:600;color:#A8A9AD;letter-spacing:.08em;text-transform:uppercase;margin-bottom:10px}
  a{color:#A8A9AD;text-decoration:none}
  .header{display:flex;align-items:center;gap:16px;padding:16px 24px;background:#13131f;border-bottom:1px solid #222}
  .header .flame{font-size:28px}
  .header nav{margin-left:auto;display:flex;gap:12px;align-items:center}
  .header nav a:hover{color:#fff}
  .badge{display:inline-block;padding:2px 8px;border-radius:999px;font-size:11px;font-weight:700}
  .badge.online{background:#1a3a1a;color:#4caf50}
  .badge.offline{background:#2a2a2a;color:#888}
  .badge.banned{background:#3a1414;color:#f44}
  .stats{display:flex;gap:16px;padding:16px 24px;flex-wrap:wrap}
  .stat-card{background:#13131f;border:1px solid #222;border-radius:10px;padding:14px 20px;min-width:130px}
  .stat-card .num{font-size:28px;font-weight:700;color:#A8A9AD}
  .stat-card .lbl{font-size:11px;color:#888;margin-top:2px}
  .section{padding:0 24px 24px}
  .card{background:#13131f;border:1px solid #1e1e2e;border-radius:12px;overflow:hidden}
  table{width:100%;border-collapse:collapse}
  th{background:#0d0d1a;color:#888;font-size:11px;text-transform:uppercase;letter-spacing:.08em;padding:10px 14px;text-align:left;border-bottom:1px solid #222}
  td{padding:10px 14px;border-bottom:1px solid #1a1a2a;vertical-align:middle}
  tr:last-child td{border-bottom:none}
  tr:hover td{background:#16161f}
  .ip{font-family:monospace;color:#7a8;font-size:12px}
  .ts{color:#666;font-size:12px}
  .actions{display:flex;gap:6px;flex-wrap:wrap}
  button{cursor:pointer;border:none;border-radius:6px;padding:4px 10px;font-size:12px;font-weight:600;transition:opacity .15s}
  button:hover{opacity:.8}
  .btn-kick{background:#3a2a00;color:#ffb300}
  .btn-ban{background:#3a1414;color:#f66}
  .btn-del{background:#1a0a0a;color:#ff4444;border:1px solid #440000}
  .btn-unban{background:#1a3a1a;color:#4f4}
  .btn-ok{background:#A8A9AD;color:#000}
  .empty{color:#555;padding:20px;text-align:center}
  .login-wrap{display:flex;align-items:center;justify-content:center;min-height:100vh}
  .login-card{background:#13131f;border:1px solid #222;border-radius:16px;padding:40px;width:340px}
  .login-card h1{margin-bottom:24px;text-align:center}
  .login-card input{width:100%;background:#0d0d14;border:1px solid #333;border-radius:8px;padding:10px 14px;color:#d4d4d8;font-size:14px;margin-bottom:14px}
  .login-card input:focus{outline:1px solid #A8A9AD}
  .login-card button{width:100%;padding:10px;font-size:15px;background:#A8A9AD;color:#000;border-radius:8px}
  .err{color:#f44;font-size:12px;margin-bottom:10px;text-align:center}
  .refresh{font-size:11px;color:#555;margin-left:8px}
  .section-hd{display:flex;align-items:center;gap:10px;margin-bottom:10px}
</style>
</head>
<body>
<div id="app">Loading…</div>
<script>
let data = null;

async function fetchData() {
  const r = await fetch('/admin/api/data');
  if (r.status === 401) { render(null); return; }
  if (r.status === 423) { location.href = '/admin/vault'; return; }   // server locked
  data = await r.json();
  render(data);
}

async function action(url, method='POST', body=null) {
  const opts = { method };
  if (body) { opts.headers={'Content-Type':'application/json'}; opts.body=JSON.stringify(body); }
  const r = await fetch(url, opts);
  const j = await r.json().catch(()=>({}));
  if (!r.ok) alert(j.error || 'Error');
  fetchData();
}

async function publishUpdate() {
  if (!confirm('DM an update notice from Silvershire Updates to all eligible users?')) return;
  const out = document.getElementById('pubOut');
  if (out) out.textContent = 'Publishing…';
  try {
    const r = await fetch('/admin/api/publish-update', { method: 'POST' });
    const j = await r.json().catch(()=>({}));
    if (!r.ok) { if (out) out.textContent = j.error || 'Failed'; return; }
    if (out) out.textContent = 'Update notice published.';
  } catch (e) { if (out) out.textContent = String(e.message || e); }
}
function kick(u)       { if(confirm('Kick '+u+'?'))  action('/admin/api/kick/'+encodeURIComponent(u)); }
function banIp(ip)     { if(confirm('Ban IP '+ip+'?')) action('/admin/api/ban-ip',  'POST', {ip}); }
function delUser(u)    { if(confirm('DELETE account "'+u+'" and ALL data? This cannot be undone.')) action('/admin/api/user/'+encodeURIComponent(u),'DELETE'); }
function unban(ip)     { action('/admin/api/unban-ip','POST',{ip}); }
function allowKey(u)   { if(confirm('Allow "'+u+'" to sign in with a NEW device key for the next 15 minutes? Only do this if they lost their key (reinstall).')) action('/admin/api/allow-key-reset/'+encodeURIComponent(u)); }
function clearWipe(u)  { if(confirm('Remove "'+u+'" from the wipe blacklist? They will be able to register again.')) action('/admin/api/clear-wipe/'+encodeURIComponent(u)); }
function clearAllWiped(){ if(confirm('Clear ALL wiped accounts? All locked usernames will be freed.')) action('/admin/api/clear-all-wiped'); }
async function logout() { await fetch('/admin/logout'); render(null); }

async function login(e) {
  e.preventDefault();
  const pw = document.getElementById('pw').value;
  const r  = await fetch('/admin/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({password:pw})});
  const j  = await r.json();
  if (r.ok) { fetchData(); }
  else { document.getElementById('err').textContent = j.error || 'Wrong password'; }
}

function fmt(ts) {
  if (!ts) return '<span class="ts">—</span>';
  try {
    const d = new Date(ts);
    return '<span class="ts">'+d.toLocaleDateString()+' '+d.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'})+'</span>';
  } catch { return '<span class="ts">'+ts+'</span>'; }
}

function render(d) {
  if (!d) {
    document.getElementById('app').innerHTML = \`
      <div class="login-wrap">
        <div class="login-card">
          <h1>🏰 Admin Login</h1>
          <div id="err" class="err"></div>
          <form onsubmit="login(event)">
            <input type="password" id="pw" placeholder="Admin password" autocomplete="current-password">
            <button type="submit" class="btn-ok">Login</button>
          </form>
        </div>
      </div>\`;
    return;
  }

  const onlineCount = d.users.filter(u=>u.online).length;
  const bannedCount = d.bannedIPs.length;
  const wipedCount  = (d.wipedAccounts || []).length;

  const rows = d.users.map(u => \`
    <tr>
      <td><strong>\${u.username}</strong></td>
      <td>\${u.online ? '<span class="badge online">● Online</span>' : '<span class="badge offline">Offline</span>'}</td>
      <td class="ip">\${u.ip || '—'}</td>
      <td>\${fmt(u.lastSeen)}</td>
      <td>\${fmt(u.registeredAt)}</td>
      <td>
        <div class="actions">
          \${u.online ? \`<button class="btn-kick" onclick="kick('\${u.username}')">Kick</button>\` : ''}
          \${u.ip ? \`<button class="btn-ban" onclick="banIp('\${u.ip}')">Ban IP</button>\` : ''}
          <button class="btn-kick" onclick="allowKey('\${u.username}')" title="Lets this account sign in with a new device key for 15 minutes (reinstall without the identity key)">New device</button>
          <button class="btn-del" onclick="delUser('\${u.username}')">Delete</button>
        </div>
      </td>
    </tr>\`).join('');

  const banRows = d.bannedIPs.map(b => \`
    <tr>
      <td class="ip">\${b.ip}</td>
      <td class="ts">\${b.attempts} attempt\${b.attempts!==1?'s':''}</td>
      <td>\${fmt(b.until ? new Date(b.until).toISOString() : null)}</td>
      <td><button class="btn-unban" onclick="unban('\${b.ip}')">Unban</button></td>
    </tr>\`).join('');

  document.getElementById('app').innerHTML = \`
    <div class="header">
      <span class="flame">🏰</span>
      <div>
        <h1>Silvershire Inn</h1>
        <div style="font-size:11px;color:#666;margin-top:2px">Admin Panel</div>
      </div>
      <nav>
        <span class="refresh">Auto-refresh 10s</span>
        <a href="/admin/vault">Vault</a>
        <a href="#" onclick="logout();return false">Logout</a>
      </nav>
    </div>

    <div class="stats">
      <div class="stat-card"><div class="num">\${d.users.length}</div><div class="lbl">Total Accounts</div></div>
      <div class="stat-card"><div class="num" style="color:#4f4">\${onlineCount}</div><div class="lbl">Online Now</div></div>
      <div class="stat-card"><div class="num">\${d.users.length - onlineCount}</div><div class="lbl">Offline</div></div>
      <div class="stat-card"><div class="num" style="color:#f44">\${bannedCount}</div><div class="lbl">Banned IPs</div></div>
      <div class="stat-card"><div class="num" style="color:#fa0">\${wipedCount}</div><div class="lbl">Wiped (Locked)</div></div>
    </div>

    <div class="section">
      <div class="section-hd"><h2>App updates</h2></div>
      <div class="card" style="padding:16px">
        <div style="color:#aaa;font-size:13px;margin-bottom:12px">Publishes a DM from <b>Silvershire Updates</b> to every mutual contact who has not blocked or removed that contact. Skips blocked/removed users. Copy the APK to <code>data/download/silvershire-inn.apk</code> first.</div>
        <div class="actions">
          <button class="btn-ok" onclick="publishUpdate()">Publish update notice</button>
        </div>
        <div id="pubOut" style="margin-top:10px;font-size:12px;color:#888"></div>
      </div>
    </div>

    <div class="section">
      <div class="section-hd"><h2>Accounts</h2></div>
      <div class="card">
        \${rows ? \`<table>
          <thead><tr><th>Username</th><th>Status</th><th>IP</th><th>Last Seen</th><th>Registered</th><th>Actions</th></tr></thead>
          <tbody>\${rows}</tbody>
        </table>\` : '<div class="empty">No accounts yet.</div>'}
      </div>
    </div>

    \${bannedCount > 0 ? \`
    <div class="section">
      <div class="section-hd"><h2>Banned IPs</h2></div>
      <div class="card">
        <table>
          <thead><tr><th>IP</th><th>Attempts</th><th>Ban Expires</th><th></th></tr></thead>
          <tbody>\${banRows}</tbody>
        </table>
      </div>
    </div>\` : ''}

    \${wipedCount > 0 ? \`
    <div class="section">
      <div class="section-hd">
        <h2>Wiped / Locked Usernames</h2>
        <button class="btn-del" style="margin-left:auto" onclick="clearAllWiped()">Clear All</button>
      </div>
      <div class="card">
        <table>
          <thead><tr><th>Username</th><th>Reason</th><th>Wiped At</th><th></th></tr></thead>
          <tbody>\${(d.wipedAccounts||[]).map(w=>\`
            <tr>
              <td><strong>\${w.username}</strong></td>
              <td><span class="ts">\${w.reason === 'admin_delete' ? 'Admin delete' : 'Panic wipe'}</span></td>
              <td>\${fmt(w.wipedAt)}</td>
              <td><button class="btn-unban" onclick="clearWipe('\${w.username}')">Free Username</button></td>
            </tr>\`).join('')}
          </tbody>
        </table>
      </div>
    </div>\` : ''}
  \`;
}

fetchData();
setInterval(fetchData, 10000);
</script>
</body>
</html>`;

const adminServer = http.createServer((req, res) => {
  const url    = (req.url || '/').split('?')[0];
  const method = req.method || 'GET';

  // Serve admin panel HTML
  if (method === 'GET' && url === '/admin') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(ADMIN_HTML);
    return;
  }

  // Login endpoint
  if (method === 'POST' && url === '/admin/login') {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => {
      try {
        const { password } = JSON.parse(body);
        if (!checkAdminPassword(password)) {
          adminJson(res, 401, { error: 'Wrong password' });
          return;
        }
        const token = crypto.randomBytes(24).toString('hex');
        adminSessions.add(token);
        res.writeHead(200, {
          'Set-Cookie': `admin_token=${token}; HttpOnly; Path=/; SameSite=Strict`,
          'Content-Type': 'application/json'
        });
        res.end(JSON.stringify({ ok: true }));
      } catch {
        adminJson(res, 400, { error: 'Bad request' });
      }
    });
    return;
  }

  // Logout
  if (method === 'GET' && url === '/admin/logout') {
    const cookies = parseCookies(req.headers['cookie']);
    adminSessions.delete(cookies['admin_token']);
    res.writeHead(200, {
      'Set-Cookie': 'admin_token=; Max-Age=0; Path=/',
      'Content-Type': 'application/json'
    });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  if (method === 'GET' && url === '/admin/vault') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(VAULT_HTML);
    return;
  }

  // Locked: the admin password itself is encrypted, so the master password (or recovery key)
  // is what opens the vault. Slowed down after wrong tries.
  if (AtRest.locked() && method === 'GET' && url === '/admin/api/vault') {
    const s = AtRest.status();
    adminJson(res, 200, { exists: s.exists, locked: true, hasPassword: s.hasPassword, error: s.error, windows: s.windows });
    return;
  }
  if (AtRest.locked() && method === 'POST' && url === '/admin/api/vault/unlock') {
    const ip = req.socket.remoteAddress || '';
    const t = vaultTries.get(ip) || { next: 0, fails: 0 };
    if (Date.now() < t.next) { adminJson(res, 429, { error: 'Too many tries, wait a moment' }); return; }
    let b = ''; req.on('data', c => { if (b.length < 10000) b += c; });
    req.on('end', () => {
      let j = {}; try { j = JSON.parse(b || '{}'); } catch (_) {}
      try {
        if (j.recovery) AtRest.unlockWithRecovery(j.recovery); else AtRest.unlockWithPassword(j.password || '');
        vaultTries.delete(ip);
        onVaultUnlocked('vault page');
        adminJson(res, 200, { ok: true });
      } catch (e) {
        t.fails++; t.next = Date.now() + (t.fails >= 10 ? 5 * 60000 : 1500); vaultTries.set(ip, t);
        setTimeout(() => adminJson(res, 403, { error: e.message }), 1200);
      }
    });
    return;
  }

  // All routes below require auth
  if (!isAdminAuthed(req)) {
    adminJson(res, 401, { error: 'Unauthorized' });
    return;
  }

  // ── Vault (encryption at rest) ──
  if (url.startsWith('/admin/api/vault')) {
    const readBody = (cb) => { let b = ''; req.on('data', c => { if (b.length < 10000) b += c; }); req.on('end', () => { let j = {}; try { j = JSON.parse(b || '{}'); } catch (_) {} cb(j); }); };
    if (method === 'GET' && url === '/admin/api/vault') { adminJson(res, 200, Object.assign(AtRest.status(), { migrating: vaultMigrating, panicUsers: panicLockUsers() })); return; }
    // Server / admin password: only with the current one (and only while unlocked).
    if (method === 'POST' && (url === '/admin/api/vault/server-password' || url === '/admin/api/vault/admin-password')) {
      readBody((j) => {
        const isServer = url.endsWith('server-password');
        const fail = (m) => setTimeout(() => adminJson(res, 403, { error: m }), 1000);
        if (!secrets) { adminJson(res, 423, { error: 'Unlock first' }); return; }
        const cur = String(j.current || ''), next = String(j.next || '');
        if (!(isServer ? checkServerPassword(cur) : checkAdminPassword(cur))) return fail('Current password is wrong');
        if (next.length < 6) return fail('At least 6 characters');
        if (isServer ? checkAdminPassword(next) : checkServerPassword(next)) return fail('The admin password must be different from the server password');
        secrets[isServer ? 'serverPasswordHash' : 'adminPasswordHash'] = pwHash(next);
        _pwOkCache.clear();
        saveSecrets();
        console.log(`[VAULT] ${isServer ? 'Server' : 'Admin'} password changed`);
        adminJson(res, 200, { ok: true });
      });
      return;
    }
    if (method === 'POST' && url === '/admin/api/vault/unlock') {
      readBody((j) => {
        try {
          if (j.recovery) AtRest.unlockWithRecovery(j.recovery); else AtRest.unlockWithPassword(j.password || '');
          onVaultUnlocked('admin panel');
          adminJson(res, 200, { ok: true });
        } catch (e) { setTimeout(() => adminJson(res, 403, { error: e.message }), 1200); }   // slows guessing
      });
      return;
    }
    if (method === 'POST' && url === '/admin/api/vault/lock') { readBody(() => { lockServer('admin panel'); adminJson(res, 200, { ok: true }); }); return; }
    if (method === 'POST' && url === '/admin/api/vault/password') {
      readBody((j) => {
        try {
          if (j.next && String(j.next).length < 8) throw new Error('At least 8 characters');
          AtRest.setPassword(j.current || '', j.next || '');
          console.log(j.next ? '[VAULT] Master password set / changed' : '[VAULT] Master password removed');
          adminJson(res, 200, { ok: true });
        } catch (e) { setTimeout(() => adminJson(res, 403, { error: e.message }), 800); }
      });
      return;
    }
    if (method === 'POST' && url === '/admin/api/vault/recovery') {
      readBody((j) => {
        try {
          if (AtRest.status().hasPassword) {
            // Re-check the master password before handing out the key that opens everything.
            AtRest.unlockWithPassword(j.password || '');
          }
          adminJson(res, 200, { key: AtRest.recoveryKey() });
        } catch (e) { setTimeout(() => adminJson(res, 403, { error: e.message }), 1200); }
      });
      return;
    }
    if (method === 'POST' && url === '/admin/api/vault/panic-users') {
      readBody((j) => {
        if (!secrets) { adminJson(res, 423, { error: 'Unlock first' }); return; }
        const names = Array.from(new Set((j.users || []).map(s => String(s).trim()).filter(Boolean))).slice(0, 20);
        const unknown = names.filter(n => !users[n]);
        if (unknown.length) { adminJson(res, 400, { error: 'Not a username: ' + unknown.join(', ') }); return; }
        secrets.panicUsers = names; saveSecrets(); adminJson(res, 200, { ok: true });
      });
      return;
    }
    adminJson(res, 404, { error: 'Not found' });
    return;
  }

  // The rest of the admin panel needs the data: open the Vault page first when locked.
  if (AtRest.locked()) { adminJson(res, 423, { error: 'Server is locked: open /admin/vault' }); return; }

  // POST /admin/api/publish-update -------- DM update notice (cookie auth)
  if (method === 'POST' && url === '/admin/api/publish-update') {
    try {
      publishUpdateNotice();
      adminJson(res, 200, { ok: true });
    } catch (e) {
      adminJson(res, 500, { error: e.message || 'publish failed' });
    }
    return;
  }

  // GET /admin/api/data -------- full dashboard data
  if (method === 'GET' && url === '/admin/api/data') {
    const usersData = Object.entries(users).map(([username, info]) => ({
      username,
      online:       connections.has(username),
      ip:           connectionIPs.get(username) || null,
      lastSeen:     connections.has(username) ? null : (lastSeenMap.get(username) || info.registeredAt || null),
      registeredAt: info.registeredAt || null,
    }));
    const bannedIPs = Object.entries(banned).map(([ip, b]) => ({
      ip,
      until:    b.until,
      attempts: b.attempts || 0,
    }));
    const wipedAccounts = Object.entries(wiped).map(([name, w]) => ({
      username: name,
      wipedAt:  w.wipedAt || null,
      reason:   w.reason  || 'panic_wipe',
    }));
    adminJson(res, 200, { users: usersData, bannedIPs, wipedAccounts });
    return;
  }

  // POST /admin/api/kick/:username
  if (method === 'POST' && url.startsWith('/admin/api/kick/')) {
    const username = decodeURIComponent(url.slice('/admin/api/kick/'.length));
    if (connections.has(username)) {
      closeAllConnections(username);
      connectionIPs.delete(username);
      console.log(`[ADMIN] Kicked user: ${username}`);
      adminJson(res, 200, { ok: true });
    } else {
      adminJson(res, 404, { error: 'User not online' });
    }
    return;
  }

  // POST /admin/api/ban-ip
  if (method === 'POST' && url === '/admin/api/ban-ip') {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => {
      try {
        const { ip } = JSON.parse(body);
        if (!ip) { adminJson(res, 400, { error: 'ip required' }); return; }
        banned[ip] = { until: Date.now() + BAN_DURATION_MS, attempts: 999 };
        saveJSON(BANNED_FILE, banned);
        // Kick any user connected from that IP
        for (const [u, uIp] of connectionIPs.entries()) {
          if (uIp === ip) {
            closeAllConnections(u);
            connectionIPs.delete(u);
          }
        }
        console.log(`[ADMIN] Banned IP: ${ip}`);
        adminJson(res, 200, { ok: true });
      } catch { adminJson(res, 400, { error: 'Bad request' }); }
    });
    return;
  }

  // POST /admin/api/unban-ip
  if (method === 'POST' && url.startsWith('/admin/api/allow-key-reset/')) {
    let uname = ''; try { uname = decodeURIComponent(url.slice('/admin/api/allow-key-reset/'.length)); } catch (_) {}
    if (!users[uname]) { adminJson(res, 404, { error: 'User not found' }); return; }
    users[uname].allowKeyResetUntil = Date.now() + 15 * 60 * 1000;
    saveJSON(USERS_FILE, users);
    console.log(`[ADMIN] new device key allowed for ${uname} (15 min)`);
    adminJson(res, 200, { ok: true });
    return;
  }

  if (method === 'POST' && url === '/admin/api/unban-ip') {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => {
      try {
        const { ip } = JSON.parse(body);
        if (!ip) { adminJson(res, 400, { error: 'ip required' }); return; }
        delete banned[ip];
        saveJSON(BANNED_FILE, banned);
        failedAuths.delete(ip);
        console.log(`[ADMIN] Unbanned IP: ${ip}`);
        adminJson(res, 200, { ok: true });
      } catch { adminJson(res, 400, { error: 'Bad request' }); }
    });
    return;
  }

  // POST /admin/api/clear-wipe/:username -------- remove a username from the wiped blacklist
  if (method === 'POST' && url.startsWith('/admin/api/clear-wipe/')) {
    const username = decodeURIComponent(url.slice('/admin/api/clear-wipe/'.length));
    if (!wiped[username]) { adminJson(res, 404, { error: 'Not in wiped list' }); return; }
    delete wiped[username];
    saveJSON(WIPED_FILE, wiped);
    console.log(`[ADMIN] Cleared wipe lock for: ${username}`);
    adminJson(res, 200, { ok: true });
    return;
  }

  // POST /admin/api/clear-all-wiped -------- remove ALL entries from the wiped blacklist
  if (method === 'POST' && url === '/admin/api/clear-all-wiped') {
    const count = Object.keys(wiped).length;
    wiped = {};
    saveJSON(WIPED_FILE, wiped);
    console.log(`[ADMIN] Cleared all ${count} wiped account(s)`);
    adminJson(res, 200, { ok: true, count });
    return;
  }

  // DELETE /admin/api/user/:username
  if (method === 'DELETE' && url.startsWith('/admin/api/user/')) {
    const username = decodeURIComponent(url.slice('/admin/api/user/'.length));
    if (!users[username]) { adminJson(res, 404, { error: 'User not found' }); return; }
    purgeUser(username);
    adminJson(res, 200, { ok: true });
    return;
  }

  adminJson(res, 404, { error: 'Not found' });
});

// ============================================================
//  START
// ============================================================
if (!AtRest.locked()) { loadSecrets(); ensureUpdatesUser(); setTimeout(migrateAtRest, 3000); setTimeout(autoUpdateNotice, 4000); }

else promptMasterPassword();

// Publishing asks for a restart by creating restart.request: save and exit with code 99, and
// START_SERVER.bat starts the new version in the same window (nothing gets killed).
const RESTART_FILE = path.join(__dirname, 'restart.request');
try { if (fs.existsSync(RESTART_FILE)) fs.unlinkSync(RESTART_FILE); } catch (_) {}
setInterval(() => {
  if (!fs.existsSync(RESTART_FILE)) return;
  try { fs.unlinkSync(RESTART_FILE); } catch (_) {}
  console.log('[UPDATE] Restart requested: saving and restarting...');
  try { saveJSON(USERS_FILE, users); saveJSON(FRIENDS_FILE, friends); saveJSON(PENDING_FILE, pending); saveJSON(GROUPS_FILE, groups); saveJSON(BANNED_FILE, banned); } catch (_) {}
  setTimeout(() => process.exit(99), 500);
}, 2000);
httpServer.listen(PORT, () => {
  try { if (!AtRest.locked()) ensureUpdatesUser(); } catch (e) { console.error('[UPDATES] ensure failed', e.message); }
  if (AtRest.locked()) console.log('\n   LOCKED: open http://localhost:' + ADMIN_PORT + '/admin/vault (or type the master password here)');
  console.log(`\n-------- Silvershire Inn server running on port ${PORT}`);
  console.log(`   WebSocket : ws://localhost:${PORT}`);
  console.log(`   HTTP API  : http://localhost:${PORT}`);
  console.log(`   Data dir  : ${DATA_DIR}`);
  console.log(`   Updates   : contact "${UPDATES_USER}" ready (POST /admin/publish-update)`);
});

// The admin panel only listens on this computer unless data/config.json has "adminRemote": true
// (it listened on every network interface before).
adminServer.listen(ADMIN_PORT, _cfg.adminRemote ? undefined : '127.0.0.1', () => {
  console.log(`   Admin GUI : http://localhost:${ADMIN_PORT}/admin`);
  console.log(`   Passwords : on the admin Vault page (first start: data/FIRST-RUN-PASSWORDS.txt)\n`);
});

// ============================================================
//  GRACEFUL SHUTDOWN
// ============================================================
process.on('SIGINT', () => {
  console.log('\n[SHUTDOWN] Saving data...');
  saveJSON(USERS_FILE,   users);
  saveJSON(FRIENDS_FILE, friends);
  saveJSON(PENDING_FILE, pending);
  saveJSON(GROUPS_FILE,  groups);
  saveJSON(BANNED_FILE,  banned);
  wss.close(() => {
    httpServer.close(() => {
      console.log('[SHUTDOWN] Done. Goodbye.');
      process.exit(0);
    });
  });
});

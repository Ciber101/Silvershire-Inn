'use strict';
/*
 * Encryption at rest for everything the server keeps on this PC (chat history, groups,
 * accounts, receipts, upload details, profile pictures, older uploads, crash reports).
 *
 * One random data key encrypts everything (AES-256-GCM). The data key is kept in
 * data/keyring.json wrapped by:
 *   - Windows DPAPI (tied to this Windows account): the server unlocks by itself, and the
 *     files are noise to anyone who copies them, takes the drive, or uses another account.
 *   - optionally ALSO a master password (scrypt): then nothing opens until it's entered
 *     (admin panel or the server window), and "Lock now" / a panic phrase drops the key.
 * A recovery key (the data key itself) can be shown once in the admin panel; it is the only
 * way back in if Windows is reinstalled or the keyring is lost.
 *
 * Formats:
 *   small files : "GSE1" + iv(12) + ciphertext + tag(16)
 *   big files   : "GSC1" + iv8(8) + chunks of 1 MiB (same scheme as the apps' file
 *                 encryption: nonce = iv8 + chunk index, AAD = last-chunk flag), so a video
 *                 can be served from any position without decrypting the whole file.
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { Transform, Readable } = require('stream');
const { execFileSync } = require('child_process');

const MAGIC = Buffer.from('GSE1');
const CMAGIC = Buffer.from('GSC1');
const CS = 1 << 20;
const STEP = CS + 16;
const CHECK_TEXT = Buffer.from('silvershire-keyring-check');

module.exports = function createAtRest(DATA_DIR) {
  const KEYRING = path.join(DATA_DIR, 'keyring.json');
  let DK = null;
  let lastError = '';
  // A file the data key must open (users.json). A key that doesn't open it is refused, so a
  // replaced / missing keyring can never start an empty server that overwrites the real data.
  let probeFile = null;
  function probeOk(k) {
    try {
      if (!probeFile || !fs.existsSync(probeFile)) return true;
      const raw = fs.readFileSync(probeFile);
      if (!(raw.length >= 4 && raw.subarray(0, 4).equals(MAGIC))) return true;   // still plain
      ungcm(k, raw.subarray(4));
      return true;
    } catch (_) { return false; }
  }
  function sealedDataExists() {
    try { if (!probeFile || !fs.existsSync(probeFile)) return false; const b = fs.readFileSync(probeFile); return b.length >= 4 && b.subarray(0, 4).equals(MAGIC); }
    catch (_) { return false; }
  }
  const WRONG_KEY = 'This key does not open the data on this PC (the keyring file was replaced or damaged). Use the recovery key.';

  const readRing = () => { try { return JSON.parse(fs.readFileSync(KEYRING, 'utf8')); } catch (_) { return null; } };
  function writeRing(r) {
    const tmp = KEYRING + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(r, null, 2));
    fs.renameSync(tmp, KEYRING);
  }

  // ── Windows DPAPI (via PowerShell; input passed in an environment variable) ──
  const isWin = process.platform === 'win32';
  function dpapi(op, buf) {
    const script = "$ErrorActionPreference='Stop';Add-Type -AssemblyName System.Security;" +
      "$b=[Convert]::FromBase64String($env:GS_DPAPI_IN);" +
      "$o=[System.Security.Cryptography.ProtectedData]::" + op +
      "($b,$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);" +
      "[Convert]::ToBase64String($o)";
    const out = execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script],
      { env: Object.assign({}, process.env, { GS_DPAPI_IN: buf.toString('base64') }), windowsHide: true, timeout: 60000 })
      .toString().trim();
    if (!out) throw new Error('DPAPI returned nothing');
    return Buffer.from(out, 'base64');
  }
  const wrapOuter = (buf) => isWin ? { wrap: 'dpapi', blob: dpapi('Protect', buf).toString('base64') }
                                   : { wrap: 'plain', blob: buf.toString('base64') };
  const unwrapOuter = (r) => r.wrap === 'dpapi' ? dpapi('Unprotect', Buffer.from(r.blob, 'base64')) : Buffer.from(r.blob, 'base64');

  // ── primitives ──
  function gcm(key, data) {
    const iv = crypto.randomBytes(12);
    const c = crypto.createCipheriv('aes-256-gcm', key, iv);
    const ct = Buffer.concat([c.update(data), c.final()]);
    return Buffer.concat([iv, ct, c.getAuthTag()]);
  }
  function ungcm(key, blob) {
    if (blob.length < 28) throw new Error('too short');
    const d = crypto.createDecipheriv('aes-256-gcm', key, blob.subarray(0, 12));
    d.setAuthTag(blob.subarray(blob.length - 16));
    return Buffer.concat([d.update(blob.subarray(12, blob.length - 16)), d.final()]);
  }
  const kek = (pw, salt) => crypto.scryptSync(String(pw), salt, 32, { N: 1 << 15, r: 8, p: 1, maxmem: 128 * 1024 * 1024 });
  const keyOk = (key, r) => { try { return ungcm(key, Buffer.from(r.check, 'base64')).equals(CHECK_TEXT); } catch (_) { return false; } };

  function saveRing(key, password, prev) {
    let inner;
    if (password) {
      const salt = crypto.randomBytes(16);
      inner = Buffer.from(JSON.stringify({ salt: salt.toString('base64'), w: gcm(kek(password, salt), key).toString('base64') }));
    } else inner = key;
    const outer = wrapOuter(inner);
    writeRing({
      v: 1, wrap: outer.wrap, blob: outer.blob, hasPassword: !!password,
      check: gcm(key, CHECK_TEXT).toString('base64'),
      panicUsers: (prev && prev.panicUsers) || [],
      created: (prev && prev.created) || new Date().toISOString(),
      changed: new Date().toISOString()
    });
  }

  const api = {
    setProbe(file) { probeFile = file; },
    locked: () => !DK,
    status() {
      const r = readRing();
      return { exists: !!r, locked: !DK, hasPassword: !!(r && r.hasPassword), wrap: r ? r.wrap : null,
               panicUsers: (r && r.panicUsers) || [], error: lastError, windows: isWin };
    },

    /** First start: make a key. Otherwise unlock by itself when no master password is set. */
    start() {
      const r = readRing();
      if (!r) {
        if (sealedDataExists()) {
          lastError = 'The keyring file is missing but the data is encrypted. Use the recovery key.';
          console.error('[VAULT] ' + lastError);
          return 'locked';
        }
        DK = crypto.randomBytes(32);
        saveRing(DK, null, null);
        console.log(`[VAULT] New data key created (${isWin ? 'protected by this Windows account' : 'NOT protected: not on Windows'}).`);
        return 'created';
      }
      if (r.hasPassword) { console.log('[VAULT] Locked: master password needed (admin panel or this window).'); return 'locked'; }
      try {
        const k = unwrapOuter(r);
        if (k.length !== 32 || !keyOk(k, r)) throw new Error('wrong key');
        if (!probeOk(k)) { lastError = WRONG_KEY; console.error('[VAULT] ' + WRONG_KEY); return 'locked'; }
        DK = k; lastError = '';
        return 'unlocked';
      } catch (e) {
        lastError = 'This Windows account cannot open the keyring (moved to another PC or user?). Use the recovery key.';
        console.error('[VAULT] ' + lastError, e.message);
        return 'locked';
      }
    },

    unlockWithPassword(pw) {
      const r = readRing(); if (!r) throw new Error('no keyring');
      let inner;
      try { inner = unwrapOuter(r); } catch (e) {
        lastError = 'This Windows account cannot open the keyring. Use the recovery key.'; throw new Error(lastError);
      }
      let k;
      if (r.hasPassword) {
        const j = JSON.parse(inner.toString('utf8'));
        try { k = ungcm(kek(pw, Buffer.from(j.salt, 'base64')), Buffer.from(j.w, 'base64')); }
        catch (_) { throw new Error('Wrong master password'); }
      } else k = inner;
      if (!keyOk(k, r)) throw new Error('Wrong master password');
      if (!probeOk(k)) { lastError = WRONG_KEY; throw new Error(WRONG_KEY); }
      DK = k; lastError = '';
    },

    /** Recovery key = the data key. Re-protects it for this Windows account (no master password). */
    unlockWithRecovery(text) {
      const r = readRing();
      const k = Buffer.from(String(text || '').replace(/[^A-Za-z0-9+/=]/g, ''), 'base64');
      if (k.length !== 32) throw new Error('That is not a recovery key');
      if ((r && !keyOk(k, r) && sealedDataExists() && !probeOk(k)) || !probeOk(k)) throw new Error('That recovery key does not fit this server');
      DK = k; lastError = '';
      saveRing(DK, null, r);
    },

    lock() { DK = null; },

    /** Set, change ('' removes) the master password. The current one is needed if one is set. */
    setPassword(current, next) {
      if (!DK) throw new Error('Unlock first');
      const r = readRing();
      if (r && r.hasPassword) {
        const inner = unwrapOuter(r);
        const j = JSON.parse(inner.toString('utf8'));
        try { ungcm(kek(current, Buffer.from(j.salt, 'base64')), Buffer.from(j.w, 'base64')); }
        catch (_) { throw new Error('Current master password is wrong'); }
      }
      saveRing(DK, next || null, r);
    },

    setPanicUsers(list) {
      const r = readRing(); if (!r) throw new Error('no keyring');
      r.panicUsers = Array.from(new Set((list || []).map(s => String(s).trim()).filter(Boolean))).slice(0, 20);
      writeRing(r);
    },
    panicUsers() { const r = readRing(); return (r && r.panicUsers) || []; },

    recoveryKey() { if (!DK) throw new Error('Unlock first'); return DK.toString('base64'); },

    /** The data key wrapped by another key (kept in memory only: panic lock of the server). */
    wrapKeyFor(k32) { if (!DK) throw new Error('vault locked'); return gcm(k32, DK); },
    /** Try to restore the data key from such a wrap; true if it opened (and fits the data). */
    unwrapKeyWith(wrapped, k32) {
      let k; try { k = ungcm(k32, wrapped); } catch (_) { return false; }
      if (k.length !== 32 || !probeOk(k)) return false;
      DK = k; lastError = '';
      return true;
    },

    // ── small files ──
    isSealed: (buf) => buf.length >= 4 && buf.subarray(0, 4).equals(MAGIC),
    isChunked: (buf) => buf.length >= 4 && buf.subarray(0, 4).equals(CMAGIC),
    seal(buf) { if (!DK) throw new Error('vault locked'); return Buffer.concat([MAGIC, gcm(DK, buf)]); },
    open(buf) {
      if (!api.isSealed(buf)) return buf;
      if (!DK) throw new Error('vault locked');
      return ungcm(DK, buf.subarray(4));
    },
    /** Read a file, decrypting if needed (chunked files decrypted whole: only for small ones). */
    readFile(file) {
      const raw = fs.readFileSync(file);
      if (api.isChunked(raw)) return api.openChunkedBuffer(raw);
      return api.open(raw);
    },
    /** Write encrypted, atomically (a crash never leaves half a file). */
    writeFile(file, buf) {
      const tmp = file + '.tmp' + process.pid;
      fs.writeFileSync(tmp, api.seal(Buffer.isBuffer(buf) ? buf : Buffer.from(String(buf), 'utf8')));
      fs.renameSync(tmp, file);
    },

    // ── big (chunked) files ──
    nonce(iv8, i) { const n = Buffer.alloc(12); iv8.copy(n, 0); n.writeUInt32BE(i >>> 0, 8); return n; },
    sealChunk(iv8, i, data, last) {
      const c = crypto.createCipheriv('aes-256-gcm', DK, api.nonce(iv8, i));
      c.setAAD(Buffer.from([last ? 1 : 0]));
      return Buffer.concat([c.update(data), c.final(), c.getAuthTag()]);
    },
    openChunk(iv8, i, enc, last) {
      const d = crypto.createDecipheriv('aes-256-gcm', DK, api.nonce(iv8, i));
      d.setAAD(Buffer.from([last ? 1 : 0]));
      d.setAuthTag(enc.subarray(enc.length - 16));
      return Buffer.concat([d.update(enc.subarray(0, enc.length - 16)), d.final()]);
    },
    /** Transform: plaintext in, chunked encrypted file out (header first). */
    chunkSealer() {
      if (!DK) throw new Error('vault locked');
      const iv8 = crypto.randomBytes(8);
      let pend = Buffer.alloc(0), idx = 0, headerDone = false;
      return new Transform({
        transform(chunk, _enc, cb) {
          try {
            if (!headerDone) { this.push(Buffer.concat([CMAGIC, iv8])); headerDone = true; }
            pend = pend.length ? Buffer.concat([pend, chunk]) : chunk;
            while (pend.length > CS) {           // more follows: not the last chunk
              this.push(api.sealChunk(iv8, idx++, pend.subarray(0, CS), false));
              pend = pend.subarray(CS);
            }
            cb();
          } catch (e) { cb(e); }
        },
        flush(cb) {
          try {
            if (!headerDone) this.push(Buffer.concat([CMAGIC, iv8]));
            this.push(api.sealChunk(iv8, idx++, pend, true));
            cb();
          } catch (e) { cb(e); }
        }
      });
    },
    plainSizeOfChunked(encSize) {
      const body = encSize - 12;
      const n = Math.max(1, Math.ceil(body / STEP));
      return body - 16 * n;
    },
    /** Readable of plaintext bytes [start, end] of a chunked file (decrypts only what's needed). */
    openChunkedRange(file, start, end) {
      if (!DK) throw new Error('vault locked');
      const fd = fs.openSync(file, 'r');
      const iv8 = Buffer.alloc(8); fs.readSync(fd, iv8, 0, 8, 4);
      const encSize = fs.fstatSync(fd).size;
      const plain = api.plainSizeOfChunked(encSize);
      const lastIdx = Math.max(0, Math.ceil(plain / CS) - 1);
      let idx = Math.floor(start / CS);
      let skip = start % CS;
      let remaining = end - start + 1;
      return new Readable({
        read() {
          try {
            if (remaining <= 0 || idx > lastIdx) { fs.closeSync(fd); this.push(null); return; }
            const last = idx === lastIdx;
            const len = last ? (plain - idx * CS + 16) : STEP;
            const buf = Buffer.alloc(len);
            fs.readSync(fd, buf, 0, len, 12 + idx * STEP);
            let pt = api.openChunk(iv8, idx, buf, last);
            idx++;
            if (skip) { pt = pt.subarray(skip); skip = 0; }
            if (pt.length > remaining) pt = pt.subarray(0, remaining);
            remaining -= pt.length;
            this.push(pt);
          } catch (e) { try { fs.closeSync(fd); } catch (_) {} this.destroy(e); }
        }
      });
    },
    openChunkedBuffer(raw) {
      const iv8 = raw.subarray(4, 12);
      const plain = api.plainSizeOfChunked(raw.length);
      const n = Math.max(1, Math.ceil(plain / CS));
      const out = [];
      for (let i = 0; i < n; i++) {
        const s = 12 + i * STEP, e = Math.min(raw.length, s + STEP);
        out.push(api.openChunk(iv8, i, raw.subarray(s, e), i === n - 1));
      }
      return Buffer.concat(out);
    },
    /** Encrypt a plain file in place (chunked), streaming. */
    sealFileChunked(file) {
      return new Promise((resolve, reject) => {
        const tmp = file + '.sealing';
        const rs = fs.createReadStream(file), ws = fs.createWriteStream(tmp);
        rs.on('error', reject); ws.on('error', reject);
        ws.on('finish', () => { try { fs.renameSync(tmp, file); resolve(); } catch (e) { reject(e); } });
        rs.pipe(api.chunkSealer()).pipe(ws);
      });
    },
    fileHead(file) {
      try { const fd = fs.openSync(file, 'r'); const b = Buffer.alloc(4); fs.readSync(fd, b, 0, 4, 0); fs.closeSync(fd); return b; }
      catch (_) { return Buffer.alloc(0); }
    }
  };
  return api;
};

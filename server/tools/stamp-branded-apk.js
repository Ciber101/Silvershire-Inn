/**
 * Stamp a personalized Silvershire Inn APK.
 *
 * Reads data/branding/branding.json { appName }
 * Optional icon: data/branding/icon.png
 * Writes app_name into strings.xml, copies icon into mipmap folders,
 * runs assembleDebug (signs with the debug keystore — same key as current
 * sideload builds: %USERPROFILE%\\.android\\debug.keystore, alias androiddebugkey),
 * copies APK to data/download/silvershire-inn.apk
 *
 * Usage: node tools/stamp-branded-apk.js
 */
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const ROOT = path.join(__dirname, '..');
const BRAND_DIR = path.join(ROOT, 'data', 'branding');
const STATUS = path.join(BRAND_DIR, 'status.json');
const STRINGS = path.join(ROOT, '..', 'android', 'app', 'src', 'main', 'res', 'values', 'strings.xml');
const PROPS = path.join(BRAND_DIR, 'branding.properties');
const DOWNLOAD = path.join(ROOT, 'data', 'download', 'silvershire-inn.apk');
const GRADLEW = path.join(ROOT, '..', 'android', process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');

function writeStatus(status, message, extra) {
  fs.mkdirSync(BRAND_DIR, { recursive: true });
  fs.writeFileSync(STATUS, JSON.stringify({ status, message, updatedAt: new Date().toISOString(), signingKey: '%USERPROFILE%\\.android\\debug.keystore (alias androiddebugkey)', applicationId: 'com.silvershireinn.app', ...extra }, null, 2));
}

function main() {
  writeStatus('building', 'Stamping branded APK…');
  let appName = 'Silvershire Inn';
  try {
    const j = JSON.parse(fs.readFileSync(path.join(BRAND_DIR, 'branding.json'), 'utf8'));
    if (j.appName) appName = String(j.appName).slice(0, 30);
  } catch (_) {}
  const safe = appName.replace(/[<>&'"]/g, '');
  fs.writeFileSync(PROPS, 'app_name=' + safe + '\n');

  let xml = fs.readFileSync(STRINGS, 'utf8');
  xml = xml.replace(/<string name="app_name">[^<]*<\/string>/, '<string name="app_name">' + safe + '</string>');
  fs.writeFileSync(STRINGS, xml);

  const iconSrc = path.join(BRAND_DIR, 'icon.png');
  if (fs.existsSync(iconSrc)) {
    const res = path.join(ROOT, '..', 'android', 'app', 'src', 'main', 'res');
    for (const dens of ['mipmap-mdpi', 'mipmap-hdpi', 'mipmap-xhdpi', 'mipmap-xxhdpi', 'mipmap-xxxhdpi']) {
      const dir = path.join(res, dens);
      if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
      fs.copyFileSync(iconSrc, path.join(dir, 'ic_launcher.png'));
      fs.copyFileSync(iconSrc, path.join(dir, 'ic_launcher_round.png'));
    }
    // API 26+ uses adaptive XML (mipmap-anydpi-v26), not the density PNGs.
    // Point both layers at the user image so the home-screen icon actually changes.
    const drawableDir = path.join(res, 'drawable');
    if (!fs.existsSync(drawableDir)) fs.mkdirSync(drawableDir, { recursive: true });
    fs.copyFileSync(iconSrc, path.join(drawableDir, 'ic_branded_fg.png'));
    const adaptive = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_branded_fg"/>
    <foreground android:drawable="@drawable/ic_branded_fg"/>
</adaptive-icon>
`;
    const anydpi = path.join(res, 'mipmap-anydpi-v26');
    if (!fs.existsSync(anydpi)) fs.mkdirSync(anydpi, { recursive: true });
    const backupLauncher = path.join(anydpi, 'ic_launcher.xml.bak');
    const launcherXml = path.join(anydpi, 'ic_launcher.xml');
    if (fs.existsSync(launcherXml) && !fs.existsSync(backupLauncher)) {
      fs.copyFileSync(launcherXml, backupLauncher);
      const rnd = path.join(anydpi, 'ic_launcher_round.xml');
      if (fs.existsSync(rnd)) fs.copyFileSync(rnd, path.join(anydpi, 'ic_launcher_round.xml.bak'));
    }
    fs.writeFileSync(launcherXml, adaptive);
    fs.writeFileSync(path.join(anydpi, 'ic_launcher_round.xml'), adaptive);
    console.log('[stamp] adaptive launcher icon rewritten to user image');
  }

  const child = spawn(GRADLEW, ['assembleDebug', '--no-daemon'], {
    cwd: path.join(ROOT, '..', 'android'),
    windowsHide: true,
    env: process.env
  });
  let out = '';
  child.stdout.on('data', d => { out += d.toString(); process.stdout.write(d); });
  child.stderr.on('data', d => { out += d.toString(); process.stderr.write(d); });
  child.on('close', code => {
    if (code !== 0) {
      writeStatus('error', 'Gradle assembleDebug failed (exit ' + code + ')');
      process.exit(code || 1);
      return;
    }
    const built = path.join(ROOT, '..', 'android', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
    if (!fs.existsSync(built)) {
      writeStatus('error', 'assembleDebug succeeded but app-debug.apk not found');
      process.exit(1);
      return;
    }
    fs.mkdirSync(path.dirname(DOWNLOAD), { recursive: true });
    fs.copyFileSync(built, DOWNLOAD);
    writeStatus('ready', 'Branded APK ready: ' + safe, { apkPath: DOWNLOAD, appName: safe });
    console.log('[stamp] ready', DOWNLOAD);
    try {
      const http = require('http');
      const req = http.request({ hostname: '127.0.0.1', port: 8080, path: '/admin/publish-update', method: 'POST', headers: { 'x-password': process.env.SILVERSHIRE_SERVER_PASSWORD || '' } });
      req.on('error', e => console.warn('[stamp] publish notice skipped:', e.message));
      req.end();
    } catch (e) { console.warn('[stamp] publish notice skipped', e.message); }
  });
}

main();

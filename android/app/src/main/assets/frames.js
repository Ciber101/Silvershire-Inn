/* Silvershire Inn profile frames (borders). The server serves this file to the web app; the
   Android app ships a copy (android/app/src/main/assets/frames.js, run tools/sync_frames.py).
   Copyright (C) 2026 CiberAI. GNU GPL v3 or later.
   Rustic, gritty forged metal and carved stone, with procedural dragons.
   Original artwork (no game assets). Everything is drawn from a seed, so a frame is shared with
   friends as just "gen:<kind>:<seed>" and every device draws the identical frame.
   drawFrame(ctx, id, shape, cx, cy, r): r = picture radius. Ornaments reach ~1.7 r. */
(function (global) {
  const TAU = Math.PI * 2;
  const LIGHT = -Math.PI * 0.75;            // light from the top-left
  // corner radius of rounded / squircle pictures, as a fraction of the picture's width (apps can match their own clip)
  const CORNER = { rounded: 0.28, squircle: 0.45 };

  const MATS = {
    iron:       { dark: [12, 12, 14], mid: [56, 57, 61],  light: [124, 126, 131], spec: [186, 188, 192], rust: 0.55, grit: 0.5 },
    steel:      { dark: [16, 18, 21], mid: [74, 78, 86],  light: [146, 150, 158], spec: [210, 213, 218], rust: 0.12, grit: 0.38 },
    gold:       { dark: [24, 16, 5],  mid: [94, 71, 31],  light: [160, 128, 68],  spec: [214, 190, 128], rust: 0.0,  grit: 0.42, patina: [60, 70, 40] },
    bronze:     { dark: [20, 12, 6],  mid: [78, 50, 29],  light: [136, 96, 58],   spec: [196, 160, 120], rust: 0.0,  grit: 0.48, patina: [46, 86, 72] },
    blacksteel: { dark: [5, 5, 6],    mid: [32, 32, 36],  light: [84, 84, 92],    spec: [150, 150, 160], rust: 0.3,  grit: 0.45 },
    stone:      { dark: [17, 16, 15], mid: [66, 63, 58],  light: [116, 111, 103], spec: [140, 135, 126], rust: 0.0,  grit: 0.75, stone: true }
  };

  const mix = (a, b, t) => [0, 1, 2].map(i => Math.round(a[i] + (b[i] - a[i]) * t));
  const rgb = (c, a) => a == null ? `rgb(${c[0]},${c[1]},${c[2]})` : `rgba(${c[0]},${c[1]},${c[2]},${a})`;
  function shade(m, b) {
    b = Math.max(0, Math.min(1, b));
    if (b < 0.45) return mix(m.dark, m.mid, b / 0.45);
    if (b < 0.85) return mix(m.mid, m.light, (b - 0.45) / 0.4);
    return mix(m.light, m.spec, (b - 0.85) / 0.15);
  }
  function rng(seed) { let s = (seed >>> 0) || 1; return () => (s = (s * 1664525 + 1013904223) >>> 0) / 4294967296; }

  function shapePath(ctx, shape, cx, cy, r, keep) {
    if (!keep) ctx.beginPath();
    if (shape === 'diamond') {
      ctx.moveTo(cx, cy - r); ctx.lineTo(cx + r, cy); ctx.lineTo(cx, cy + r); ctx.lineTo(cx - r, cy); ctx.closePath();
    } else if (shape === 'rounded' || shape === 'squircle') {
      const k = shape === 'rounded' ? CORNER.rounded : CORNER.squircle, rr = r * k * 2, x = cx - r, y = cy - r, w = 2 * r;
      ctx.moveTo(x + rr, y); ctx.lineTo(x + w - rr, y); ctx.quadraticCurveTo(x + w, y, x + w, y + rr);
      ctx.lineTo(x + w, y + w - rr); ctx.quadraticCurveTo(x + w, y + w, x + w - rr, y + w);
      ctx.lineTo(x + rr, y + w); ctx.quadraticCurveTo(x, y + w, x, y + w - rr);
      ctx.lineTo(x, y + rr); ctx.quadraticCurveTo(x, y, x + rr, y); ctx.closePath();
    } else ctx.arc(cx, cy, r, 0, TAU);
  }
  /** Point on the shape outline at angle a, radius r (so ornaments follow non-round shapes). */
  function onShape(shape, cx, cy, r, a) {
    let d = r;
    if (shape === 'diamond') d = r / (Math.abs(Math.cos(a)) + Math.abs(Math.sin(a)));
    else if (shape === 'rounded' || shape === 'squircle') d = r / Math.max(Math.abs(Math.cos(a)), Math.abs(Math.sin(a))) * (shape === 'rounded' ? 0.95 : 0.9);
    return [cx + Math.cos(a) * d, cy + Math.sin(a) * d];
  }

  // ── texture: fractal value noise with pits; cached per seed ──
  const _tex = {};
  function texture(seed, kind) {
    const key = seed + ':' + kind;
    if (_tex[key]) return _tex[key];
    const size = 256, c = document.createElement('canvas'); c.width = c.height = size;
    const x = c.getContext('2d'), img = x.createImageData(size, size), rnd = rng(seed * 7 + 3);
    const grid = (n) => { const g = []; for (let i = 0; i < (n + 1) * (n + 1); i++) g.push(rnd()); return g; };
    const octs = (kind === 'stone' ? [[3, 0.35], [9, 0.3], [27, 0.2], [81, 0.15]] : [[4, 0.3], [16, 0.3], [64, 0.25], [128, 0.15]]).map(([n, w]) => ({ n, w, g: grid(n) }));
    for (let py = 0; py < size; py++) for (let px = 0; px < size; px++) {
      let v = 0;
      for (const o of octs) {
        const fx = px / size * o.n, fy = py / size * o.n, ix = Math.floor(fx), iy = Math.floor(fy), tx = fx - ix, ty = fy - iy;
        const at = (a, b) => o.g[(b % (o.n + 1)) * (o.n + 1) + (a % (o.n + 1))];
        const sx = tx * tx * (3 - 2 * tx), sy = ty * ty * (3 - 2 * ty);
        v += o.w * ((at(ix, iy) * (1 - sx) + at(ix + 1, iy) * sx) * (1 - sy) + (at(ix, iy + 1) * (1 - sx) + at(ix + 1, iy + 1) * sx) * sy);
      }
      if (rnd() < (kind === 'stone' ? 0.03 : 0.018)) v -= 0.45;
      const g = Math.max(0, Math.min(255, Math.round(v * 255)));
      const i = (py * size + px) * 4; img.data[i] = img.data[i + 1] = img.data[i + 2] = g; img.data[i + 3] = 255;
    }
    x.putImageData(img, 0, 0);
    return (_tex[key] = c);
  }
  function grit(ctx, cx, cy, size, m, seed) {
    ctx.save();
    ctx.globalCompositeOperation = 'overlay'; ctx.globalAlpha = m.grit;
    ctx.drawImage(texture(seed, m.stone ? 'stone' : 'metal'), cx - size, cy - size, size * 2, size * 2);
    ctx.globalCompositeOperation = 'multiply'; ctx.globalAlpha = m.grit * 0.45;
    ctx.drawImage(texture(seed + 1, m.stone ? 'stone' : 'metal'), cx - size, cy - size, size * 2, size * 2);
    ctx.restore();
  }
  /** Rust, patina, scratches and grime over the current clip. */
  function weather(ctx, cx, cy, R, m, rnd) {
    ctx.save();
    if (m.rust > 0) for (let i = 0; i < 60 * m.rust; i++) {
      const a = rnd() * TAU, d = rnd() * R, x = cx + Math.cos(a) * d, y = cy + Math.sin(a) * d, s = R * (0.01 + rnd() * 0.05);
      const g = ctx.createRadialGradient(x, y, 0, x, y, s);
      g.addColorStop(0, 'rgba(110,52,22,0.55)'); g.addColorStop(1, 'rgba(70,32,14,0)');
      ctx.fillStyle = g; ctx.fillRect(x - s, y - s, s * 2, s * 2);
    }
    if (m.patina) for (let i = 0; i < 26; i++) {
      const a = rnd() * TAU, d = rnd() * R, x = cx + Math.cos(a) * d, y = cy + Math.sin(a) * d, s = R * (0.02 + rnd() * 0.05);
      const g = ctx.createRadialGradient(x, y, 0, x, y, s);
      g.addColorStop(0, rgb(m.patina, 0.45)); g.addColorStop(1, rgb(m.patina, 0));
      ctx.fillStyle = g; ctx.fillRect(x - s, y - s, s * 2, s * 2);
    }
    ctx.lineCap = 'round';
    for (let i = 0; i < 40; i++) {
      const a = rnd() * TAU, d = rnd() * R, x = cx + Math.cos(a) * d, y = cy + Math.sin(a) * d;
      const ang = rnd() * TAU, len = R * (0.03 + rnd() * 0.12);
      ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + Math.cos(ang) * len, y + Math.sin(ang) * len);
      ctx.lineWidth = Math.max(0.4, R * 0.004); ctx.strokeStyle = rnd() < 0.5 ? 'rgba(0,0,0,0.45)' : rgb(m.spec, 0.22); ctx.stroke();
    }
    if (m.stone) for (let i = 0; i < 6; i++) {            // cracks
      let a = rnd() * TAU, d = R * (0.85 + rnd() * 0.1), x = cx + Math.cos(a) * d, y = cy + Math.sin(a) * d;
      ctx.beginPath(); ctx.moveTo(x, y);
      for (let k = 0; k < 5; k++) { a += (rnd() - 0.5) * 0.6; x += Math.cos(a + 1.3) * R * 0.03; y += Math.sin(a + 1.3) * R * 0.03; ctx.lineTo(x, y); }
      ctx.lineWidth = Math.max(0.5, R * 0.006); ctx.strokeStyle = 'rgba(0,0,0,0.6)'; ctx.stroke();
    }
    ctx.restore();
  }

  /** The band: deep cast shadow, tube lighting, grooves with grime, chipped edge, grit, weathering. */
  function band(ctx, shape, cx, cy, r, w, m, opts, rnd) {
    const grooves = (opts && opts.grooves) || [0.5];
    ctx.save();
    // the band's drop shadow falls outward only: nothing hazes the picture
    ctx.beginPath(); ctx.rect(cx - r * 10, cy - r * 10, r * 20, r * 20); shapePath(ctx, shape, cx, cy, r, true); ctx.clip('evenodd');
    ctx.shadowColor = 'rgba(0,0,0,0.9)'; ctx.shadowBlur = w * 1.7; ctx.shadowOffsetX = w * 0.25; ctx.shadowOffsetY = w * 0.5;
    shapePath(ctx, shape, cx, cy, r + w * 0.5); ctx.lineWidth = w; ctx.strokeStyle = rgb(m.dark); ctx.stroke();
    ctx.restore();
    const steps = Math.max(10, Math.round(w / 0.6)), hasConic = typeof ctx.createConicGradient === 'function';
    const flat = m.stone ? 0.55 : 0.95;                     // stone is cut flatter than forged metal
    for (let k = 0; k <= steps; k++) {
      const t = k / steps, theta = (t - 0.5) * Math.PI * flat, profile = 0.5 + 0.5 * Math.cos(theta - 0.35);
      shapePath(ctx, shape, cx, cy, r + t * w);
      ctx.lineWidth = w / steps * 1.8;
      if (hasConic) {
        const g = ctx.createConicGradient(LIGHT, cx, cy);
        for (let i = 0; i <= 12; i++) {
          const ang = 0.5 + 0.5 * Math.cos(i / 12 * TAU) * (t < 0.5 ? -0.55 : 0.75);
          g.addColorStop(i / 12, rgb(shade(m, 0.1 + (m.stone ? 0.6 : 0.8) * profile * (0.55 + 0.45 * ang))));
        }
        ctx.strokeStyle = g;
      } else ctx.strokeStyle = rgb(shade(m, 0.2 + 0.7 * profile));
      ctx.stroke();
    }
    ctx.save();
    ctx.beginPath(); shapePath(ctx, shape, cx, cy, r + w, true); ctx.moveTo(cx + r, cy); shapePath(ctx, shape, cx, cy, r, true); ctx.clip('evenodd');
    grit(ctx, cx, cy, r + w, m, 11 + Math.floor(rnd() * 50));
    weather(ctx, cx, cy, r + w, m, rnd);
    ctx.restore();
    for (const f of grooves) {
      shapePath(ctx, shape, cx, cy, r + w * f); ctx.lineWidth = Math.max(0.7, w * 0.08); ctx.strokeStyle = 'rgba(8,6,4,0.8)'; ctx.stroke();
      shapePath(ctx, shape, cx, cy, r + w * f + Math.max(0.6, w * 0.06)); ctx.lineWidth = Math.max(0.4, w * 0.03); ctx.strokeStyle = rgb(m.spec, 0.2); ctx.stroke();
    }
    // chipped outer edge
    for (let i = 0; i < 14; i++) {
      const a = rnd() * TAU, [x, y] = onShape(shape, cx, cy, r + w * 0.97, a), s = w * (0.08 + rnd() * 0.16);
      ctx.beginPath(); ctx.arc(x, y, s, 0, TAU); ctx.fillStyle = 'rgba(6,6,7,0.85)'; ctx.fill();
    }
    // shadow cast onto the picture
    ctx.save();
    ctx.beginPath(); shapePath(ctx, shape, cx, cy, r); ctx.clip();
    for (let i = 1; i <= 6; i++) {
      shapePath(ctx, shape, cx + w * 0.05 * i, cy + w * 0.07 * i, r);
      ctx.lineWidth = w * 0.2; ctx.strokeStyle = 'rgba(0,0,0,' + (0.12 - i * 0.016) + ')'; ctx.stroke();
    }
    ctx.restore();
    shapePath(ctx, shape, cx, cy, r - w * 0.02); ctx.lineWidth = w * 0.12; ctx.strokeStyle = 'rgba(0,0,0,0.65)'; ctx.stroke();
  }

  function rivet(ctx, x, y, s, m) {
    const sh = ctx.createRadialGradient(x + s * 0.3, y + s * 0.4, 0, x + s * 0.3, y + s * 0.4, s * 1.7);
    sh.addColorStop(0, 'rgba(0,0,0,0.7)'); sh.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = sh; ctx.beginPath(); ctx.arc(x + s * 0.3, y + s * 0.4, s * 1.7, 0, TAU); ctx.fill();
    const g = ctx.createRadialGradient(x - s * 0.4, y - s * 0.45, s * 0.05, x, y, s);
    g.addColorStop(0, rgb(m.spec)); g.addColorStop(0.35, rgb(m.light)); g.addColorStop(0.8, rgb(m.mid)); g.addColorStop(1, rgb(m.dark));
    ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, s, 0, TAU); ctx.fill();
  }
  function rivets(ctx, shape, cx, cy, r, w, m, n, off) {
    for (let i = 0; i < n; i++) { const [x, y] = onShape(shape, cx, cy, r + w / 2, (off || 0) - Math.PI / 2 + (i + 0.5) * TAU / n); rivet(ctx, x, y, w * 0.16, m); }
  }

  /** Bevelled plate with grit (wings, crest, spikes). */
  function plate(ctx, pathFn, m, bright, seed) {
    pathFn(); ctx.fillStyle = rgb(shade(m, bright)); ctx.fill();
    ctx.save(); pathFn(); ctx.clip(); grit(ctx, 0, 0, 3000, m, seed); ctx.restore();
    ctx.save(); ctx.translate(-0.7, -0.7); pathFn(); ctx.lineWidth = 1.2; ctx.strokeStyle = rgb(m.spec, 0.45); ctx.stroke(); ctx.restore();
    ctx.save(); ctx.translate(0.8, 0.8); pathFn(); ctx.lineWidth = 1.3; ctx.strokeStyle = rgb(m.dark, 0.85); ctx.stroke(); ctx.restore();
  }
  function castShadow(ctx, pathFn, r) {
    ctx.save(); ctx.shadowColor = 'rgba(0,0,0,0.85)'; ctx.shadowBlur = r * 0.12; ctx.shadowOffsetX = r * 0.04; ctx.shadowOffsetY = r * 0.07;
    pathFn(); ctx.fillStyle = 'rgba(0,0,0,0.6)'; ctx.fill(); ctx.restore();
  }

  function bladeWing(ctx, cx, cy, r, dir, m, n, reach, rnd) {
    for (let i = n - 1; i >= 0; i--) {
      const t = n === 1 ? 0 : i / (n - 1), a = -1.05 + t * 1.35, len = r * (reach - t * 0.38);
      const bx = cx + dir * r * 0.86, by = cy - r * 0.2 + t * r * 0.4;
      const tx = bx + dir * Math.cos(a) * len, ty = by + Math.sin(a) * len, nx = -Math.sin(a), ny = Math.cos(a), wd = r * (0.11 - t * 0.02);
      const path = () => {
        ctx.beginPath();
        ctx.moveTo(bx - dir * nx * wd * 0.3, by - ny * wd);
        ctx.quadraticCurveTo(bx + dir * Math.cos(a) * len * 0.55 - dir * nx * wd, by + Math.sin(a) * len * 0.55 - ny * wd * 1.2, tx, ty);
        ctx.quadraticCurveTo(bx + dir * Math.cos(a) * len * 0.6 + dir * nx * wd * 0.6, by + Math.sin(a) * len * 0.6 + ny * wd * 0.6, bx, by + ny * wd * 0.8);
        ctx.closePath();
      };
      castShadow(ctx, path, r);
      plate(ctx, path, m, 0.4 - t * 0.12, 20 + i + Math.floor(rnd() * 9));
      ctx.beginPath(); ctx.moveTo(bx, by); ctx.lineTo(bx + (tx - bx) * 0.9, by + (ty - by) * 0.9);
      ctx.lineWidth = Math.max(0.6, r * 0.012); ctx.strokeStyle = rgb(m.spec, 0.28); ctx.stroke();
    }
  }
  function crest(ctx, cx, cy, r, m, stone, rnd) {
    const top = cy - r * (1.38 + rnd() * 0.1), base = cy - r * 1.08, hw = r * (0.22 + rnd() * 0.08);
    const path = () => { ctx.beginPath(); ctx.moveTo(cx - hw, base); ctx.quadraticCurveTo(cx - hw * 0.9, top + r * 0.12, cx, top); ctx.quadraticCurveTo(cx + hw * 0.9, top + r * 0.12, cx + hw, base); ctx.closePath(); };
    castShadow(ctx, path, r); plate(ctx, path, m, 0.4, 41);
    if (stone) gem(ctx, cx, base - r * 0.13, r * 0.07, stone);
  }
  function gem(ctx, x, y, s, col) {
    ctx.beginPath(); ctx.arc(x, y, s * 1.3, 0, TAU); ctx.fillStyle = 'rgba(0,0,0,0.7)'; ctx.fill();
    const g = ctx.createRadialGradient(x - s * 0.3, y - s * 0.35, s * 0.05, x, y, s);
    g.addColorStop(0, 'rgba(255,255,255,0.7)'); g.addColorStop(0.25, col); g.addColorStop(1, 'rgba(0,0,0,0.92)');
    ctx.beginPath(); ctx.arc(x, y, s, 0, TAU); ctx.fillStyle = g; ctx.fill();
  }
  /** Spikes around the frame, following its shape (round, rounded square, diamond). */
  function spikes(ctx, shape, cx, cy, r, m, n, len, rot) {
    const P = (a, rad) => onShape(shape, cx, cy, rad, a);
    for (let i = 0; i < n; i++) {
      const a = (rot || 0) + i * TAU / n, w = 0.075, b = r * 1.18, t = r * (1.18 + len), lit = 0.5 + 0.5 * Math.cos(a - LIGHT);
      const [bx, by] = P(a, b), [tx, ty] = P(a, t);
      for (const side of [-1, 1]) {
        const [sx, sy] = P(a + side * w, b);
        ctx.beginPath(); ctx.moveTo(bx, by); ctx.lineTo(sx, sy); ctx.lineTo(tx, ty); ctx.closePath();
        ctx.fillStyle = rgb(shade(m, side < 0 ? 0.18 + 0.55 * lit : 0.12 + 0.28 * lit)); ctx.fill();
      }
    }
  }

  // ── Shared sculpting helpers ──
  /** Fill a path like a lit, gritty, sculpted surface (light from the top-left). */
  function sculpt(ctx, pathFn, m, box, seed, opts) {
    const [x0, y0, x1, y1] = box, bright = (opts && opts.bright) || 0;
    pathFn();
    const g = ctx.createLinearGradient(x0, y0, x1, y1);
    g.addColorStop(0, rgb(shade(m, 0.82 + bright))); g.addColorStop(0.45, rgb(shade(m, 0.5 + bright)));
    g.addColorStop(1, rgb(shade(m, 0.12 + bright * 0.5)));
    ctx.fillStyle = g; ctx.fill();
    ctx.save(); pathFn(); ctx.clip();
    if (opts && opts.fine) fineGrain(ctx, x0, y0, x1, y1, m, seed); else grit(ctx, (x0 + x1) / 2, (y0 + y1) / 2, Math.max(x1 - x0, y1 - y0), m, seed);
    // soft occlusion on the shadow side
    const og = ctx.createLinearGradient(x0, y0, x1, y1);
    og.addColorStop(0.55, 'rgba(0,0,0,0)'); og.addColorStop(1, 'rgba(0,0,0,0.45)');
    ctx.fillStyle = og; ctx.fillRect(x0 - 5, y0 - 5, x1 - x0 + 10, y1 - y0 + 10);
    ctx.restore();
    if (opts && opts.soft) return;
    ctx.save(); ctx.translate(-0.6, -0.6); pathFn(); ctx.lineWidth = 1; ctx.strokeStyle = rgb(m.spec, 0.35); ctx.stroke(); ctx.restore();
    ctx.save(); pathFn(); ctx.lineWidth = 1.1; ctx.strokeStyle = rgb(m.dark, 0.85); ctx.stroke(); ctx.restore();
  }
  /** Tapered tube along points [{x,y,w}] (neck, tail, limbs), lit across its width. */
  function tube(ctx, pts, m, seed, opts) {
    const n = pts.length, L = [], R = [];
    for (let i = 0; i < n; i++) {
      const p = pts[i], q = pts[Math.min(n - 1, i + 1)], o = pts[Math.max(0, i - 1)];
      const a = Math.atan2(q.y - o.y, q.x - o.x), nx = -Math.sin(a), ny = Math.cos(a);
      L.push([p.x + nx * p.w / 2, p.y + ny * p.w / 2]); R.push([p.x - nx * p.w / 2, p.y - ny * p.w / 2]);
    }
    const path = () => {
      ctx.beginPath(); ctx.moveTo(L[0][0], L[0][1]);
      for (let i = 1; i < n; i++) ctx.lineTo(L[i][0], L[i][1]);
      for (let i = n - 1; i >= 0; i--) ctx.lineTo(R[i][0], R[i][1]);
      ctx.closePath();
    };
    let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
    for (const [x, y] of L.concat(R)) { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
    if (!(opts && opts.soft)) castShadow(ctx, path, Math.max(x1 - x0, y1 - y0) * 0.6);
    sculpt(ctx, path, m, [x0, y0, x1, y1], seed, opts);
    // a highlight running along the lit side
    ctx.save(); ctx.lineCap = 'round'; ctx.beginPath();
    for (let i = 1; i < n - 1; i++) { const p = pts[i], [lx, ly] = L[i]; const hx = p.x + (lx - p.x) * 0.55, hy = p.y + (ly - p.y) * 0.55; i === 1 ? ctx.moveTo(hx, hy) : ctx.lineTo(hx, hy); }
    ctx.lineWidth = Math.max(0.6, pts[1].w * 0.12); ctx.strokeStyle = rgb(m.spec, 0.22); ctx.stroke(); ctx.restore();
    return { L, R, path };
  }
  /** Catmull-Rom points between control points, with widths. */
  function spline(ctrl, steps) {
    const out = [];
    for (let i = 0; i < ctrl.length - 1; i++) {
      const p0 = ctrl[Math.max(0, i - 1)], p1 = ctrl[i], p2 = ctrl[i + 1], p3 = ctrl[Math.min(ctrl.length - 1, i + 2)];
      for (let k = 0; k < steps; k++) {
        const t = k / steps, t2 = t * t, t3 = t2 * t;
        const f = (a, b, c, d) => 0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3);
        out.push({ x: f(p0.x, p1.x, p2.x, p3.x), y: f(p0.y, p1.y, p2.y, p3.y), w: p1.w + (p2.w - p1.w) * t });
      }
    }
    out.push({ ...ctrl[ctrl.length - 1] });
    return out;
  }
  function talon(ctx, x, y, a, len, w, m, bend) {
    if (bend !== undefined) return talonB(ctx, x, y, a, len, w, m, bend);
    ctx.beginPath();
    ctx.moveTo(x + Math.cos(a + 1.57) * w, y + Math.sin(a + 1.57) * w);
    ctx.quadraticCurveTo(x + Math.cos(a) * len * 0.7 + Math.cos(a + 1.57) * w * 0.4, y + Math.sin(a) * len * 0.7 + Math.sin(a + 1.57) * w * 0.4,
      x + Math.cos(a + 0.5) * len, y + Math.sin(a + 0.5) * len);
    ctx.quadraticCurveTo(x + Math.cos(a) * len * 0.6, y + Math.sin(a) * len * 0.6, x + Math.cos(a - 1.57) * w, y + Math.sin(a - 1.57) * w);
    ctx.closePath();
    const g = ctx.createLinearGradient(x, y, x + Math.cos(a) * len, y + Math.sin(a) * len);
    g.addColorStop(0, rgb(shade(m, 0.35))); g.addColorStop(1, '#d9d2c2');
    ctx.fillStyle = g; ctx.fill();
  }

  /** Hooked claw curving by [bend] radians (sign picks the side), thick at the base. */
  function talonB(ctx, x, y, a, len, w, m, bend) {
    const sd = bend >= 0 ? 1 : -1, tip = [x + Math.cos(a + bend) * len, y + Math.sin(a + bend) * len];
    const outer = [x + Math.cos(a) * len * 0.75 - Math.cos(a + sd * 1.57) * w * 0.5, y + Math.sin(a) * len * 0.75 - Math.sin(a + sd * 1.57) * w * 0.5];
    const inner = [x + Math.cos(a + bend * 0.5) * len * 0.5, y + Math.sin(a + bend * 0.5) * len * 0.5];
    ctx.beginPath();
    ctx.moveTo(x - Math.cos(a + sd * 1.57) * w, y - Math.sin(a + sd * 1.57) * w);
    ctx.quadraticCurveTo(outer[0], outer[1], tip[0], tip[1]);
    ctx.quadraticCurveTo(inner[0], inner[1], x + Math.cos(a + sd * 1.57) * w, y + Math.sin(a + sd * 1.57) * w);
    ctx.closePath();
    const g = ctx.createLinearGradient(x, y, tip[0], tip[1]);
    g.addColorStop(0, rgb(shade(m, 0.3))); g.addColorStop(0.6, '#a69d8a'); g.addColorStop(1, '#ece6d6');
    ctx.fillStyle = g; ctx.fill(); ctx.lineWidth = 0.6; ctx.strokeStyle = 'rgba(0,0,0,0.6)'; ctx.stroke();
  }

  // ── The dragon: an upright figure in profile (drawn facing right, mirrored for left). ──
  // Units: U (about a third of the picture radius). Origin = where the front claws grip.
  function dragonFigure(ctx, U, m, rnd, cfg) {
    const S = (x, y) => ({ x: x * U, y: y * U });
    const open = cfg.open, hornStyle = cfg.horns, spread = cfg.spread;

    // far wing (behind everything, darker, smaller)
    wingDraw(ctx, U, m, rnd, { far: true, spread });
    // tail
    const tailCtrl = cfg.tail.map(([x, y, w]) => ({ x: x * U, y: y * U, w: w * U }));
    const tailPts = spline(tailCtrl, 8);
    tube(ctx, tailPts, m, 131, { bright: -0.05 });
    { const e = tailPts[tailPts.length - 1], p = tailPts[tailPts.length - 3], a = Math.atan2(e.y - p.y, e.x - p.x);
      const s = U * 0.32, path = () => { ctx.beginPath(); ctx.moveTo(e.x, e.y);
        ctx.quadraticCurveTo(e.x + Math.cos(a + 1.1) * s, e.y + Math.sin(a + 1.1) * s, e.x + Math.cos(a) * s * 1.5, e.y + Math.sin(a) * s * 1.5);
        ctx.quadraticCurveTo(e.x + Math.cos(a - 1.1) * s, e.y + Math.sin(a - 1.1) * s, e.x, e.y); ctx.closePath(); };
      castShadow(ctx, path, U); sculpt(ctx, path, m, [e.x - s, e.y - s, e.x + s, e.y + s], 137); }
    // hind leg + haunch
    tube(ctx, spline([S(-0.95, -0.75), S(-0.55, -0.25), S(-0.62, -0.02)].map((p, i) => ({ ...p, w: U * [0.75, 0.42, 0.26][i] })), 6), m, 141);
    for (let k = -1; k <= 1; k++) talon(ctx, -0.62 * U + k * 0.1 * U, -0.02 * U, 0.6 + k * 0.35, U * 0.24, U * 0.045, m);
    // torso: haunch to chest
    const torso = () => {
      ctx.beginPath();
      ctx.moveTo(-1.55 * U, -0.75 * U);
      ctx.bezierCurveTo(-1.5 * U, -1.45 * U, -0.6 * U, -1.95 * U, 0.05 * U, -1.85 * U);
      ctx.bezierCurveTo(0.55 * U, -1.75 * U, 0.72 * U, -1.1 * U, 0.48 * U, -0.62 * U);
      ctx.bezierCurveTo(0.2 * U, -0.18 * U, -0.6 * U, -0.25 * U, -1.05 * U, -0.32 * U);
      ctx.bezierCurveTo(-1.4 * U, -0.38 * U, -1.6 * U, -0.5 * U, -1.55 * U, -0.75 * U);
      ctx.closePath();
    };
    castShadow(ctx, torso, U * 1.5);
    sculpt(ctx, torso, m, [-1.6 * U, -1.95 * U, 0.75 * U, -0.2 * U], 151);
    // belly plates on the chest
    ctx.save(); torso(); ctx.clip();
    for (let i = 0; i < 7; i++) {
      const t = i / 7, x = (0.42 - t * 0.55) * U, y = (-1.55 + t * 1.0) * U;
      ctx.beginPath(); ctx.ellipse(x, y, U * 0.28, U * 0.07, -0.9 + t * 0.4, 0, Math.PI);
      ctx.lineWidth = Math.max(0.6, U * 0.03); ctx.strokeStyle = 'rgba(0,0,0,0.45)'; ctx.stroke();
      ctx.beginPath(); ctx.ellipse(x - U * 0.01, y - U * 0.015, U * 0.27, U * 0.065, -0.9 + t * 0.4, 0.2, Math.PI - 0.2);
      ctx.lineWidth = Math.max(0.4, U * 0.015); ctx.strokeStyle = rgb(m.spec, 0.18); ctx.stroke();
    }
    // scale texture on the flank
    const rr = rng(77);
    for (let i = 0; i < 40; i++) {
      const x = (-1.4 + rr() * 1.3) * U, y = (-1.6 + rr() * 1.2) * U;
      ctx.beginPath(); ctx.arc(x, y, U * 0.07, 0.2 * Math.PI, 0.8 * Math.PI);
      ctx.lineWidth = Math.max(0.5, U * 0.014); ctx.strokeStyle = 'rgba(0,0,0,0.35)'; ctx.stroke();
    }
    ctx.restore();
    // near wing (folded at the elbow over the back, spread behind)
    wingDraw(ctx, U, m, rnd, { far: false, spread });
    // neck: S-curve from chest to head, belly plates in front, spines behind
    const neckCtrl = [S(0.1, -1.6), S(0.42, -2.1), S(0.5, -2.65), S(0.32, -3.05)];
    const neckW = [0.62, 0.5, 0.42, 0.36];
    const neckPts = spline(neckCtrl.map((p, i) => ({ ...p, w: U * neckW[i] })), 8);
    const neck = tube(ctx, neckPts, m, 161, { bright: -0.25 });
    scaleSkin(ctx, neckPts, m, rnd, { rows: 6 });
    ctx.save(); neck.path(); ctx.clip();
    for (let i = 2; i < neckPts.length - 1; i += 2) {           // throat plates on the front (right) side
      const p = neckPts[i], [rx, ry] = neck.R[i];
      ctx.beginPath(); ctx.moveTo(p.x + (rx - p.x) * 0.1, p.y + (ry - p.y) * 0.1); ctx.lineTo(rx, ry);
      ctx.lineWidth = Math.max(0.5, U * 0.02); ctx.strokeStyle = 'rgba(0,0,0,0.4)'; ctx.stroke();
    }
    ctx.restore();
    for (let i = 1; i < neckPts.length - 2; i += 3) {           // spines along the back (left) side
      const [lx, ly] = neck.L[i], p = neckPts[i], a = Math.atan2(ly - p.y, lx - p.x) - 0.5, len = U * 0.24;
      ctx.beginPath(); ctx.moveTo(lx + Math.cos(a + 1.6) * U * 0.05, ly + Math.sin(a + 1.6) * U * 0.05);
      ctx.lineTo(lx + Math.cos(a) * len, ly + Math.sin(a) * len); ctx.lineTo(lx + Math.cos(a - 1.6) * U * 0.05, ly + Math.sin(a - 1.6) * U * 0.05);
      ctx.closePath(); ctx.fillStyle = rgb(shade(m, 0.45)); ctx.fill(); ctx.lineWidth = 0.8; ctx.strokeStyle = rgb(m.dark, 0.8); ctx.stroke();
    }
    // back spines along the torso ridge
    for (let i = 0; i < 6; i++) {
      const t = (i + 0.5) / 6, x = (-1.45 + t * 1.4) * U, y = (-1.4 - Math.sin(t * Math.PI) * 0.5) * U, len = U * (0.18 + Math.sin(t * Math.PI) * 0.12);
      ctx.beginPath(); ctx.moveTo(x - U * 0.07, y + U * 0.04); ctx.lineTo(x - U * 0.04, y - len); ctx.lineTo(x + U * 0.07, y + U * 0.03); ctx.closePath();
      ctx.fillStyle = rgb(shade(m, 0.4)); ctx.fill(); ctx.lineWidth = 0.8; ctx.strokeStyle = rgb(m.dark, 0.8); ctx.stroke();
    }
    // foreleg gripping the frame
    tube(ctx, spline([S(0.3, -1.05), S(0.62, -0.62), S(0.42, -0.25), S(0.42, -0.02)].map((p, i) => ({ ...p, w: U * [0.42, 0.3, 0.24, 0.2][i] })), 6), m, 171);
    for (let k = -1; k <= 1; k++) talon(ctx, 0.42 * U + k * 0.09 * U, -0.02 * U, 1.2 + k * 0.38, U * 0.26, U * 0.045, m);
    // head
    headDraw(ctx, U, m, rnd, { x: 0.28 * U, y: -3.05 * U, L: 1.6 * U, open, hornStyle });
  }

  function wingDraw(ctx, U, m, rnd, o) {
    ctx.save();
    if (o.far) { ctx.translate(0.55 * U, -0.25 * U); ctx.scale(0.86, 0.86); }
    const sh = { x: -0.2 * U, y: -1.6 * U };                                 // shoulder
    const lift = o.spread ? 1 : 0.55;
    const el = { x: -0.95 * U, y: (-2.2 - 0.6 * lift) * U };                 // elbow
    const wr = { x: (-0.25 - 0.4 * (1 - lift)) * U, y: (-2.85 - 0.95 * lift) * U };   // wrist (high)
    const fingers = o.spread
      ? [[-2.55, -3.95], [-3.05, -2.65], [-2.75, -1.45], [-1.95, -0.7]]
      : [[-1.85, -3.4], [-2.2, -2.5], [-2.0, -1.55], [-1.55, -0.85]];
    const tips = fingers.map(([x, y]) => ({ x: x * U, y: y * U }));
    const dark = o.far ? -0.12 : 0;
    // membrane: shoulder -> elbow -> wrist -> scalloped edge between finger tips -> back to the body
    const memb = () => {
      ctx.beginPath(); ctx.moveTo(sh.x, sh.y); ctx.lineTo(el.x, el.y); ctx.lineTo(wr.x, wr.y); ctx.lineTo(tips[0].x, tips[0].y);
      for (let i = 1; i < tips.length; i++) {
        const a = tips[i - 1], b = tips[i];
        ctx.quadraticCurveTo((a.x + b.x) / 2 + (wr.x - (a.x + b.x) / 2) * 0.32, (a.y + b.y) / 2 + (wr.y - (a.y + b.y) / 2) * 0.32, b.x, b.y);
      }
      ctx.quadraticCurveTo(-1.4 * U, -0.75 * U, -1.2 * U, -0.95 * U);
      ctx.closePath();
    };
    castShadow(ctx, memb, U * 1.5);
    memb();
    const mg = ctx.createLinearGradient(wr.x, wr.y, tips[2].x, tips[2].y);
    mg.addColorStop(0, rgb(shade(m, 0.3 + dark))); mg.addColorStop(1, rgb(shade(m, 0.12 + dark)));
    ctx.fillStyle = mg; ctx.fill();
    ctx.save(); memb(); ctx.clip(); grit(ctx, -1.5 * U, -2.3 * U, 3 * U, m, 181);
    // veins / folds from the wrist into each scallop
    for (let i = 0; i < tips.length - 1; i++) {
      const a = tips[i], b = tips[i + 1], mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
      ctx.beginPath(); ctx.moveTo(wr.x, wr.y); ctx.quadraticCurveTo((wr.x + mx) / 2 + U * 0.08, (wr.y + my) / 2, mx + (wr.x - mx) * 0.3, my + (wr.y - my) * 0.3);
      ctx.lineWidth = Math.max(0.5, U * 0.02); ctx.strokeStyle = 'rgba(0,0,0,0.35)'; ctx.stroke();
    }
    ctx.restore();
    // bones: arm (shoulder-elbow-wrist) thick, fingers thinner, all lit
    const bone = (a, b, w) => {
      ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.lineCap = 'round';
      ctx.lineWidth = w * 1.35; ctx.strokeStyle = rgb(m.dark); ctx.stroke();
      ctx.lineWidth = w; ctx.strokeStyle = rgb(shade(m, 0.5 + dark)); ctx.stroke();
      ctx.lineWidth = w * 0.35; ctx.strokeStyle = rgb(shade(m, 0.78 + dark), 0.7); ctx.stroke();
    };
    bone(sh, el, U * 0.17); bone(el, wr, U * 0.14);
    for (const t of tips) bone(wr, t, U * 0.065);
    // knuckle + thumb claw at the wrist
    ctx.beginPath(); ctx.arc(wr.x, wr.y, U * 0.09, 0, TAU); ctx.fillStyle = rgb(shade(m, 0.55 + dark)); ctx.fill();
    talon(ctx, wr.x, wr.y, -1.2, U * 0.26, U * 0.05, m);
    ctx.restore();
  }

  function headDraw(ctx, U, m, rnd, h) {
    const L = h.L;
    ctx.save(); ctx.translate(h.x, h.y);
    const P = (x, y) => [x * L, y * L];
    // horns behind the skull
    const horn = (bx, by, len, up, curl, wd, seed) => {
      const pts = [];
      for (let i = 0; i <= 10; i++) {
        const t = i / 10, ang = Math.PI + up - curl * t * t;      // sweeps back, curling
        const r = len * t;
        pts.push({ x: bx + Math.cos(ang) * r, y: by + Math.sin(ang) * r * 0.9, w: wd * 1.45 * (1 - t * 0.88) });
      }
      const tb = tube(ctx, pts, m, seed, { bright: 0.08 });
      ctx.save(); tb.path(); ctx.clip();                          // ridges
      for (let i = 1; i < 9; i++) { const p = pts[i]; ctx.beginPath(); ctx.arc(p.x, p.y, p.w * 0.55, 0, TAU); ctx.lineWidth = Math.max(0.5, L * 0.008); ctx.strokeStyle = 'rgba(0,0,0,0.3)'; ctx.stroke(); }
      ctx.restore();
    };
    if (h.hornStyle === 0) { horn(L * 0.12, -L * 0.24, L * 0.95, -0.55, -0.9, L * 0.12, 201); horn(L * 0.02, -L * 0.12, L * 0.6, -0.2, -0.6, L * 0.08, 203); }
    else if (h.hornStyle === 1) { horn(L * 0.15, -L * 0.26, L * 1.15, -0.95, -1.4, L * 0.14, 205); }
    else { for (let k = 0; k < 4; k++) horn(L * (0.2 - k * 0.07), -L * (0.27 - k * 0.04), L * (0.5 - k * 0.06), -0.35 + k * 0.08, -0.5, L * 0.06, 207 + k); }
    // lower jaw (behind the skull when open)
    const jaw = () => {
      ctx.beginPath(); ctx.moveTo(...P(0.05, 0.12));
      if (h.open) { ctx.lineTo(...P(0.42, 0.16)); ctx.quadraticCurveTo(...P(0.75, 0.3), ...P(0.9, 0.36)); ctx.quadraticCurveTo(...P(0.6, 0.44), ...P(0.25, 0.36)); }
      else { ctx.lineTo(...P(0.5, 0.1)); ctx.quadraticCurveTo(...P(0.82, 0.13), ...P(0.9, 0.16)); ctx.quadraticCurveTo(...P(0.55, 0.3), ...P(0.2, 0.3)); }
      ctx.quadraticCurveTo(...P(0.02, 0.3), ...P(0.05, 0.12)); ctx.closePath();
    };
    if (h.open) {   // inside of the mouth
      ctx.beginPath(); ctx.moveTo(...P(0.42, 0.06)); ctx.lineTo(...P(0.98, 0.0)); ctx.lineTo(...P(0.9, 0.34)); ctx.lineTo(...P(0.42, 0.16)); ctx.closePath();
      const ig = ctx.createLinearGradient(...P(0.4, 0), ...P(0.9, 0.3)); ig.addColorStop(0, '#1a0805'); ig.addColorStop(1, '#4a1a10'); ctx.fillStyle = ig; ctx.fill();
    }
    castShadow(ctx, jaw, L * 0.6); sculpt(ctx, jaw, m, [0, 0, L * 0.9, L * 0.44], 211, { bright: -0.08 });
    // skull
    const skull = () => {
      ctx.beginPath(); ctx.moveTo(...P(-0.02, 0.18));
      ctx.quadraticCurveTo(...P(-0.08, -0.12), ...P(0.12, -0.28));       // back of skull
      ctx.quadraticCurveTo(...P(0.3, -0.38), ...P(0.5, -0.3));           // crown to brow
      ctx.quadraticCurveTo(...P(0.6, -0.27), ...P(0.68, -0.2));          // brow drop
      ctx.lineTo(...P(0.9, -0.14));                                      // snout ridge
      ctx.quadraticCurveTo(...P(1.04, -0.11), ...P(1.02, -0.02));        // nose
      ctx.quadraticCurveTo(...P(0.99, 0.05), ...P(0.9, 0.06));           // upper lip
      ctx.lineTo(...P(0.42, 0.1));                                       // mouth line
      ctx.quadraticCurveTo(...P(0.2, 0.2), ...P(-0.02, 0.18));
      ctx.closePath();
    };
    castShadow(ctx, skull, L * 0.7); sculpt(ctx, skull, m, [-0.1 * L, -0.38 * L, 1.04 * L, 0.2 * L], 213, { bright: 0.04 });
    // teeth
    ctx.fillStyle = '#e2dccb';
    for (let i = 0; i < 6; i++) { const x = 0.48 + i * 0.085, y = 0.085 - i * 0.004; ctx.beginPath(); ctx.moveTo(...P(x, y)); ctx.lineTo(...P(x + 0.02, y + (h.open ? 0.09 : 0.05))); ctx.lineTo(...P(x + 0.045, y)); ctx.fill(); }
    if (h.open) for (let i = 0; i < 5; i++) { const x = 0.5 + i * 0.08, y = 0.2 + i * 0.03; ctx.beginPath(); ctx.moveTo(...P(x, y)); ctx.lineTo(...P(x + 0.02, y - 0.07)); ctx.lineTo(...P(x + 0.045, y + 0.01)); ctx.fill(); }
    // brow ridge (heavy, overhanging the eye)
    const brow = () => { ctx.beginPath(); ctx.moveTo(...P(0.22, -0.26)); ctx.quadraticCurveTo(...P(0.46, -0.36), ...P(0.68, -0.2)); ctx.quadraticCurveTo(...P(0.5, -0.2), ...P(0.24, -0.18)); ctx.closePath(); };
    sculpt(ctx, brow, m, [0.2 * L, -0.36 * L, 0.7 * L, -0.18 * L], 217, { bright: 0.12 });
    // eye socket + ember eye
    ctx.beginPath(); ctx.ellipse(...P(0.47, -0.15), L * 0.1, L * 0.05, -0.15, 0, TAU); ctx.fillStyle = 'rgba(0,0,0,0.75)'; ctx.fill();
    ctx.save(); ctx.shadowColor = 'rgba(255,110,30,0.95)'; ctx.shadowBlur = L * 0.12;
    ctx.beginPath(); ctx.ellipse(...P(0.48, -0.15), L * 0.065, L * 0.026, -0.15, 0, TAU);
    const eg = ctx.createRadialGradient(...P(0.47, -0.155), 0, ...P(0.48, -0.15), L * 0.065); eg.addColorStop(0, '#ffe08a'); eg.addColorStop(0.6, '#ff8a2a'); eg.addColorStop(1, '#8a2a08');
    ctx.fillStyle = eg; ctx.fill(); ctx.restore();
    ctx.beginPath(); ctx.ellipse(...P(0.485, -0.15), L * 0.008, L * 0.024, 0, 0, TAU); ctx.fillStyle = '#120400'; ctx.fill();
    // nostril, snout scales, jaw muscle line
    ctx.beginPath(); ctx.ellipse(...P(0.95, -0.07), L * 0.03, L * 0.014, -0.5, 0, TAU); ctx.fillStyle = 'rgba(0,0,0,0.8)'; ctx.fill();
    ctx.save(); skull(); ctx.clip();
    for (let i = 0; i < 9; i++) { const x = 0.7 + (i % 3) * 0.08, y = -0.12 + Math.floor(i / 3) * 0.05; ctx.beginPath(); ctx.arc(...P(x, y), L * 0.03, 0.1 * Math.PI, 0.9 * Math.PI); ctx.lineWidth = Math.max(0.5, L * 0.008); ctx.strokeStyle = 'rgba(0,0,0,0.35)'; ctx.stroke(); }
    ctx.beginPath(); ctx.moveTo(...P(0.42, 0.1)); ctx.quadraticCurveTo(...P(0.2, 0.0), ...P(0.12, -0.12)); ctx.lineWidth = Math.max(0.6, L * 0.012); ctx.strokeStyle = 'rgba(0,0,0,0.4)'; ctx.stroke();
    ctx.restore();
    // cheek frill: spikes sweeping back from the jaw hinge
    for (let k = 0; k < 3; k++) {
      const bx = 0.06 * L, by = (0.02 + k * 0.08) * L, a = Math.PI - 0.15 + k * 0.32, len = L * (0.32 - k * 0.06);
      ctx.beginPath(); ctx.moveTo(bx, by - L * 0.03); ctx.lineTo(bx + Math.cos(a) * len, by + Math.sin(a) * len); ctx.lineTo(bx, by + L * 0.03); ctx.closePath();
      ctx.fillStyle = rgb(shade(m, 0.42)); ctx.fill(); ctx.lineWidth = 0.8; ctx.strokeStyle = rgb(m.dark, 0.85); ctx.stroke();
    }
    ctx.restore();
  }

  // ── v6 detail: overlapping scale skin, bony wing, plated head ──
  /** Cover a tube (points with widths) in overlapping, individually lit scales. */
  function scaleSkin(ctx, pts, m, rnd, opts) {
    const rows = (opts && opts.rows) || 7, big = (opts && opts.size) || 1;
    const Lx = Math.cos(LIGHT), Ly = Math.sin(LIGHT);
    // walk from tail to head so each scale overlaps the base of the one behind it
    let acc = 0;
    for (let i = 1; i < pts.length; i++) {
      const p = pts[i], q = pts[i - 1];
      const seg = Math.hypot(p.x - q.x, p.y - q.y); acc += seg;
      const sw = p.w * 0.2 * big;
      if (acc < sw * 0.62) continue;
      acc = 0;
      const t = Math.atan2(p.y - q.y, p.x - q.x), nx = -Math.sin(t), ny = Math.cos(t);
      const lit = nx * Lx + ny * Ly;                       // which edge of the tube faces the light
      for (let k = 0; k < rows; k++) {
        const kk = (k / (rows - 1)) * 2 - 1, off = (i % 2 ? 0.5 : 0) / (rows - 1) * 2;
        const kv = Math.max(-1, Math.min(1, kk + off * 0.5));
        const x = p.x + nx * kv * p.w * 0.45, y = p.y + ny * kv * p.w * 0.45;
        const s = sw * (1 - 0.45 * Math.abs(kv));
        const b = 0.32 + 0.42 * Math.max(0, kv * lit) + 0.2 * (1 - Math.abs(kv)) + (rnd() - 0.5) * 0.08;
        const tx = -Math.cos(t), ty = -Math.sin(t);         // scales point toward the tail
        ctx.beginPath();
        ctx.moveTo(x - nx * s * 0.55, y - ny * s * 0.55);
        ctx.quadraticCurveTo(x - nx * s * 0.5 + tx * s * 0.9, y - ny * s * 0.5 + ty * s * 0.9, x + tx * s * 1.15, y + ty * s * 1.15);
        ctx.quadraticCurveTo(x + nx * s * 0.5 + tx * s * 0.9, y + ny * s * 0.5 + ty * s * 0.9, x + nx * s * 0.55, y + ny * s * 0.55);
        ctx.quadraticCurveTo(x - tx * s * 0.25, y - ty * s * 0.25, x - nx * s * 0.55, y - ny * s * 0.55);
        const g = ctx.createLinearGradient(x - tx * s * 0.3, y - ty * s * 0.3, x + tx * s * 1.1, y + ty * s * 1.1);
        g.addColorStop(0, rgb(shade(m, b + 0.28))); g.addColorStop(0.5, rgb(shade(m, b))); g.addColorStop(1, rgb(shade(m, b - 0.25)));
        ctx.fillStyle = g; ctx.fill();
        ctx.lineWidth = Math.max(0.35, s * 0.1); ctx.strokeStyle = 'rgba(0,0,0,0.7)'; ctx.stroke();
        if (b > 0.55) {   // specular glint on the lit scales
          ctx.beginPath(); ctx.moveTo(x - nx * s * 0.3, y - ny * s * 0.3);
          ctx.quadraticCurveTo(x - tx * s * 0.1, y - ty * s * 0.1, x + nx * s * 0.3, y + ny * s * 0.3);
          ctx.lineWidth = Math.max(0.3, s * 0.12); ctx.strokeStyle = rgb(m.spec, Math.min(0.7, (b - 0.5) * 1.6)); ctx.stroke();
        }
      }
    }
  }

  /** A big bony wing in world space: shoulder s, opening toward dirX (1 right, -1 left), upward. */
  function wing2(ctx, s, dirX, U, m, rnd, far) {
    const k = far ? 0.85 : 1, d = far ? -0.1 : 0;
    const P = (x, y) => ({ x: s.x + dirX * x * U * k, y: s.y + y * U * k });
    const el = P(0.55, -1.1), wr = P(1.35, -2.05);
    const n = 4, tips = [];
    for (let i = 0; i < n; i++) {
      const a = -1.35 + i * (0.55 + rnd() * 0.08), len = (2.0 - i * 0.22) * (0.95 + rnd() * 0.1);
      tips.push(P(1.35 + Math.cos(a + 0.9) * len, -2.05 + Math.sin(a + 0.9) * len));
    }
    const root = P(-0.35, 0.55);                            // membrane meets the body here
    const memb = () => {
      ctx.beginPath(); ctx.moveTo(s.x, s.y); ctx.lineTo(el.x, el.y); ctx.lineTo(wr.x, wr.y); ctx.lineTo(tips[0].x, tips[0].y);
      for (let i = 1; i < n; i++) {
        const a = tips[i - 1], b = tips[i], mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
        ctx.quadraticCurveTo(mx + (wr.x - mx) * 0.42, my + (wr.y - my) * 0.42, b.x, b.y);
      }
      ctx.quadraticCurveTo((tips[n - 1].x + root.x) / 2 + (wr.x - root.x) * 0.15, (tips[n - 1].y + root.y) / 2 + (wr.y - root.y) * 0.15, root.x, root.y);
      ctx.closePath();
    };
    castShadow(ctx, memb, U * 1.4);
    // membrane: dark, lighter between the bones, folds running along the fingers
    memb(); ctx.fillStyle = rgb(shade(m, 0.16 + d)); ctx.fill();
    ctx.save(); memb(); ctx.clip();
    for (let i = 0; i < n; i++) {
      const a = tips[i], b = i + 1 < n ? tips[i + 1] : root;
      const cx2 = (wr.x + a.x + b.x) / 3, cy2 = (wr.y + a.y + b.y) / 3;
      const g = ctx.createRadialGradient(cx2, cy2, 0, cx2, cy2, U * 1.2 * k);
      g.addColorStop(0, rgb(shade(m, 0.42 + d), 0.55)); g.addColorStop(1, rgb(shade(m, 0.1 + d), 0));
      ctx.fillStyle = g; ctx.fillRect(cx2 - U * 2, cy2 - U * 2, U * 4, U * 4);
      for (let f = 1; f <= 3; f++) {                          // folds
        const fx = a.x + (b.x - a.x) * f / 4, fy = a.y + (b.y - a.y) * f / 4;
        ctx.beginPath(); ctx.moveTo(wr.x, wr.y); ctx.quadraticCurveTo((wr.x + fx) / 2 + dirX * U * 0.1, (wr.y + fy) / 2, fx + (wr.x - fx) * 0.22, fy + (wr.y - fy) * 0.22);
        ctx.lineWidth = Math.max(0.4, U * 0.018); ctx.strokeStyle = f === 2 ? 'rgba(0,0,0,0.45)' : rgb(m.spec, 0.08); ctx.stroke();
      }
    }
    grit(ctx, wr.x, wr.y, U * 3, m, 181);
    ctx.restore();
    // light rim along the scalloped edge
    ctx.save(); ctx.beginPath(); ctx.moveTo(tips[0].x, tips[0].y);
    for (let i = 1; i < n; i++) { const a = tips[i - 1], b = tips[i], mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2; ctx.quadraticCurveTo(mx + (wr.x - mx) * 0.42, my + (wr.y - my) * 0.42, b.x, b.y); }
    ctx.lineWidth = Math.max(0.6, U * 0.025); ctx.strokeStyle = rgb(m.spec, 0.25); ctx.stroke(); ctx.restore();
    // bones: tapered, lit, knobbed joints; scaled arm
    const bone = (a, b, w0, w1) => {
      const pts = spline([{ x: a.x, y: a.y, w: w0 }, { x: (a.x + b.x) / 2 + dirX * U * 0.05, y: (a.y + b.y) / 2 - U * 0.05, w: (w0 + w1) / 2 }, { x: b.x, y: b.y, w: w1 }], 6);
      tube(ctx, pts, m, 191, { bright: d });
      return pts;
    };
    const arm1 = bone(s, el, U * 0.36, U * 0.24), arm2 = bone(el, wr, U * 0.24, U * 0.17);
    scaleSkin(ctx, arm1, m, rnd, { rows: 4, size: 1.3 }); scaleSkin(ctx, arm2, m, rnd, { rows: 3, size: 1.3 });
    for (const t of tips) bone(wr, t, U * 0.11, U * 0.025);
    for (const j of [el, wr]) {
      const g = ctx.createRadialGradient(j.x - U * 0.05, j.y - U * 0.05, 0, j.x, j.y, U * 0.16);
      g.addColorStop(0, rgb(shade(m, 0.85 + d))); g.addColorStop(1, rgb(shade(m, 0.2 + d)));
      ctx.beginPath(); ctx.arc(j.x, j.y, U * 0.15, 0, TAU); ctx.fillStyle = g; ctx.fill();
    }
    talon(ctx, wr.x, wr.y, dirX > 0 ? -1.9 : -1.25, U * 0.38, U * 0.07, m);   // thumb claw
  }

  /** Plated, scaled head (facing right in local space; caller mirrors). */
  function head2(ctx, U, m, rnd, h) {
    headDraw(ctx, U, m, rnd, h);                           // base skull, jaw, teeth, eye, horns
    const L = h.L;
    ctx.save(); ctx.translate(h.x, h.y);
    const P = (x, y) => [x * L, y * L];
    // overlapping nasal plates along the top of the snout
    for (let i = 0; i < 5; i++) {
      const x = 0.55 + i * 0.09, y = -0.235 + i * 0.03, s = L * (0.07 - i * 0.006);
      ctx.beginPath(); ctx.moveTo(...P(x - 0.05, y + 0.02)); ctx.quadraticCurveTo(...P(x, y - 0.05), ...P(x + 0.06, y + 0.015));
      ctx.quadraticCurveTo(...P(x, y + 0.035), ...P(x - 0.05, y + 0.02));
      const g = ctx.createLinearGradient(...P(x, y - 0.05), ...P(x, y + 0.04)); g.addColorStop(0, rgb(shade(m, 0.92))); g.addColorStop(1, rgb(shade(m, 0.3)));
      ctx.fillStyle = g; ctx.fill(); ctx.lineWidth = Math.max(0.4, s * 0.12); ctx.strokeStyle = 'rgba(0,0,0,0.7)'; ctx.stroke();
    }
    // small scales over the cheek and lower jaw
    const cheek = [];
    for (let i = 0; i <= 8; i++) cheek.push({ x: L * (0.05 + i * 0.06), y: L * (0.12 + Math.sin(i / 8 * Math.PI) * 0.04), w: L * 0.16 });
    ctx.save(); ctx.beginPath(); ctx.rect(-L * 0.1, L * 0.02, L * 0.62, L * 0.3); ctx.clip(); scaleSkin(ctx, cheek, m, rnd, { rows: 4, size: 0.9 }); ctx.restore();
    // jaw spikes and chin barbs
    for (let k = 0; k < 4; k++) {
      const bx = L * (0.12 + k * 0.1), by = L * (0.27 + k * 0.015), len = L * (0.16 - k * 0.025);
      ctx.beginPath(); ctx.moveTo(bx - L * 0.025, by); ctx.lineTo(bx - L * 0.06, by + len); ctx.lineTo(bx + L * 0.025, by); ctx.closePath();
      ctx.fillStyle = rgb(shade(m, 0.45)); ctx.fill(); ctx.lineWidth = 0.7; ctx.strokeStyle = 'rgba(0,0,0,0.75)'; ctx.stroke();
    }
    // brow horn-let above the eye
    ctx.beginPath(); ctx.moveTo(...P(0.4, -0.27)); ctx.quadraticCurveTo(...P(0.36, -0.42), ...P(0.24, -0.5)); ctx.quadraticCurveTo(...P(0.42, -0.4), ...P(0.52, -0.26)); ctx.closePath();
    ctx.fillStyle = rgb(shade(m, 0.6)); ctx.fill(); ctx.lineWidth = 0.8; ctx.strokeStyle = 'rgba(0,0,0,0.7)'; ctx.stroke();
    ctx.restore();
  }

  // ── v8: polished metal shading, a new plated head, curling horns, gripping hands ──
  /** Fine, tight grain (the big-blotch grit made small parts look like paper mache). */
  function fineGrain(ctx, x0, y0, x1, y1, m, seed) {
    ctx.save();
    const sc = 0.28, pat = ctx.createPattern(texture(seed, 'metal'), 'repeat');
    ctx.globalCompositeOperation = 'overlay'; ctx.globalAlpha = m.grit * 0.55;
    ctx.scale(sc, sc); ctx.fillStyle = pat; ctx.fillRect(x0 / sc - 4, y0 / sc - 4, (x1 - x0) / sc + 8, (y1 - y0) / sc + 8);
    ctx.restore();
  }
  /** Polished, forged metal: a hard reflection band, crisp outline, lit top edge. */
  function metal(ctx, pathFn, m, box, seed, bright) {
    const [x0, y0, x1, y1] = box, b = bright || 0;
    castShadow(ctx, pathFn, Math.max(x1 - x0, y1 - y0) * 0.35);
    pathFn();
    const g = ctx.createLinearGradient(x0, y0, x0 + (x1 - x0) * 0.25, y1);
    g.addColorStop(0, rgb(shade(m, 0.97 + b))); g.addColorStop(0.2, rgb(shade(m, 0.74 + b)));
    g.addColorStop(0.42, rgb(shade(m, 0.4 + b))); g.addColorStop(0.56, rgb(shade(m, 0.2 + b)));
    g.addColorStop(0.74, rgb(shade(m, 0.36 + b))); g.addColorStop(1, rgb(shade(m, 0.07 + b)));
    ctx.fillStyle = g; ctx.fill();
    ctx.save(); pathFn(); ctx.clip(); fineGrain(ctx, x0, y0, x1, y1, m, seed); ctx.restore();
    ctx.save(); ctx.translate(-0.7, -0.9); pathFn(); ctx.lineWidth = 1.1; ctx.strokeStyle = rgb(m.spec, 0.55); ctx.stroke(); ctx.restore();
    pathFn(); ctx.lineWidth = 1.2; ctx.strokeStyle = 'rgba(0,0,0,0.88)'; ctx.stroke();
  }
  /** Horn grown by steering: style 0 swept back, 1 ram curl downward, 2 up-curl, 3 down-swept. */
  function horn3(ctx, bx, by, len, wd, style, m, seed, bright) {
    const H = [
      t => Math.PI + 0.45 - 0.55 * t,
      t => Math.PI + 0.6 - 3.5 * Math.pow(t, 1.25),
      t => Math.PI + 0.75 + 0.6 * t,
      t => Math.PI + 0.2 - 1.3 * t
    ][style];
    const n = 26, ds = len / n, pts = [];
    let x = bx, y = by;
    for (let i = 0; i <= n; i++) {
      const t = i / n;
      pts.push({ x, y, w: wd * (1 - t * 0.9) });
      const h = H(t); x += Math.cos(h) * ds; y += Math.sin(h) * ds;
    }
    const tb = tube(ctx, pts, m, seed, { bright: (bright || 0) + 0.1, fine: true });
    ctx.save(); tb.path(); ctx.clip();
    for (let i = 2; i < n - 2; i += 2) {                    // growth ridges
      const [lx, ly] = tb.L[i], [rx, ry] = tb.R[i];
      ctx.beginPath(); ctx.moveTo(lx, ly); ctx.quadraticCurveTo((lx + rx) / 2 + (pts[i + 1].x - pts[i].x), (ly + ry) / 2 + (pts[i + 1].y - pts[i].y), rx, ry);
      ctx.lineWidth = Math.max(0.5, wd * 0.07); ctx.strokeStyle = 'rgba(0,0,0,0.42)'; ctx.stroke();
    }
    ctx.restore();
  }
  /** The head, facing right; origin where the neck joins. L = head length. */
  function head3(ctx, U, m, rnd, h) {
    const L = h.L;
    ctx.save(); ctx.translate(h.x, h.y);
    // shape genes: every head is a little different
    const sl = 0.86 + rnd() * 0.34, sd = 0.78 + rnd() * 0.5, bh = 0.75 + rnd() * 0.6, jd = 0.8 + rnd() * 0.5, hh = 0.85 + rnd() * 0.35;
    const noseStyle = Math.floor(rnd() * 4), crest = Math.floor(rnd() * 3), nFrill = 2 + Math.floor(rnd() * 4), nChin = 1 + Math.floor(rnd() * 4), eyeK = 0.8 + rnd() * 0.4;
    const sm = (e0, e1, x) => { const t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t); };
    const warp = (x, y) => {
      const X = x <= 0.45 ? x : 0.45 + (x - 0.45) * sl;
      let Y = y;
      if (y > 0.1) Y = 0.1 + (y - 0.1) * jd;                                         // jaw depth
      else { const t = sm(0.5, 0.95, x); Y = 0.08 + (y - 0.08) * (1 + (sd - 1) * t) * (1 + (hh - 1) * (1 - t)); }   // snout depth / skull height
      if (y < -0.15) Y -= (bh - 1) * 0.12 * Math.max(0, Math.sin(Math.max(0, Math.min(1, (x - 0.1) / 0.6)) * Math.PI));  // brow
      return [X, Y];
    };
    const P = (x, y) => { const [X, Y] = warp(x, y); return [X * L, Y * L]; };
    const poly = (arr, close) => () => { ctx.beginPath(); ctx.moveTo(...P(...arr[0])); for (let i = 1; i < arr.length; i++) { const a = arr[i]; if (a.length === 4) ctx.quadraticCurveTo(...P(a[0], a[1]), ...P(a[2], a[3])); else ctx.lineTo(...P(...a)); } if (close !== false) ctx.closePath(); };
    // closed smooth outline through points; [x, y, 1] keeps a sharp corner there
    const smooth = (arr) => () => {
      const n = arr.length, Q = arr.map(a => P(a[0], a[1]));
      ctx.beginPath(); ctx.moveTo(...Q[0]);
      for (let i = 0; i < n; i++) {
        const p0 = Q[(i - 1 + n) % n], p1 = Q[i], p2 = Q[(i + 1) % n], p3 = Q[(i + 2) % n];
        const c1 = arr[i][2] ? p1 : [p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6];
        const c2 = arr[(i + 1) % n][2] ? p2 : [p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6];
        ctx.bezierCurveTo(c1[0], c1[1], c2[0], c2[1], p2[0], p2[1]);
      }
      ctx.closePath();
    };
    const box = (x0, y0, x1, y1) => { const a = P(x0, y0), b = P(x1, y1); return [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])]; };
    const hs = h.hornStyle;
    // far horn (behind, darker)
    if (hs < 4) horn3(ctx, L * 0.24, -L * 0.25, L * 0.85, L * 0.12, hs, m, 301, -0.22);
    // frill blades sweeping back from the cheek
    for (let k = 0; k < nFrill; k++) {
      const by = -0.02 + k * 0.28 / nFrill, len = (0.36 - k * 0.2 / nFrill) * (0.8 + hh * 0.3), a = 0.1 + k * 0.9 / nFrill;
      metal(ctx, poly([[0.12, by - 0.035], [-len * Math.cos(a) * 0.6, by + len * Math.sin(a) * 0.4 - 0.05, -len * Math.cos(a), by + len * Math.sin(a)], [0.02, by + 0.03, 0.12, by + 0.04]]), m, box(-0.35, by - 0.05, 0.12, by + 0.2), 311 + k, -0.15);
    }
    // lower jaw (opens on its hinge)
    const open = h.open ? 0.32 : 0.04;
    ctx.save(); ctx.translate(...P(0.16, 0.12)); ctx.rotate(open); ctx.translate(...P(-0.16, -0.12));
    if (h.open) {   // mouth interior, lower teeth
      ctx.save(); ctx.rotate(-open * 0.5);
      const ig = ctx.createLinearGradient(...P(0.3, 0), ...P(0.9, 0.2)); ig.addColorStop(0, '#120403'); ig.addColorStop(1, '#561a10');
      poly([[0.2, 0.1], [1.0, 0.0], [0.92, 0.2], [0.25, 0.2]])(); ctx.fillStyle = ig; ctx.fill(); ctx.restore();
      ctx.fillStyle = '#e9e1cc';
      for (let i = 0; i < 6; i++) { const x = 0.42 + i * 0.075; ctx.beginPath(); ctx.moveTo(...P(x, 0.115)); ctx.lineTo(...P(x + 0.02, 0.04 - (i === 4 ? 0.04 : 0))); ctx.lineTo(...P(x + 0.04, 0.115)); ctx.fill(); }
    }
    const jaw = smooth([[0.1, 0.1], [0.55, 0.11], [1.0, 0.1, 1], [0.92, 0.16], [0.7, 0.21], [0.42, 0.26], [0.18, 0.28], [0.06, 0.2]]);
    metal(ctx, jaw, m, box(0.05, 0.1, 0.93, 0.3), 321, 0.04);
    ctx.beginPath(); ctx.moveTo(...P(0.15, 0.2)); ctx.quadraticCurveTo(...P(0.45, 0.18), ...P(0.85, 0.13)); ctx.lineWidth = Math.max(0.6, L * 0.01); ctx.strokeStyle = 'rgba(0,0,0,0.5)'; ctx.stroke();
    for (let k = 0; k < nChin; k++) {                      // chin blades
      const x = 0.22 + k * 0.13, y = 0.27 - k * 0.025;
      metal(ctx, poly([[x - 0.05, y - 0.02], [x - 0.13, y + 0.13 - k * 0.03], [x + 0.03, y - 0.01]]), m, box(x - 0.13, y - 0.02, x + 0.03, y + 0.13), 331 + k, -0.1);
    }
    ctx.restore();
    // skull
    const noses = [
      [[0.95, -0.13], [1.1, -0.05], [1.07, 0.02], [1.0, 0.08, 1]],              // tapered
      [[0.98, -0.135], [1.06, -0.1], [1.08, 0.0], [1.0, 0.08, 1]],              // blunt
      [[0.96, -0.14], [1.09, -0.09], [1.12, 0.03], [1.05, 0.04], [1.0, 0.08, 1]], // hooked down
      [[0.92, -0.15], [1.07, -0.19], [1.07, -0.06], [1.0, 0.08, 1]]             // upturned
    ];
    const skull = smooth([[0.0, 0.15], [-0.05, -0.06], [0.07, -0.21], [0.22, -0.27], [0.42, -0.27], [0.56, -0.2], [0.8, -0.155]].concat(noses[noseStyle], [[0.45, 0.11], [0.22, 0.16]]));
    // crest along the top of the skull
    if (crest === 1) for (let k = 0; k < 4; k++) { const x = 0.06 + k * 0.09; metal(ctx, poly([[x, -0.2 - k * 0.02], [x - 0.07, -0.36 - k * 0.01], [x + 0.05, -0.23 - k * 0.02]]), m, box(x - 0.07, -0.36, x + 0.05, -0.2), 391 + k, 0); }
    if (crest === 2) metal(ctx, poly([[-0.02, -0.12], [-0.12, -0.3], [0.1, -0.24], [0.06, -0.38], [0.24, -0.27], [0.24, -0.4], [0.38, -0.27]]), m, box(-0.12, -0.4, 0.38, -0.12), 395, -0.05);
    metal(ctx, skull, m, box(-0.07, -0.27, 1.06, 0.17), 341, 0.12);
    // upper teeth + fang
    ctx.fillStyle = '#ece4cf';
    for (let i = 0; i < 7; i++) { const x = 0.46 + i * 0.075, big = i === 5; ctx.beginPath(); ctx.moveTo(...P(x, 0.1)); ctx.lineTo(...P(x + 0.018, 0.1 + (big ? 0.12 : h.open ? 0.07 : 0.05))); ctx.lineTo(...P(x + (big ? 0.05 : 0.038), 0.098)); ctx.fill(); }
    ctx.lineWidth = 0.5; ctx.strokeStyle = 'rgba(0,0,0,0.5)';
    // cheek plate with ridge
    const cheek = smooth([[0.05, -0.05], [0.3, -0.1], [0.44, 0.0], [0.4, 0.1], [0.16, 0.16], [0.03, 0.1]]);
    metal(ctx, cheek, m, box(0.03, -0.1, 0.44, 0.16), 351, 0.12);
    ctx.beginPath(); ctx.moveTo(...P(0.08, 0.05)); ctx.quadraticCurveTo(...P(0.25, -0.02), ...P(0.4, 0.02)); ctx.lineWidth = Math.max(0.6, L * 0.012); ctx.strokeStyle = 'rgba(0,0,0,0.55)'; ctx.stroke();
    // nasal plates (chevrons down the snout)
    for (let i = 0; i < 4; i++) {
      const x = 0.62 + i * 0.09, y = -0.17 + i * 0.012, wdt = 0.07 - i * 0.008;
      metal(ctx, smooth([[x - wdt, y + 0.03], [x - wdt * 0.3, y - 0.03], [x + wdt * 0.6, y - 0.01], [x + wdt, y + 0.03, 1], [x, y + 0.015]]), m, box(x - wdt, y - 0.035, x + wdt, y + 0.035), 361 + i, 0.08);
    }
    // nostril
    const ny = [0, 0, 0.01, -0.04][noseStyle]; ctx.beginPath(); ctx.moveTo(...P(0.92, -0.085 + ny)); ctx.quadraticCurveTo(...P(0.97, -0.1 + ny), ...P(1.02, -0.065 + ny)); ctx.quadraticCurveTo(...P(0.97, -0.06 + ny), ...P(0.92, -0.085 + ny)); ctx.fillStyle = '#050302'; ctx.fill();
    if (rnd() < 0.45) metal(ctx, poly([[0.9, -0.14], [1.0, -0.27], [0.99, -0.12]]), m, box(0.9, -0.27, 1.0, -0.12), 381, 0.1);   // nose horn
    // eye under a heavy brow
    ctx.save(); { const [ex, ey] = P(0.47, -0.14); ctx.translate(ex, ey); ctx.scale(eyeK, eyeK); ctx.translate(-ex, -ey); }
    ctx.beginPath(); ctx.ellipse(...P(0.47, -0.135), L * 0.1, L * 0.055, -0.12, 0, TAU); ctx.fillStyle = 'rgba(0,0,0,0.85)'; ctx.fill();
    ctx.save(); ctx.shadowColor = 'rgba(255,120,30,1)'; ctx.shadowBlur = L * 0.14;
    ctx.beginPath(); ctx.moveTo(...P(0.39, -0.13)); ctx.quadraticCurveTo(...P(0.47, -0.185), ...P(0.56, -0.15)); ctx.quadraticCurveTo(...P(0.48, -0.1), ...P(0.39, -0.13));
    const eg = ctx.createRadialGradient(...P(0.47, -0.145), 0, ...P(0.47, -0.14), L * 0.08); eg.addColorStop(0, '#fff2b0'); eg.addColorStop(0.45, '#ffa030'); eg.addColorStop(1, '#a3300a');
    ctx.fillStyle = eg; ctx.fill(); ctx.restore();
    ctx.beginPath(); ctx.ellipse(...P(0.475, -0.142), L * 0.007, L * 0.028, 0.1, 0, TAU); ctx.fillStyle = '#100300'; ctx.fill(); ctx.restore();
    const brow = smooth([[0.14, -0.2], [0.3, -0.3], [0.5, -0.33], [0.63, -0.2, 1], [0.5, -0.18], [0.32, -0.175]]);
    metal(ctx, brow, m, box(0.14, -0.34, 0.63, -0.18), 371, 0.18);
    metal(ctx, poly([[0.2, -0.24], [-0.14, -0.36], [0.24, -0.29]]), m, box(-0.14, -0.36, 0.24, -0.24), 373, 0.04);   // brow spine
    // small scales between cheek and snout
    for (let i = 0; i < 10; i++) {
      const x = 0.45 + (i % 5) * 0.06, y = -0.06 + Math.floor(i / 5) * 0.06 + (i % 2) * 0.02;
      ctx.beginPath(); ctx.arc(...P(x, y), L * 0.028, 0.15 * Math.PI, 0.85 * Math.PI); ctx.lineWidth = Math.max(0.5, L * 0.008); ctx.strokeStyle = 'rgba(0,0,0,0.5)'; ctx.stroke();
      ctx.beginPath(); ctx.arc(...P(x, y - 0.006), L * 0.028, 0.25 * Math.PI, 0.6 * Math.PI); ctx.strokeStyle = rgb(m.spec, 0.25); ctx.stroke();
    }
    // near horn(s)
    if (hs < 4) { horn3(ctx, L * 0.2, -L * 0.22, L * 0.95, L * 0.15, hs, m, 303, 0); horn3(ctx, L * 0.06, -L * 0.12, L * 0.5, L * 0.09, hs === 2 ? 0 : hs, m, 305, -0.06); }
    else for (let k = 0; k < 5; k++) horn3(ctx, L * (0.3 - k * 0.08), -L * (0.27 - k * 0.035), L * (0.42 - k * 0.04), L * 0.08, 0, m, 307 + k, 0);
    ctx.restore();
  }

  /**
   * Dragon coiled around the border (v7). Everything is laid out in polar terms around the
   * frame so the body, the swept-back wings and the legs follow the curve. The head rests on
   * the top of the coil, upright, looking left or right along it; never upside down.
   */
  /** Closed smooth outline through [x, y] points (Catmull-Rom). */
  function smoothClosedAt(ctx, arr) {
    return () => {
      const n = arr.length; ctx.beginPath(); ctx.moveTo(arr[0][0], arr[0][1]);
      for (let i = 0; i < n; i++) {
        const p0 = arr[(i - 1 + n) % n], p1 = arr[i], p2 = arr[(i + 1) % n], p3 = arr[(i + 2) % n];
        ctx.bezierCurveTo(p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6, p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6, p2[0], p2[1]);
      }
      ctx.closePath();
    };
  }
  function dragon(ctx, shape, cx, cy, r, w, m, rnd) {
    const s = rnd() < 0.5 ? 1 : -1;                     // direction of travel (head looks this way)
    const side = rnd() < 0.5 ? 1 : -1;                  // head on the right or left of the top
    const U = r * (0.22 + rnd() * 0.04);
    const T = U * (0.85 + rnd() * 0.3);                  // chest thickness
    const aHead = -Math.PI / 2 + side * (0.12 + rnd() * 0.38);
    const sweep = Math.PI * (1.15 + rnd() * 0.55);
    const aTail = aHead - s * sweep;
    // ── body plan genes: long thin tail, haunches, deep chest, slimmer neck ──
    const uT = 0.4 + rnd() * 0.16;                       // where the tail meets the hips
    const hipU = uT + 0.07, shU = hipU + 0.2 + rnd() * 0.08, neckU = shU + 0.06;
    const tipW = 0.05 + rnd() * 0.05, tailBase = 0.3 + rnd() * 0.16, tailPow = 1.1 + rnd() * 0.9;
    const hipW = 0.85 + rnd() * 0.2, chestW = 0.95 + rnd() * 0.2, belly = 0.05 + rnd() * 0.18, neckW = 0.45 + rnd() * 0.17;
    const lift = T * (0.5 + rnd() * 0.95);              // touching the band ... clearly detached               // how far the body rides off the band
    const R0 = r + w + lift;
    const waves = 1 + Math.floor(rnd() * 2), wav = T * (0.05 + rnd() * 0.15), wph = rnd() * TAU;
    const tailMode = Math.floor(rnd() * 4);              // 0 follows the frame, 1 flicks out, 2 S-wave, 3 spiral curl
    const tailAmp = T * (0.4 + rnd() * 1.0), tailFreq = 2 + rnd() * 3;
    const scaleSize = 0.8 + rnd() * 0.5, scaleRows = 5 + Math.floor(rnd() * 4);
    const spStyle = Math.floor(rnd() * 5), spN = 10 + Math.floor(rnd() * 12), spK = 0.35 + rnd() * 0.5;
    const bellyStyle = Math.floor(rnd() * 3), tailStyle = Math.floor(rnd() * 6);
    const rrk = shape === 'rounded' ? CORNER.rounded * 2 : shape === 'squircle' ? CORNER.squircle * 2 : 0;
    // Distance from the centre along angle a to the path that keeps "rad" (circle units) away from
    // the frame: the frame shape itself for things ON the band (feet, claws)...
    const shapeDist = (a, rad) => {
      if (shape === 'diamond') {
        // A rounded diamond (corner radius c), so even close to the band the body takes a wide,
        // gentle curve around each corner rather than a tight bend.
        const V = r + w, c = V * 0.32, hs = V / Math.SQRT2 - c, t = rad - V, ux = Math.cos(a), uy = Math.sin(a);
        const sd = (s) => {
          const x = ux * s, y = uy * s, xr = Math.abs((x + y) / Math.SQRT2) - hs, yr = Math.abs((y - x) / Math.SQRT2) - hs;
          return Math.hypot(Math.max(xr, 0), Math.max(yr, 0)) + Math.min(Math.max(xr, yr), 0) - c;
        };
        let lo = 0, hi = Math.max(V, rad) * 2 + Math.abs(t) * 2 + 1;
        for (let i = 0; i < 32; i++) { const mid = (lo + hi) / 2; if (sd(mid) < t) lo = mid; else hi = mid; }
        return lo;
      }
      // ray from the centre to a rounded square of half-size rad (same corner proportion as the frame)
      const H = rad, C = Math.min(H, (r + w) * rrk + (rad - r - w) * 0.6), ux = Math.abs(Math.cos(a)), uy = Math.abs(Math.sin(a));
      let t = Math.min(ux > 1e-9 ? H / ux : 1e9, uy > 1e-9 ? H / uy : 1e9);
      if (ux * t > H - C && uy * t > H - C) { const o = H - C, b = (ux + uy) * o, q = b * b - 2 * o * o + C * C; t = b + Math.sqrt(Math.max(0, q)); }
      return t;
    };
    // ...and, further out (body, wings), bowed toward a circle through the corners: no straight,
    // flat runs along the dragon's back on square or diamond frames, only curves.
    const pol = (a, rad) => {
      if (shape === 'circle') return { x: cx + Math.cos(a) * rad, y: cy + Math.sin(a) * rad };
      let d = shapeDist(a, rad);
      const t = rad - (r + w);
      if (t > 0) {
        const corner = Math.max(shapeDist(0, rad), shapeDist(Math.PI / 4, rad));
        d += (corner - d) * 0.55 * Math.min(1, t / (w * 1.2 + 1e-6));
      }
      return { x: cx + Math.cos(a) * d, y: cy + Math.sin(a) * d };
    };
    // inward normal of the frame outline at a point (toward the picture), angle in radians
    const inN = (x, y) => {
      if (shape === 'circle') return Math.atan2(cy - y, cx - x);
      const a = Math.atan2(y - cy, x - cx), rad = Math.hypot(x - cx, y - cy) * 0.9, p1 = pol(a - 0.01, rad), p2 = pol(a + 0.01, rad);
      const tx = p2.x - p1.x, ty = p2.y - p1.y; let n = Math.atan2(-tx, ty);   // rotate tangent
      if (Math.cos(n) * (cx - x) + Math.sin(n) * (cy - y) < 0) n += Math.PI;
      return n;
    };
    const arcPts = (a0, r0, a1, r1, n, bulge, w0, w1) => {
      const o = [];
      for (let i = 0; i <= n; i++) { const t = i / n; const p = pol(a0 + (a1 - a0) * t, r0 + (r1 - r0) * t + Math.sin(t * Math.PI) * bulge); p.w = w0 + (w1 - w0) * t; o.push(p); }
      return o;
    };
    const sstep = (e0, e1, x) => { const t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t); };
    const widthAt = (u) => {
      if (u < uT) return T * (tipW + (tailBase - tipW) * Math.pow(u / uT, tailPow));
      if (u < hipU) return T * (tailBase + (hipW - tailBase) * sstep(uT, hipU, u));
      if (u < shU) { const t = (u - hipU) / (shU - hipU); return T * (hipW + (chestW - hipW) * t + belly * Math.sin(t * Math.PI)); }
      if (u < neckU) return T * (chestW + (neckW - chestW) * sstep(shU, neckU, u));
      return T * neckW * (1 - 0.1 * (u - neckU) / (1 - neckU));
    };
    const radAt = (u) => {
      let rad = R0 + Math.sin(u * Math.PI * waves + wph) * wav * (1 - sstep(0.75, 0.9, u)) + T * 0.35 * sstep(0.86, 1, u);
      const tailT = 1 - sstep(0, uT * 0.9, u);           // 1 at the tip, 0 by the hips
      if (tailMode === 1) rad += tailAmp * Math.pow(tailT, 2);
      if (tailMode === 2) rad += Math.sin(u / uT * Math.PI * tailFreq) * T * 0.35 * tailT;
      if (tailMode === 3) rad += tailAmp * 0.5 * Math.pow(tailT, 2);
      return rad;
    };
    const N = 96, pts = [];
    for (let i = 0; i <= N; i++) {
      const u = i / N, a = aTail + (aHead - aTail) * u, p = pol(a, radAt(u));
      p.w = widthAt(u); p.a = a; p.u = u; pts.push(p);
    }
    // spiral curl on the tail tip, curling away from the frame
    if (tailMode === 3) {
      const p0 = pts[0], p1 = pts[2], out = (inN(p0.x, p0.y) + Math.PI);
      let h = Math.atan2(p0.y - p1.y, p0.x - p1.x), x = p0.x, y = p0.y;
      const turn = Math.sin(out - h) > 0 ? 1 : -1, K = 26, len = T * (1.6 + rnd() * 1.4), extra = [];
      for (let k = 1; k <= K; k++) { const t = k / K; h += turn * (0.12 + 0.35 * t); const ds = len / K * (1 - t * 0.55); x += Math.cos(h) * ds; y += Math.sin(h) * ds; extra.push({ x, y, w: p0.w * (1 - t * 0.6), a: p0.a, u: 0 }); }
      pts.unshift(...extra.reverse());
    }
    const NB = pts.length - 1;
    const aAt = (u) => aTail + (aHead - aTail) * u;
    const uAt = (a) => Math.max(0, Math.min(1, (a - aTail) / (aHead - aTail)));
    const bodyR = (a) => radAt(uAt(a));
    const bodyW = (a) => widthAt(uAt(a));
    const end = pts[NB];
    const smoothClosed = (arr) => smoothClosedAt(ctx, arr);
    const behind = () => { ctx.beginPath(); ctx.rect(cx - r * 10, cy - r * 10, r * 20, r * 20); shapePath(ctx, shape, cx, cy, r + w, true); ctx.clip('evenodd'); };

    // ── wings: arched back along the frame, hugging the curve ──
    // wing shape: varies a lot per dragon (finger count, height, span, sweep, torn edges, one or two wings)
    const aSh = aAt(hipU + (shU - hipU) * (0.72 + rnd() * 0.22)), span = 0.8 + rnd() * 0.6, nF = 3 + Math.floor(rnd() * 3);
    const wH = 1.0 + rnd() * 0.6, wrT = 0.26 + rnd() * 0.22, tatter = rnd() < 0.4, twoWings = rnd() < 0.45, wBulge = 0.15 + rnd() * 0.25;
    const fan = 0.3 + rnd() * 0.3, tipStart = 0.78 + rnd() * 0.25;
    const tipT = [], tipR = [];
    for (let j = 0; j < nF; j++) { const t = j / (nF - 1); tipT.push(tipStart + fan * Math.pow(t, 0.8) + (j && j < nF - 1 ? (rnd() - 0.5) * 0.03 : 0)); tipR.push(Math.max(0.12, wH * 0.92 * (1 - t * 0.88) + (j && j < nF - 1 ? (rnd() - 0.5) * 0.06 : 0))); }
    for (let j = 1; j < nF; j++) { tipT[j] = Math.max(tipT[j], tipT[j - 1] + 0.06); tipR[j] = Math.min(tipR[j], tipR[j - 1] - 0.12); }
    const tears = []; for (let j = 0; j <= nF; j++) tears.push(tatter && rnd() < 0.45 ? { amp: 0.08 + rnd() * 0.14, c: 0.3 + rnd() * 0.4 } : null);
    const wingArc = (far) => {
      const d = far ? -0.16 : 0, k = far ? 1.12 : 1, A = (t) => aSh + (far ? s * 0.13 : 0) - s * t * span;
      const backR = (a) => bodyR(a) + bodyW(a) * 0.36, base = backR(aSh) + T * 0.1;
      const Uw = U * Math.min(1, Math.max(0.45, (2.0 * r - base) / (U * wH * k * 1.15)));   // keep the wing on the canvas
      const aE = A(wrT * 0.3), rE = base + Uw * wH * 0.6 * k, aW = A(wrT), rW = base + Uw * wH * k;
      const arm = arcPts(A(0), backR(A(0)) - T * 0.15, aE, rE, 6, Uw * 0.12, U * 0.42, U * 0.3).concat(arcPts(aE, rE, aW, rW, 6, Uw * 0.26, U * 0.3, U * 0.2).slice(1));   // bowed, never a straight edge
      const tipCap = Math.max(0.25, (uAt(aSh) - (hipU - 0.02)) * sweep / span) + 0.28;   // overhang the hips a little at most
      const fingers = tipT.map((t, j) => arcPts(aW, rW, A(Math.min(t, tipCap - (nF - 1 - j) * 0.05)), base + Uw * Math.max(0.1, tipR[j]) * k, 10, Uw * wBulge * (1 - j / nF) * k, U * 0.13, U * 0.03));
      // The membrane joins the back no further than the hips: it never runs along the thin tail
      // (that looked like a loose sheath beside the tail).
      const uSh = uAt(aSh), tLim = Math.max(0.25, (uSh - (hipU - 0.02)) * sweep / span);
      const attach = { a: A(Math.min(tipT[nF - 1] + 0.12, tLim)) }; attach.r = backR(attach.a);
      const W = pol(aW, rW);
      // scalloped (and maybe torn) trailing edge as a point list
      const edge = [];
      const scallop = (a, b, pull, from) => {
        const mx = (a.x + b.x) / 2 + (W.x - (a.x + b.x) / 2) * pull, my = (a.y + b.y) / 2 + (W.y - (a.y + b.y) / 2) * pull;
        for (let i = 1; i <= 8; i++) {
          const t = i / 8, x = (1 - t) * (1 - t) * a.x + 2 * t * (1 - t) * mx + t * t * b.x, y = (1 - t) * (1 - t) * a.y + 2 * t * (1 - t) * my + t * t * b.y;
          const tr = tears[from % tears.length], nt = tr && i < 8 ? tr.amp * Math.max(0, 1 - Math.abs(t - tr.c) / 0.16) : 0;
          edge.push({ x: x + (W.x - x) * nt, y: y + (W.y - y) * nt });
        }
      };
      for (let j = 1; j < nF; j++) scallop(fingers[j - 1][10], fingers[j][10], 0.38, j);
      const at = pol(attach.a, attach.r); scallop(fingers[nF - 1][10], at, 0.3, 0);
      const memb = () => {
        ctx.beginPath(); ctx.moveTo(arm[0].x, arm[0].y);
        for (const p of arm) ctx.lineTo(p.x, p.y);
        for (const p of fingers[0]) ctx.lineTo(p.x, p.y);
        for (const p of edge) ctx.lineTo(p.x, p.y);
        for (let i = 0; i <= 14; i++) { const a2 = attach.a + (A(0) - attach.a) * i / 14, p = pol(a2, backR(a2)); ctx.lineTo(p.x, p.y); }
        ctx.closePath();
      };
      ctx.save(); behind();
      castShadow(ctx, memb, U * 1.2);
      memb(); ctx.fillStyle = rgb(shade(m, 0.13 + d)); ctx.fill();
      ctx.save(); memb(); ctx.clip();
      for (let j = 0; j < nF; j++) {
        const a = fingers[j][10], b = j + 1 < nF ? fingers[j + 1][10] : at;
        const px = (W.x + a.x + b.x) / 3, py = (W.y + a.y + b.y) / 3;
        const g = ctx.createRadialGradient(px - U * 0.15, py - U * 0.15, 0, px, py, U * 0.9);
        g.addColorStop(0, rgb(shade(m, 0.46 + d), 0.6)); g.addColorStop(1, rgb(shade(m, 0.1 + d), 0));
        ctx.fillStyle = g; ctx.fillRect(px - U * 2, py - U * 2, U * 4, U * 4);
        for (let f = 1; f <= 3; f++) {                     // folds in the skin
          const fx = a.x + (b.x - a.x) * f / 4, fy = a.y + (b.y - a.y) * f / 4;
          ctx.beginPath(); ctx.moveTo(W.x, W.y);
          ctx.quadraticCurveTo((W.x + fx) / 2 + (cx - (W.x + fx) / 2) * -0.04, (W.y + fy) / 2 + (cy - (W.y + fy) / 2) * -0.04, fx + (W.x - fx) * 0.18, fy + (W.y - fy) * 0.18);
          ctx.lineWidth = Math.max(0.4, U * 0.02); ctx.strokeStyle = f === 2 ? 'rgba(0,0,0,0.5)' : rgb(m.spec, 0.07); ctx.stroke();
        }
      }
      fineGrain(ctx, W.x - U * 3, W.y - U * 3, W.x + U * 3, W.y + U * 3, m, 181);
      ctx.restore();
      // pale rim on the trailing edge
      ctx.beginPath(); ctx.moveTo(fingers[0][10].x, fingers[0][10].y);
      for (const p of edge) ctx.lineTo(p.x, p.y);
      ctx.lineWidth = Math.max(0.6, U * 0.03); ctx.strokeStyle = rgb(m.spec, far ? 0.12 : 0.24); ctx.stroke();
      // bones
      for (const f of fingers) tube(ctx, f, m, 191, { bright: d, fine: true });
      tube(ctx, arm, m, 193, { bright: d - 0.1, fine: true }); scaleSkin(ctx, arm, m, rnd, { rows: 4, size: 1.25 });
      for (const j of [pol(aE, rE), W]) {
        const g = ctx.createRadialGradient(j.x - U * 0.05, j.y - U * 0.05, 0, j.x, j.y, U * 0.15);
        g.addColorStop(0, rgb(shade(m, 0.85 + d))); g.addColorStop(1, rgb(shade(m, 0.18 + d)));
        ctx.beginPath(); ctx.arc(j.x, j.y, U * 0.14, 0, TAU); ctx.fillStyle = g; ctx.fill();
      }
      const fwd = Math.atan2(pol(aW + s * 0.1, rW).y - W.y, pol(aW + s * 0.1, rW).x - W.x);
      talon(ctx, W.x, W.y, fwd - s * 0.6, U * 0.34, U * 0.065, m);
      for (const f of fingers) { const p = f[10], q = f[9]; talon(ctx, p.x, p.y, Math.atan2(p.y - q.y, p.x - q.x), U * 0.14, U * 0.025, m); }
      ctx.restore();
    };
    if (twoWings) wingArc(true);

    // ── hands and feet, bird-like: long jointed toes fanning from one point, a back toe, hooked talons ──
    const hand = (W, foot, d) => {
      const z = T * (1.15 + rnd() * 0.3), inA = inN(W.x, W.y), fw = inA - s * Math.PI / 2;     // fw: the way the dragon faces
      const ix = Math.cos(inA), iy = Math.sin(inA), fx = Math.cos(fw), fy = Math.sin(fw);
      const B = { x: W.x + ix * z * 0.1, y: W.y + iy * z * 0.1 };                              // the ball the toes grow from
      const fan = 0.3 + rnd() * 0.25, lean = 0.08 + rnd() * 0.2, hasBack = false; rnd();   // no back toe: it read as not gripping
      const gripR = r + w * (0.55 - rnd() * 0.6), bendK = 0.55 + rnd() * 0.3;
      const dB = Math.hypot(B.x - cx, B.y - cy);
      // one toe: three phalanges, each joint able to bend; curled toes bend and foreshorten as they hook over
      const curlHand = rnd() * 0.55;
      // how far a point sits past the band's inner edge (positive = over the picture), in band widths
      const pastEdge = (x, y) => { const a = Math.atan2(y - cy, x - cx), e = pol(a, r); return (Math.hypot(e.x - cx, e.y - cy) - Math.hypot(x - cx, y - cy)) / w; };
      const segL = [0.42, 0.33, 0.25];
      const chain = (ang, len, curl, bendSide) => {
        let x = B.x, y = B.y, a = ang; const pts3 = [{ x, y, a }];
        for (let k = 0; k < 3; k++) { const L = len * segL[k] * Math.cos(curl * k * 0.55); x += Math.cos(a) * L; y += Math.sin(a) * L; a += bendSide * curl * (0.35 + k * 0.15); pts3.push({ x, y, a, L }); }
        return pts3;
      };
      const toe = (ang, len, wd, seed, back) => {
        const bendSide = (back ? -1 : 1) * s;                                       // curl toward the back of the foot
        // Grip rule: a digit that would reach past the inner edge curls until its tip just hooks over it;
        // one that rests on the band curls a little, more the closer its tip gets to the edge.
        const curlFor = (ang2, len2) => {
          if (back) return 0.2 + curlHand * 0.4;
          const straight = chain(ang2, len2, 0, bendSide), over = pastEdge(straight[3].x, straight[3].y);
          if (over <= 0) return Math.max(0, 0.1 + 0.5 * (1 + over)) * (0.8 + curlHand * 0.4);   // over in [-1,0]: on the band
          for (let c = 0.3; c <= 1.25; c += 0.05) { const tip = chain(ang2, len2, c, bendSide)[3]; if (pastEdge(tip.x, tip.y) <= 0.08) return c; }
          return 1.25;
        };
        // Never float: the tip must come down on the band. Swing the digit toward the frame (and let it
        // reach a bit further) until it does; a back toe that can't reach is simply tucked away.
        let curl = curlFor(ang, len), tries = 0;
        const toward = Math.sin(inA - ang) > 0 ? 1 : -1;
        while (tries++ < 30) {
          const tip = chain(ang, len, curl, bendSide)[3];
          if (pastEdge(tip.x, tip.y) >= -0.9) break;
          ang += toward * 0.06; len = Math.min(len * 1.03, z * 1.35); curl = curlFor(ang, len);
        }
        { const tip = chain(ang, len, curl, bendSide)[3]; if (pastEdge(tip.x, tip.y) < -0.9) { if (back) return; } }
        if (!back) curl = Math.min(1.25, curl + (rnd() - 0.5) * 0.08);
        let x = B.x, y = B.y, a = ang, w0 = wd;
        const joints = [{ x, y, w: w0 }];
        for (let k = 0; k < 3; k++) {
          const L = len * segL[k] * Math.cos(curl * k * 0.55);                      // later segments foreshorten as they curl down
          const w1 = wd * (1 - (k + 1) * 0.15);
          const mid = { x: x + Math.cos(a) * L * 0.5, y: y + Math.sin(a) * L * 0.5 };
          const nx2 = x + Math.cos(a) * L, ny2 = y + Math.sin(a) * L;
          const seg = [{ x, y, w: w0 * 0.92 }, { ...mid, w: (w0 + w1) * 0.5 * 0.84 }, { x: nx2, y: ny2, w: w1 * 0.92 }];
          const tb = tube(ctx, seg, m, seed + k, { bright: d + 0.1 - k * 0.03, fine: true });
          ctx.save(); tb.path(); ctx.clip();                                         // scutes across the segment
          for (let q = 1; q <= 2; q++) { const t = q / 3, px = x + Math.cos(a) * L * t, py = y + Math.sin(a) * L * t, pa = a + Math.PI / 2, pw = w0 * (1 - t * 0.2);
            ctx.beginPath(); ctx.moveTo(px + Math.cos(pa) * pw * 0.6, py + Math.sin(pa) * pw * 0.6); ctx.quadraticCurveTo(px + Math.cos(a) * pw * 0.35, py + Math.sin(a) * pw * 0.35, px - Math.cos(pa) * pw * 0.6, py - Math.sin(pa) * pw * 0.6);
            ctx.lineWidth = Math.max(0.5, z * 0.012); ctx.strokeStyle = 'rgba(0,0,0,0.35)'; ctx.stroke(); }
          ctx.restore();
          x = nx2; y = ny2; w0 = w1; joints.push({ x, y, w: w1 });
          a += bendSide * curl * (0.35 + k * 0.15);                                   // bend at the next joint
        }
        // knuckles at every joint
        for (let k = 1; k < joints.length - 1; k++) {
          const j = joints[k], rr = j.w * 0.62;
          const g = ctx.createRadialGradient(j.x - rr * 0.35, j.y - rr * 0.35, 0, j.x, j.y, rr);
          g.addColorStop(0, rgb(shade(m, 0.95 + d))); g.addColorStop(0.55, rgb(shade(m, 0.5 + d))); g.addColorStop(1, rgb(shade(m, 0.15 + d)));
          ctx.beginPath(); ctx.ellipse(j.x, j.y, rr, rr * 0.85, a, 0, TAU); ctx.fillStyle = g; ctx.fill();
          ctx.lineWidth = 0.6; ctx.strokeStyle = 'rgba(0,0,0,0.65)'; ctx.stroke();
        }
        const e = joints[3];
        talon(ctx, e.x, e.y, a, z * (back ? 0.26 : 0.32) * (1 - Math.min(1, curl) * 0.3), wd * 0.55, m, bendSide * (0.5 + Math.min(1.1, curl) * 0.5));
      };
      if (hasBack) toe(inA + s * (1.7 + rnd() * 0.4), z * (0.2 + rnd() * 0.06), z * 0.1, 421, true);   // rarely, and mostly hidden behind the foot   // back toe braced behind
      const nF = 3, order = [0, 2, 1];                                                            // outer toes first, middle on top
      // long toes; when the foot is close to the band they lie along it (leaning forward) instead of getting short
      const lenMid = z * (0.8 + rnd() * 0.25), need = Math.max(z * 0.1, dB - gripR);
      const phi = Math.acos(Math.min(1, need / lenMid)) * 0.85 + lean;
      for (const k of order) {
        const off = phi + (k - 1) * fan, ang = inA - s * off;                                  // + is forward
        const len = lenMid * (k === 1 ? 1 : 0.84 + rnd() * 0.06);
        toe(ang, len, z * (k === 1 ? 0.15 : 0.135), 431 + k, false);
      }
      // the ball of the foot over the toe roots
      const bp = () => { ctx.beginPath(); ctx.ellipse(B.x, B.y, z * 0.16, z * 0.13, inA, 0, TAU); };
      metal(ctx, bp, m, [B.x - z * 0.16, B.y - z * 0.16, B.x + z * 0.16, B.y + z * 0.16], 441, d + 0.02);
    };
    const limb = (pts2, d, rows, seed) => { tube(ctx, pts2, m, seed, { bright: d + 0.1, fine: true }); scaleSkin(ctx, pts2, m, rnd, { rows, size: scaleSize * 1.1 }); };
    let bodyPath = null;
    // upper arm / thigh: drawn with an outline, then repainted without one inside the body so it grows out of it
    const rootLimb = (pts2, d, rows, seed) => {
      limb(pts2, d, rows, seed);
      if (!bodyPath) return;
      ctx.save(); bodyPath(); ctx.clip();
      tube(ctx, pts2, m, seed, { bright: d + 0.04, fine: true, soft: true }); scaleSkin(ctx, pts2, m, rnd, { rows, size: scaleSize * 1.1 });
      ctx.restore();
    };
    const tan = (p) => inN(p.x, p.y) - s * Math.PI / 2;      // direction the dragon travels at p
    const foreleg = (a0, far) => {
      const d = far ? -0.22 : 0, rb = bodyR(a0), bw = bodyW(a0), j = (rnd() - 0.5) * 0.04;
      const gap = Math.max(0, rb - bw * 0.5 - r - w), rWr = r + w + Math.max(0.06 * T, Math.min(0.32 * T, gap * 0.45)) + rnd() * 0.08 * T;
      const st = rnd() < 0.4 ? 0.12 + rnd() * 0.2 : 0;                 // stretched out: reaching forward
      const S = pol(a0 + s * 0.03, rb + bw * 0.2), E = pol(a0 - s * (0.15 + j - st * 0.6), Math.max(rWr + T * 0.2, rb - bw * 0.42 - T * (0.12 + rnd() * 0.08) + st * T * 0.5));
      const W = pol(a0 + s * (0.05 + rnd() * 0.05 + st), rWr);
      const fa = spline([{ ...E, w: T * 0.36 }, { x: (E.x + W.x) / 2, y: (E.y + W.y) / 2, w: T * 0.3 }, { ...W, w: T * 0.27 }], 6);
      limb(fa, d, 3, 173);
      hand(W, false, d);
      rootLimb(spline([{ ...S, w: T * 0.7 }, { x: (S.x + E.x) / 2, y: (S.y + E.y) / 2, w: T * 0.6 }, { ...E, w: T * 0.38 }], 6), d, 4, 171);
      talon(ctx, E.x, E.y, tan(E) + Math.PI + s * 0.5, T * 0.28, T * 0.06, m);   // elbow spur, pointing back
    };
    const hindleg = (a0, far) => {
      const d = far ? -0.22 : 0, rb = bodyR(a0), bw = bodyW(a0), j = (rnd() - 0.5) * 0.04;
      const gap = Math.max(0, rb - bw * 0.5 - r - w), rWr = r + w + Math.max(0.06 * T, Math.min(0.3 * T, gap * 0.4)) + rnd() * 0.08 * T;
      const st = rnd() < 0.4 ? 0.12 + rnd() * 0.22 : 0;                 // stretched out: kicked back
      const H = pol(a0 - s * 0.03, rb + bw * 0.15), K = pol(a0 + s * (0.17 + j - st * 0.5), Math.max(rWr + T * 0.35, rb - bw * 0.4 - T * 0.08));
      const A2 = pol(a0 - s * (0.06 + j + st * 0.7), Math.max(rWr + T * 0.2, Math.min(rb - bw * 0.3, r + w + T * (0.62 + rnd() * 0.1)))), W = pol(a0 + s * (0.03 + rnd() * 0.04 - st), rWr);
      limb(spline([{ ...A2, w: T * 0.26 }, { ...W, w: T * 0.24 }], 4), d, 3, 175);
      hand(W, true, d);
      limb(spline([{ ...K, w: T * 0.44 }, { x: (K.x + A2.x) / 2, y: (K.y + A2.y) / 2, w: T * 0.34 }, { ...A2, w: T * 0.27 }], 6), d, 3, 176);
      rootLimb(spline([{ ...H, w: T * 1.0 }, { x: (H.x + K.x) / 2, y: (H.y + K.y) / 2, w: T * 0.82 }, { ...K, w: T * 0.46 }], 6), d, 5, 177);
    };
    const aFore = aAt(shU - 0.01), aHind = aAt(hipU + 0.01);
    const farF = (rnd() < 0.7 ? -1 : 1) * s * (0.12 + rnd() * 0.22), farH = (rnd() < 0.7 ? -1 : 1) * s * (0.12 + rnd() * 0.25);
    const nearF = s * (rnd() - 0.5) * 0.12, nearH = s * (rnd() - 0.5) * 0.14;
    // far-side limbs grip the back of the border: hidden behind the band and the picture
    ctx.save(); behind(); foreleg(aFore + farF, true); hindleg(aHind + farH, true); ctx.restore();

    // ── the body ──
    const body = tube(ctx, pts, m, 151, { bright: -0.2, fine: true }); bodyPath = body.path;
    scaleSkin(ctx, pts, m, rnd, { rows: scaleRows, size: scaleSize });
    ctx.save(); body.path(); ctx.clip();
    if (bellyStyle !== 2) for (let i = 3; i < NB - 1; i += 2) {           // belly plates on the frame side
      const p = pts[i], q2 = pts[i + 2] || pts[i + 1], inA = inN(p.x, p.y), inB = inN(q2.x, q2.y);
      if (p.u > neckU + 0.06) continue;
      const k1 = bellyStyle === 1 ? 0.1 : 0.25;
      const a1 = { x: p.x + Math.cos(inA) * p.w * k1, y: p.y + Math.sin(inA) * p.w * k1 }, a2 = { x: p.x + Math.cos(inA) * p.w * 0.55, y: p.y + Math.sin(inA) * p.w * 0.55 };
      const b1 = { x: q2.x + Math.cos(inB) * q2.w * k1, y: q2.y + Math.sin(inB) * q2.w * k1 }, b2 = { x: q2.x + Math.cos(inB) * q2.w * 0.55, y: q2.y + Math.sin(inB) * q2.w * 0.55 };
      ctx.beginPath(); ctx.moveTo(a1.x, a1.y); ctx.lineTo(a2.x, a2.y); ctx.lineTo(b2.x, b2.y); ctx.quadraticCurveTo((b1.x + b2.x) / 2 + (b1.x - a1.x) * 0.3, (b1.y + b2.y) / 2 + (b1.y - a1.y) * 0.3, b1.x, b1.y); ctx.closePath();
      const g = ctx.createLinearGradient(a1.x, a1.y, a2.x, a2.y); g.addColorStop(0, rgb(shade(m, 0.6))); g.addColorStop(1, rgb(shade(m, 0.2))); ctx.fillStyle = g; ctx.fill();
      ctx.lineWidth = Math.max(0.5, T * 0.022); ctx.strokeStyle = 'rgba(0,0,0,0.6)'; ctx.stroke();
    }
    // muscle at the shoulders and haunches, curving down into the legs
    for (const [a, big] of [[aFore, 0.9], [aHind, 1.15]]) {
      const p = pol(a, bodyR(a)), pw = bodyW(a) * big, inward = inN(p.x, p.y);
      const mx = p.x + Math.cos(inward) * pw * 0.1, my = p.y + Math.sin(inward) * pw * 0.1;
      const g = ctx.createRadialGradient(mx - pw * 0.12, my - pw * 0.15, 0, mx, my, pw * 0.7);
      g.addColorStop(0, rgb(m.spec, 0.3)); g.addColorStop(0.6, rgb(m.light, 0.08)); g.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = g; ctx.fillRect(mx - pw, my - pw, pw * 2, pw * 2);
      const tg = inward + Math.PI / 2, rr = pw * 0.5;
      ctx.beginPath(); ctx.arc(mx + Math.cos(tg) * s * rr * 0.4, my + Math.sin(tg) * s * rr * 0.4, rr, inward + Math.PI * 0.55, inward + Math.PI * 1.45, false);
      ctx.lineWidth = Math.max(0.6, T * 0.035); ctx.strokeStyle = 'rgba(0,0,0,0.38)'; ctx.stroke();
    }
    ctx.restore();

    foreleg(aFore + nearF, false); hindleg(aHind + nearH, false);

    // ── tail tip ──
    { const e = pts[0], p = pts[3], ta = Math.atan2(e.y - p.y, e.x - p.x), sz = T * (0.4 + rnd() * 0.3), L2 = 1 + rnd() * 0.8;
      const at = (a, d2) => [e.x + Math.cos(ta + a) * sz * d2, e.y + Math.sin(ta + a) * sz * d2];
      const P2 = (arr) => () => { ctx.beginPath(); ctx.moveTo(...at(...arr[0])); for (let i = 1; i < arr.length; i++) { const q = arr[i]; if (q.length === 4) ctx.quadraticCurveTo(...at(q[0], q[1]), ...at(q[2], q[3])); else ctx.lineTo(...at(...q)); } ctx.closePath(); };
      const sp = 0.8 + rnd() * 0.6;
      const tips = [
        P2([[1.57, 0.12], [sp, 0.9, 0, 1.6 * L2], [-sp, 0.9, -1.57, 0.12]]),                                          // spade
        P2([[1.57, 0.1], [0.3, 1.1 * L2, 0, 2.3 * L2], [-0.12, 1.0, -1.57, 0.1]]),                                    // long blade
        P2([[1.57, 0.12], [0.55 * sp, 1.2], [0.2, 0.75], [0, 1.9 * L2], [-0.2, 0.75], [-0.55 * sp, 1.2], [-1.57, 0.12]]),  // barbed arrow
        P2([[1.57, 0.12], [0.9, 1.1 * L2], [0.45, 0.55], [0.15, 1.5 * L2], [-0.05, 0.6], [-0.35, 1.3 * L2], [-0.55, 0.45], [-1.57, 0.12]]),  // feathered fan
        P2([[1.57, 0.15], [1.2, 0.9 * L2], [0.75, 0.45], [0.35, 1.2 * L2], [0, 0.5], [-0.35, 1.2 * L2], [-0.75, 0.45], [-1.2, 0.9 * L2], [-1.57, 0.15]]),  // spiked mace
        P2([[1.57, 0.1], [0.5, 0.7, 0.1, 1.6 * L2], [0.05, 0.9], [-0.1, 1.6 * L2], [-0.5, 0.7, -1.57, 0.1]])          // split fork
      ];
      metal(ctx, tips[tailStyle], m, [e.x - sz * 2, e.y - sz * 2, e.x + sz * 2, e.y + sz * 2], 137, 0.05);
      // a ridge line down the middle of the tip
      ctx.beginPath(); ctx.moveTo(...at(0, 0.15)); ctx.lineTo(...at(0, 1.2 * L2)); ctx.lineWidth = Math.max(0.6, T * 0.025); ctx.strokeStyle = 'rgba(0,0,0,0.45)'; ctx.stroke(); }

    // ── ridge along the back (smaller toward the tail tip) ──
    const ridgeAt = (i) => { const p = pts[i], out = (inN(p.x, p.y) + Math.PI); return { p, out, bx: p.x + Math.cos(out) * p.w * 0.42, by: p.y + Math.sin(out) * p.w * 0.42 }; };
    const idx = (f) => Math.min(NB - 2, Math.max(1, Math.floor(f)));
    const i0 = NB - N, ridgeEnd = NB - Math.floor(N * (1 - neckU - 0.04));
    const ridgeI = (k, n) => idx(i0 + N * 0.04 + (ridgeEnd - i0 - N * 0.04) * k / n);
    if (spStyle === 3) {
      const tips = [], bases = [];
      for (let k = 0; k <= spN; k++) { const { p, out, bx, by } = ridgeAt(ridgeI(k, spN)), len = Math.max(T * 0.12, p.w * spK); bases.push([bx, by]); tips.push([bx + Math.cos(out - s * 0.5) * len, by + Math.sin(out - s * 0.5) * len]); }
      const fin = () => { ctx.beginPath(); ctx.moveTo(...bases[0]); for (let k = 0; k <= spN; k++) { if (k) { const mx = (tips[k - 1][0] + tips[k][0]) / 2, my = (tips[k - 1][1] + tips[k][1]) / 2; ctx.quadraticCurveTo(mx + (bases[k][0] - mx) * 0.4, my + (bases[k][1] - my) * 0.4, ...tips[k]); } else ctx.lineTo(...tips[0]); } for (let k = spN; k >= 0; k--) ctx.lineTo(...bases[k]); ctx.closePath(); };
      fin(); ctx.fillStyle = rgb(shade(m, 0.2)); ctx.fill(); ctx.lineWidth = 0.8; ctx.strokeStyle = 'rgba(0,0,0,0.7)'; ctx.stroke();
      for (let k = 0; k <= spN; k++) { ctx.beginPath(); ctx.moveTo(...bases[k]); ctx.lineTo(...tips[k]); ctx.lineWidth = Math.max(0.8, T * 0.04); ctx.strokeStyle = rgb(shade(m, 0.6)); ctx.stroke(); }
    } else if (spStyle === 4) {
      const n = Math.floor(spN * 1.6);
      for (let k = 0; k < n; k++) { const { p, bx, by } = ridgeAt(ridgeI(k, n)), rr = p.w * 0.12;
        const g = ctx.createRadialGradient(bx - rr * 0.4, by - rr * 0.4, 0, bx, by, rr); g.addColorStop(0, rgb(shade(m, 0.95))); g.addColorStop(1, rgb(shade(m, 0.2)));
        ctx.beginPath(); ctx.arc(bx, by, rr, 0, TAU); ctx.fillStyle = g; ctx.fill(); ctx.lineWidth = 0.6; ctx.strokeStyle = 'rgba(0,0,0,0.7)'; ctx.stroke(); }
    } else {
      const rows = spStyle === 2 ? [1, -1] : [1];
      for (const row of rows) for (let k = 0; k < spN; k++) {
        const { p, out, bx, by } = ridgeAt(ridgeI(k + (row < 0 ? 0.5 : 0), spN));
        const len = p.w * spK * (row < 0 ? 0.7 : 1) * (0.8 + rnd() * 0.4), tg = out + Math.PI / 2, tilt = -s * (spStyle === 1 ? 0.9 : 0.5), bw = p.w * 0.15;
        ctx.beginPath(); ctx.moveTo(bx + Math.cos(tg) * bw, by + Math.sin(tg) * bw);
        if (spStyle === 1) ctx.quadraticCurveTo(bx + Math.cos(out) * len * 0.8, by + Math.sin(out) * len * 0.8, bx + Math.cos(out + tilt) * len, by + Math.sin(out + tilt) * len);
        else ctx.lineTo(bx + Math.cos(out + tilt) * len, by + Math.sin(out + tilt) * len);
        ctx.lineTo(bx - Math.cos(tg) * bw, by - Math.sin(tg) * bw); ctx.closePath();
        ctx.fillStyle = rgb(shade(m, (row < 0 ? 0.15 : 0.3) + 0.35 * (0.5 + 0.5 * Math.cos(out - LIGHT)))); ctx.fill();
        ctx.lineWidth = 0.8; ctx.strokeStyle = rgb(m.dark, 0.8); ctx.stroke();
      }
    }

    // ── wings: rooted on the back at the middle of the body, drawn over it ──
    wingArc(false);

    // ── head ──
    const q = pts[NB - 3], dx = end.x - q.x, dy = end.y - q.y;
    let rot = Math.atan2(dy, s * dx) + 0.12;
    rot = Math.max(-0.45, Math.min(0.55, rot));
    ctx.save(); ctx.translate(end.x, end.y); ctx.scale(s, 1); ctx.rotate(rot);
    head3(ctx, U, m, rnd, { x: -U * 0.3, y: -U * 0.12, L: (1.8 + rnd() * 0.35) * U, open: rnd() < 0.55, hornStyle: Math.floor(rnd() * 5) });
    ctx.restore();
  }

  // ── Weapons behind the frame ──
  function sword(ctx, len, m, guardM, seed) {
    const bw = len * 0.055, gl = len * 0.2, grip = len * 0.15;
    // blade (tip up at -len)
    const blade = () => { ctx.beginPath(); ctx.moveTo(-bw / 2, 0); ctx.lineTo(-bw / 2, -len * 0.82); ctx.lineTo(0, -len); ctx.lineTo(bw / 2, -len * 0.82); ctx.lineTo(bw / 2, 0); ctx.closePath(); };
    castShadow(ctx, blade, len * 0.2);
    blade(); const bg = ctx.createLinearGradient(-bw / 2, 0, bw / 2, 0);
    bg.addColorStop(0, rgb(shade(m, 0.85))); bg.addColorStop(0.48, rgb(shade(m, 0.55))); bg.addColorStop(0.52, rgb(shade(m, 0.3))); bg.addColorStop(1, rgb(shade(m, 0.6)));
    ctx.fillStyle = bg; ctx.fill();
    ctx.save(); blade(); ctx.clip(); grit(ctx, 0, -len / 2, len, m, seed); ctx.restore();
    ctx.beginPath(); ctx.moveTo(0, -len * 0.05); ctx.lineTo(0, -len * 0.75); ctx.lineWidth = Math.max(0.8, bw * 0.18); ctx.strokeStyle = 'rgba(0,0,0,0.5)'; ctx.stroke();   // fuller
    // guard
    const guard = () => { ctx.beginPath(); ctx.moveTo(-gl / 2, -bw * 0.4); ctx.quadraticCurveTo(0, -bw * 0.9, gl / 2, -bw * 0.4); ctx.lineTo(gl / 2 - bw * 0.3, bw * 0.45); ctx.quadraticCurveTo(0, 0, -gl / 2 + bw * 0.3, bw * 0.45); ctx.closePath(); };
    sculpt(ctx, guard, guardM, [-gl / 2, -bw, gl / 2, bw], seed + 1);
    // grip (leather wrap) + pommel
    ctx.fillStyle = '#2a1b10'; ctx.fillRect(-bw * 0.38, bw * 0.4, bw * 0.76, grip);
    for (let i = 0; i < 6; i++) { ctx.beginPath(); ctx.moveTo(-bw * 0.38, bw * 0.5 + i * grip / 6); ctx.lineTo(bw * 0.38, bw * 0.5 + (i + 0.6) * grip / 6); ctx.lineWidth = Math.max(0.6, bw * 0.08); ctx.strokeStyle = 'rgba(120,90,60,0.5)'; ctx.stroke(); }
    const pm = () => { ctx.beginPath(); ctx.arc(0, bw * 0.4 + grip + bw * 0.35, bw * 0.45, 0, TAU); };
    sculpt(ctx, pm, guardM, [-bw / 2, grip, bw / 2, grip + bw], seed + 2, { bright: 0.08 });
  }
  function axe(ctx, len, m, seed, double) {
    const hw = len * 0.035;
    // wooden haft
    const haft = () => { ctx.beginPath(); ctx.rect(-hw / 2, -len, hw, len * 1.05); };
    castShadow(ctx, haft, len * 0.15);
    haft(); const wg = ctx.createLinearGradient(-hw / 2, 0, hw / 2, 0); wg.addColorStop(0, '#5c3d22'); wg.addColorStop(0.5, '#3a2514'); wg.addColorStop(1, '#1e130a'); ctx.fillStyle = wg; ctx.fill();
    for (let i = 0; i < 5; i++) { ctx.beginPath(); ctx.moveTo(-hw * 0.3 + i * hw * 0.15, -len); ctx.lineTo(-hw * 0.3 + i * hw * 0.15, len * 0.05); ctx.lineWidth = 0.5; ctx.strokeStyle = 'rgba(0,0,0,0.25)'; ctx.stroke(); }
    // blade(s) near the top
    const head = (dir) => () => {
      ctx.beginPath(); ctx.moveTo(dir * hw * 0.5, -len * 0.92); ctx.lineTo(dir * len * 0.12, -len * 0.98);
      ctx.quadraticCurveTo(dir * len * 0.3, -len * 0.86, dir * len * 0.26, -len * 0.7);
      ctx.quadraticCurveTo(dir * len * 0.2, -len * 0.74, dir * len * 0.12, -len * 0.72); ctx.lineTo(dir * hw * 0.5, -len * 0.8); ctx.closePath();
    };
    for (const d of double ? [-1, 1] : [1]) { castShadow(ctx, head(d), len * 0.2); sculpt(ctx, head(d), m, [d * hw, -len, d * len * 0.3, -len * 0.7], seed + (d > 0 ? 3 : 4), { bright: 0.05 }); }
    ctx.beginPath(); ctx.rect(-hw * 0.8, -len * 0.93, hw * 1.6, len * 0.15); ctx.fillStyle = rgb(shade(m, 0.3)); ctx.fill();
  }
  function mace(ctx, len, m, seed, hammer) {
    const hw = len * 0.035;
    const haft = () => { ctx.beginPath(); ctx.rect(-hw / 2, -len * 0.8, hw, len * 0.85); };
    castShadow(ctx, haft, len * 0.15);
    haft(); const wg = ctx.createLinearGradient(-hw / 2, 0, hw / 2, 0); wg.addColorStop(0, rgb(shade(m, 0.6))); wg.addColorStop(1, rgb(shade(m, 0.15))); ctx.fillStyle = wg; ctx.fill();
    if (hammer) {
      const hd = () => { ctx.beginPath(); ctx.rect(-len * 0.15, -len * 0.98, len * 0.3, len * 0.16); };
      castShadow(ctx, hd, len * 0.2); sculpt(ctx, hd, m, [-len * 0.15, -len, len * 0.15, -len * 0.82], seed + 5);
      ctx.beginPath(); ctx.moveTo(-len * 0.15, -len * 0.98); ctx.lineTo(-len * 0.24, -len * 0.9); ctx.lineTo(-len * 0.15, -len * 0.82); ctx.closePath(); ctx.fillStyle = rgb(shade(m, 0.35)); ctx.fill();
    } else {
      for (let k = 0; k < 6; k++) {
        const a = k / 6 * TAU, f = () => { ctx.beginPath(); ctx.moveTo(0, -len * 0.9); ctx.lineTo(Math.cos(a) * len * 0.13, -len * 0.9 + Math.sin(a) * len * 0.13 * 0.6 - len * 0.04); ctx.lineTo(Math.cos(a) * len * 0.13, -len * 0.9 + Math.sin(a) * len * 0.13 * 0.6 + len * 0.06); ctx.closePath(); };
        sculpt(ctx, f, m, [-len * 0.14, -len, len * 0.14, -len * 0.8], seed + 6 + k);
      }
      ctx.beginPath(); ctx.arc(0, -len * 0.89, len * 0.06, 0, TAU); ctx.fillStyle = rgb(shade(m, 0.4)); ctx.fill();
    }
  }
  // ── Polearms: long wooden haft, steel head at the top (base at 0, tip at -len) ──
  function haftWood(ctx, len, hw) {
    const haft = () => { ctx.beginPath(); ctx.rect(-hw / 2, -len * 0.88, hw, len * 0.93); };
    castShadow(ctx, haft, len * 0.1);
    haft(); const wg = ctx.createLinearGradient(-hw / 2, 0, hw / 2, 0); wg.addColorStop(0, '#5c3d22'); wg.addColorStop(0.5, '#3a2514'); wg.addColorStop(1, '#1e130a'); ctx.fillStyle = wg; ctx.fill();
    for (let i = 0; i < 4; i++) { ctx.beginPath(); ctx.moveTo(-hw * 0.3 + i * hw * 0.2, -len * 0.88); ctx.lineTo(-hw * 0.3 + i * hw * 0.2, len * 0.05); ctx.lineWidth = 0.5; ctx.strokeStyle = 'rgba(0,0,0,0.25)'; ctx.stroke(); }
    for (const y of [-len * 0.35, -len * 0.6]) { ctx.beginPath(); ctx.rect(-hw * 0.65, y, hw * 1.3, hw * 1.2); ctx.fillStyle = '#2a2016'; ctx.fill(); }   // leather wraps
  }
  function polearm(ctx, len, m, seed, kind) {
    const hw = len * 0.026;
    haftWood(ctx, len, hw);
    // socket
    const sock = () => { ctx.beginPath(); ctx.moveTo(-hw * 0.9, -len * 0.86); ctx.lineTo(-hw * 0.6, -len * 0.92); ctx.lineTo(hw * 0.6, -len * 0.92); ctx.lineTo(hw * 0.9, -len * 0.86); ctx.closePath(); };
    sculpt(ctx, sock, m, [-hw, -len * 0.93, hw, -len * 0.85], seed);
    if (kind === 'spear') {
      const bl = () => { ctx.beginPath(); ctx.moveTo(0, -len); ctx.quadraticCurveTo(hw * 3.2, -len * 0.95, hw * 0.7, -len * 0.91); ctx.lineTo(-hw * 0.7, -len * 0.91); ctx.quadraticCurveTo(-hw * 3.2, -len * 0.95, 0, -len); ctx.closePath(); };
      castShadow(ctx, bl, len * 0.1); sculpt(ctx, bl, MATS.steel, [-hw * 3, -len, hw * 3, -len * 0.9], seed + 1, { bright: 0.1 });
      ctx.beginPath(); ctx.moveTo(0, -len * 0.99); ctx.lineTo(0, -len * 0.92); ctx.lineWidth = Math.max(0.6, hw * 0.25); ctx.strokeStyle = 'rgba(0,0,0,0.45)'; ctx.stroke();
    } else if (kind === 'halberd') {
      const top = () => { ctx.beginPath(); ctx.moveTo(0, -len); ctx.lineTo(hw * 0.8, -len * 0.92); ctx.lineTo(-hw * 0.8, -len * 0.92); ctx.closePath(); };
      const axeB = () => { ctx.beginPath(); ctx.moveTo(hw * 0.5, -len * 0.92); ctx.lineTo(len * 0.1, -len * 0.95); ctx.quadraticCurveTo(len * 0.135, -len * 0.87, len * 0.11, -len * 0.79); ctx.lineTo(hw * 0.5, -len * 0.83); ctx.closePath(); };
      const hook = () => { ctx.beginPath(); ctx.moveTo(-hw * 0.5, -len * 0.9); ctx.quadraticCurveTo(-len * 0.06, -len * 0.89, -len * 0.085, -len * 0.94); ctx.quadraticCurveTo(-len * 0.05, -len * 0.86, -hw * 0.5, -len * 0.85); ctx.closePath(); };
      for (const [p, s] of [[top, 2], [axeB, 3], [hook, 4]]) { castShadow(ctx, p, len * 0.1); sculpt(ctx, p, MATS.steel, [-len * 0.09, -len, len * 0.14, -len * 0.78], seed + s, { bright: 0.06 }); }
    } else {   // glaive: long curved single-edged blade
      const bl = () => { ctx.beginPath(); ctx.moveTo(-hw * 0.6, -len * 0.91); ctx.lineTo(-hw * 0.6, -len * 0.97); ctx.quadraticCurveTo(hw * 0.5, -len * 1.04, hw * 3.6, -len * 1.02);
        ctx.quadraticCurveTo(hw * 3.2, -len * 0.95, hw * 0.6, -len * 0.91); ctx.closePath(); };
      castShadow(ctx, bl, len * 0.1); sculpt(ctx, bl, MATS.steel, [-hw, -len * 1.04, hw * 3.6, -len * 0.9], seed + 5, { bright: 0.08 });
    }
  }

  // ── Shields (drawn behind the picture, so only the parts around the frame show) ──
  const FIELDS = [['#4a1416', '#1a0607'], ['#14254a', '#060c1a'], ['#1d3a20', '#091409'], ['#2b2b30', '#0d0d10'], ['#4a3412', '#1a1105']];
  function shield(ctx, cx, cy, r, m, accent, rnd) {
    const style = ['heater', 'heater', 'round', 'kite'][Math.floor(rnd() * 4)];
    const [f1, f2] = FIELDS[Math.floor(rnd() * FIELDS.length)];
    const W = r * (2.2 + rnd() * 0.3), H = style === 'kite' ? W * 1.45 : style === 'round' ? W : W * 1.18, top = cy - H * 0.5 + r * 0.08;
    const path = () => {
      ctx.beginPath();
      if (style === 'round') ctx.arc(cx, cy + r * 0.05, W / 2, 0, TAU);
      else {
        const x0 = cx - W / 2, x1 = cx + W / 2, bot = top + H;
        ctx.moveTo(x0, top + H * 0.04); ctx.quadraticCurveTo(cx, top - H * 0.04, x1, top + H * 0.04);
        ctx.lineTo(x1, top + H * (style === 'kite' ? 0.32 : 0.45));
        ctx.quadraticCurveTo(x1 - W * 0.04, bot - H * 0.18, cx, bot);
        ctx.quadraticCurveTo(x0 + W * 0.04, bot - H * 0.18, x0, top + H * (style === 'kite' ? 0.32 : 0.45));
        ctx.closePath();
      }
    };
    castShadow(ctx, path, r * 2.2);
    // field: painted / planked, lit from the top-left
    path(); const g = ctx.createLinearGradient(cx - W / 2, top, cx + W / 2, top + H); g.addColorStop(0, f1); g.addColorStop(1, f2); ctx.fillStyle = g; ctx.fill();
    ctx.save(); path(); ctx.clip();
    if (style === 'round') {   // planks
      for (let i = -4; i <= 4; i++) { ctx.beginPath(); ctx.moveTo(cx + i * W / 9, top - r); ctx.lineTo(cx + i * W / 9, top + H + r); ctx.lineWidth = Math.max(0.8, r * 0.015); ctx.strokeStyle = 'rgba(0,0,0,0.45)'; ctx.stroke(); }
    } else {                   // a heraldic division in the accent metal
      const d = Math.floor(rnd() * 3);
      ctx.fillStyle = rgb(shade(accent, 0.45), 0.85);
      ctx.beginPath();
      if (d === 0) ctx.rect(cx - W * 0.09, top - r, W * 0.18, H + 2 * r);                                   // pale
      else if (d === 1) { ctx.moveTo(cx - W, top + H * 0.75); ctx.lineTo(cx, top + H * 0.35); ctx.lineTo(cx + W, top + H * 0.75); ctx.lineTo(cx + W, top + H * 0.95); ctx.lineTo(cx, top + H * 0.55); ctx.lineTo(cx - W, top + H * 0.95); }   // chevron
      else { ctx.moveTo(cx - W, top - r); ctx.lineTo(cx - W * 0.7, top - r); ctx.lineTo(cx + W, top + H * 0.9); ctx.lineTo(cx + W * 0.7, top + H + r); }   // bend
      ctx.fill();
    }
    grit(ctx, cx, cy, Math.max(W, H), MATS.iron, 501);
    const sh = ctx.createRadialGradient(cx - W * 0.25, top + H * 0.2, 0, cx, cy, Math.max(W, H) * 0.75);
    sh.addColorStop(0, 'rgba(255,255,255,0.07)'); sh.addColorStop(1, 'rgba(0,0,0,0.45)'); ctx.fillStyle = sh; ctx.fillRect(cx - W, top - r, W * 2, H + 2 * r);
    ctx.restore();
    // metal rim + rivets
    const rimW = r * 0.09;
    path(); ctx.lineWidth = rimW; ctx.strokeStyle = rgb(shade(m, 0.35)); ctx.stroke();
    ctx.save(); ctx.translate(-rimW * 0.15, -rimW * 0.2); path(); ctx.lineWidth = rimW * 0.35; ctx.strokeStyle = rgb(shade(m, 0.85), 0.6); ctx.stroke(); ctx.restore();
    path(); ctx.lineWidth = 1; ctx.strokeStyle = 'rgba(0,0,0,0.8)'; ctx.stroke();
    return style;
  }

  /** Weapons crossed or standing behind the frame (and shields behind the picture). */
  function weapons(ctx, cx, cy, r, m, accent, rnd) {
    const kinds = ['swords', 'axes', 'greatsword', 'sword_axe', 'maces', 'hammer_sword', 'double_axes',
                   'spears', 'halberds', 'glaives', 'halberd_sword', 'spear_axe',
                   'shield', 'shield_swords', 'shield_spears', 'shield_axes', 'shield_halberds'];
    let k = kinds[Math.floor(rnd() * kinds.length)];
    const len = r * (2.5 + rnd() * 0.5), tilt = 0.6 + rnd() * 0.25, plen = len * 1.25;
    const withShield = k.startsWith('shield');
    if (withShield) k = k === 'shield' ? 'none' : k.slice(7);
    const at = (ang, fn) => { ctx.save(); ctx.translate(cx, cy + r * 0.95); ctx.rotate(ang); ctx.translate(0, len * 0.12); fn(); ctx.restore(); };
    if (k === 'greatsword') { ctx.save(); ctx.translate(cx, cy + len * 0.5); sword(ctx, len * 1.05, MATS.steel, accent, 301); ctx.restore(); }
    else if (k === 'swords') { at(-tilt, () => sword(ctx, len, MATS.steel, accent, 311)); at(tilt, () => sword(ctx, len, MATS.steel, accent, 321)); }
    else if (k === 'axes') { at(-tilt, () => axe(ctx, len, m, 331, false)); ctx.save(); ctx.translate(cx, cy + r * 0.95); ctx.rotate(tilt); ctx.scale(-1, 1); ctx.translate(0, len * 0.12); axe(ctx, len, m, 341, false); ctx.restore(); }
    else if (k === 'double_axes') { at(-tilt, () => axe(ctx, len, m, 351, true)); at(tilt, () => axe(ctx, len, m, 361, true)); }
    else if (k === 'sword_axe') { at(-tilt, () => sword(ctx, len, MATS.steel, accent, 371)); at(tilt, () => axe(ctx, len, m, 381, false)); }
    else if (k === 'maces') { at(-tilt, () => mace(ctx, len * 0.8, m, 391, false)); at(tilt, () => mace(ctx, len * 0.8, m, 401, false)); }
    else if (k === 'hammer_sword') { at(-tilt, () => mace(ctx, len * 0.85, m, 411, true)); at(tilt, () => sword(ctx, len, MATS.steel, accent, 421)); }
    else if (k === 'spears') { at(-tilt * 0.8, () => polearm(ctx, plen, m, 431, 'spear')); at(tilt * 0.8, () => polearm(ctx, plen, m, 441, 'spear')); }
    else if (k === 'halberds') { at(-tilt * 0.8, () => polearm(ctx, plen, m, 451, 'halberd')); ctx.save(); ctx.translate(cx, cy + r * 0.95); ctx.rotate(tilt * 0.8); ctx.scale(-1, 1); ctx.translate(0, len * 0.12); polearm(ctx, plen, m, 461, 'halberd'); ctx.restore(); }
    else if (k === 'glaives') { at(-tilt * 0.8, () => polearm(ctx, plen, m, 471, 'glaive')); ctx.save(); ctx.translate(cx, cy + r * 0.95); ctx.rotate(tilt * 0.8); ctx.scale(-1, 1); ctx.translate(0, len * 0.12); polearm(ctx, plen, m, 481, 'glaive'); ctx.restore(); }
    else if (k === 'halberd_sword') { at(-tilt * 0.8, () => polearm(ctx, plen, m, 491, 'halberd')); at(tilt, () => sword(ctx, len, MATS.steel, accent, 501)); }
    else if (k === 'spear_axe') { at(-tilt * 0.8, () => polearm(ctx, plen, m, 511, 'spear')); at(tilt, () => axe(ctx, len, m, 521, false)); }
    if (withShield) shield(ctx, cx, cy, r, MATS[rnd() < 0.5 ? 'iron' : 'steel'], accent, rnd);
    return withShield ? 'shield_' + k : k;
  }

  // ── kinds offered in the generator ──
  const KINDS = {
    random: 'Random', dragon: 'Dragon', weapons: 'Weapons', basic: 'Basic', stone: 'Stone', iron: 'Iron', silver: 'Silver', gold: 'Gold',
    bronze: 'Bronze', winged: 'Winged', spiked: 'Spiked', none: 'None'
  };
  const GEMS = ['#3c7fb8', '#9b1c26', '#2f7d4a', '#6b3fa0', '#b8862c', '#1f6f74'];

  function generate(ctx, kind, seed, shape, cx, cy, r, opts) {
    opts = opts || {};
    const rnd = rng(seed), pick = (a) => a[Math.floor(rnd() * a.length)];
    if (kind === 'none') return { kind };
    if (kind === 'random') kind = pick(['dragon', 'dragon', 'weapons', 'weapons', 'basic', 'stone', 'winged', 'spiked', 'iron', 'gold', 'silver', 'bronze']);
    const matFor = { stone: 'stone', iron: 'iron', silver: 'steel', gold: 'gold', bronze: 'bronze' };
    const matName = matFor[kind] || pick(['iron', 'iron', 'steel', 'stone', 'gold', 'bronze', 'blacksteel']);
    const m = MATS[matName];
    const accName = rnd() < 0.35 ? pick(['steel', 'gold', 'iron', 'bronze', 'blacksteel']) : matName;
    const acc = MATS[accName === 'stone' ? 'iron' : accName];
    const wk = 0.15 + rnd() * 0.11;
    // compact: just the band (and rivets), sized so its OUTER edge is opts.outer; drawn over a small avatar
    if (opts.compact) r = opts.outer / (1 + wk);
    const w = r * wk;
    const ng = pick([0, 1, 1, 2, 2, 3]), grooves = []; for (let i = 0; i < ng; i++) grooves.push((i + 1) / (ng + 1));
    let orn = 'none';
    if (kind === 'dragon') orn = pick(['dragon', 'dragon', 'dragon_weapons']);
    else if (kind === 'weapons') orn = pick(['weapons', 'weapons', 'weapons_crest', 'weapons_spikes']);
    else if (kind === 'winged') orn = pick(['wings', 'wings_crest']);
    else if (kind === 'spiked') orn = pick(['spikes', 'spikes_crest']);
    else if (kind === 'basic') orn = pick(['none', 'none', 'crest']);
    else orn = pick(['none', 'none', 'crest', 'wings', 'spikes', 'dragon', 'weapons']);
    if (opts.compact) {
      band(ctx, shape, cx, cy, r, w, m, { grooves }, rnd);
      const nr0 = pick([0, 4, 6, 8, 8, 12]);
      if (nr0 && !m.stone && opts.outer > 40) rivets(ctx, shape, cx, cy, r, w, rnd() < 0.5 ? m : acc, nr0, rnd() < 0.5 ? 0 : Math.PI / nr0);
      return { kind, material: matName, accent: accName, ornament: orn, inner: r };
    }
    const dragonMat = MATS[pick(['iron', 'steel', 'steel', 'bronze', 'gold', matName === 'stone' ? 'stone' : (matName === 'blacksteel' ? 'iron' : matName)])];
    if (orn.includes('weapons')) {
      // behind the frame AND behind the picture: clip the picture area out
      ctx.save(); ctx.beginPath(); ctx.rect(cx - r * 10, cy - r * 10, r * 20, r * 20); shapePath(ctx, shape, cx, cy, r + w * 0.5, true); ctx.clip('evenodd');
      weapons(ctx, cx, cy, r, MATS[pick(['iron', 'steel', 'bronze', 'blacksteel'])], acc.stone ? MATS.iron : acc, rnd);
      ctx.restore();
    }
    if (orn.includes('wings')) { const n = 3 + Math.floor(rnd() * 5), reach = 0.75 + rnd() * 0.4; bladeWing(ctx, cx, cy, r, -1, acc, n, reach, rnd); bladeWing(ctx, cx, cy, r, 1, acc, n, reach, rnd); }
    if (orn.includes('spikes')) { const n = pick([6, 8, 10, 12, 16]); spikes(ctx, shape, cx, cy, r, acc, n, 0.14 + rnd() * 0.18, rnd() < 0.5 ? 0 : Math.PI / n); }
    band(ctx, shape, cx, cy, r, w, m, { grooves }, rnd);
    if (orn.includes('crest')) crest(ctx, cx, cy, r, acc, rnd() < 0.7 ? pick(GEMS) : null, rnd);
    const nr = pick([0, 4, 6, 8, 8, 12]);
    if (nr && !m.stone) rivets(ctx, shape, cx, cy, r, w, rnd() < 0.5 ? m : acc, nr, rnd() < 0.5 ? 0 : Math.PI / nr);
    if (orn.startsWith('dragon')) dragon(ctx, shape, cx, cy, r, w, dragonMat, rnd);
    return { kind, material: matName, accent: accName, ornament: orn };
  }

  global.SilvershireFrames = {
    KINDS, MATS, shapePath, shade, rgb, rivet, grit, texture,
    newSeed: () => ((Math.random() * 4294967295) >>> 0) || 1,
    isValid: (id) => id === 'none' || /^gen:(random|dragon|weapons|basic|stone|iron|silver|gold|bronze|winged|spiked|none):\d{1,10}$/.test(id || ''),
    /** Full frame on a square canvas of size S: picture radius S * PIC (ornaments reach ~2.2 r). */
    PIC: 0.22,
    setCorners(o) { if (o && o.rounded > 0) CORNER.rounded = o.rounded; if (o && o.squircle > 0) CORNER.squircle = o.squircle; },
    /** Render to a new canvas. mode 'full' (profile) or 'compact' (small avatars). Returns the canvas. */
    render(id, shape, S, mode) {
      const c = document.createElement('canvas'); c.width = c.height = S;
      const x = c.getContext('2d');
      if (mode === 'compact') this.drawFrame(x, id, shape, S / 2, S / 2, S / 2, { compact: true });
      else this.drawFrame(x, id, shape, S / 2, S / 2, S * 0.22);
      return c;
    },
    /** id: "gen:<kind>:<seed>" (generated) or "none". */
    /** opts.compact: band only, its outer edge at radius r (for small avatars; drawn over the picture). */
    drawFrame(ctx, id, shape, cx, cy, r, opts) {
      if (!id || id === 'none') return { kind: 'none' };
      const m = /^gen:([a-z]+):(\d+)$/.exec(id);
      if (!m) return { kind: 'none' };
      ctx.save();
      const info = generate(ctx, m[1], parseInt(m[2], 10), shape || 'circle', cx, cy, r, opts && opts.compact ? { compact: true, outer: r } : null);
      ctx.restore();
      return info;
    }
  };
})(typeof window !== 'undefined' ? window : globalThis);

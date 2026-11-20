# Customizing

## App name and icon for your group

In the app, **Profile > App identity**:

- **Save in-app branding** changes the name and logo inside the app on that phone.
- **Generate my branded APK** asks the server to build a copy of the app with your name and icon
  as the name on the home screen (needs the admin password, and Android build tools on the server
  computer: see [ANDROID.md](ANDROID.md)). It replaces the APK at `/download`.

The web app has the same name setting under Settings > App identity (per browser).

## Theme

- **Silvershire** (default): forged iron panels, dark stone, metal rimmed chat bubbles.
- **Classic**: the original flat dark look.

Switch in the app under Profile > Theme, and on the web under Settings > Appearance > Theme.

The theme art is generated, not drawn by hand: `tools/gen_theme_assets.py` (Python 3 with
`numpy` and `Pillow`) renders the stone texture, panels, bars, buttons and bubble rims as
Android 9-patches and as web images. After changing it:

```bash
pip install numpy pillow
python tools/gen_theme_assets.py      # writes android/app/src/main/res/drawable-* and tools/theme-out/
python tools/embed_theme_css.py       # puts the web versions into server/webclient.html
```

## Profile borders

`server/frames.js` draws every border from an id like `gen:dragon:123456789` (style + seed), so
each device draws exactly the same picture from those few characters. Styles: Random, Dragon,
Weapons (swords, axes, maces, polearms, shields), Basic, Stone, Iron, Silver, Gold, Bronze,
Winged, Spiked.

To change how borders look, edit `server/frames.js`, then `python tools/sync_frames.py` (copies it
into the app). `tools/frames-preview.html` (open it in a browser) shows a gallery of generated
borders while you work.

## Colors, fonts, bubbles

Most colors, the font, bubble shape and chat backgrounds can be changed by each person in the
app (Profile) and the web app (Settings > Appearance).

"""Copy server/frames.js (the border drawing code) into the Android app's assets.
After changing how borders look, also raise CACHE_VERSION in
android/app/src/main/java/com/silvershireinn/app/utils/ProfileFrames.kt so phones redraw them."""
import os, shutil
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = os.path.join(root, 'server', 'frames.js')
dst = os.path.join(root, 'android', 'app', 'src', 'main', 'assets', 'frames.js')
os.makedirs(os.path.dirname(dst), exist_ok=True)
shutil.copyfile(src, dst)
print('Copied', src, '->', dst)
print('Remember: raise CACHE_VERSION in ProfileFrames.kt if borders look different now.')

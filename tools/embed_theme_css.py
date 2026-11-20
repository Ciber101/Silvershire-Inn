"""Re-embed tools/theme-out/theme.css.txt (data URLs) into webclient.html's body.theme-ornate block."""
import os, re
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
p = os.path.join(ROOT, 'server', 'webclient.html')
s = open(p, 'rb').read().decode('utf-8')
nl = '\r\n' if '\r\n' in s else '\n'
new = open(os.path.join(ROOT, 'tools', 'theme-out', 'theme.css.txt')).read().strip().splitlines()
vals = {l.split(':', 1)[0].strip(): l for l in new}
def sub(m):
    k = m.group(1)
    return '    ' + vals[k] if k in vals else m.group(0)
s2, n = re.subn(r'^    (--orn-[a-z-]+): url\("data:image/png;base64,[^"]*"\);', sub, s, flags=re.M)
open(p, 'wb').write(s2.encode('utf-8'))
print('replaced', n)

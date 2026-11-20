# Reaching your server from outside your home

At home, phones reach the server at its local address (for example `192.168.1.50:8080`). To use
Silvershire Inn on mobile data or at a friend's house, pick one of these:

| Option | Effort | Notes |
| --- | --- | --- |
| [A. Tailscale (private network)](#a-tailscale) | Easiest | Nothing is open to the internet. Everyone installs Tailscale and joins your network. |
| [B. Your own domain with HTTPS](#b-a-domain-with-https-caddy) | Medium | A normal web address (`https://chat.example.org`). Needs a router port forward. |
| C. A rented server (VPS) | Medium | Run the server on the VPS and follow B there (no port forward needed). |

---

## A. Tailscale

1. Install Tailscale (https://tailscale.com) on the server computer and sign in.
2. Each person installs Tailscale on their phone and you invite them to your tailnet (or share
   the server machine with them from the Tailscale admin console).
3. In the app, the server address is the server's Tailscale address, e.g. `100.101.102.103:8080`
   or its MagicDNS name, e.g. `myserver:8080`.

Calls work over Tailscale as well, and the share link uses the Tailscale address automatically
(it only works for people in your tailnet, which is the point).

## B. A domain with HTTPS (Caddy)

### 1. A name that points at your home

If you have a domain, add an `A` record for e.g. `chat.example.org` with your home's public IP.

No domain? A free dynamic DNS name works too, e.g. DuckDNS (https://www.duckdns.org):
create a name, then keep it updated with your current home IP. `server/update-duckdns.example.bat`
shows how (copy it, fill in **your** name and token; never share the token).

### 2. Forward ports on your router

Forward these to the server computer's local address:

| Port | Protocol | For |
| --- | --- | --- |
| 80 | TCP | HTTPS certificate (Let's Encrypt) |
| 443 | TCP | The app and web app over HTTPS |
| 3478 | UDP + TCP | Calls (TURN relay) |
| 49160-49200 | UDP | Calls (TURN relay) |

Port 8080 does **not** need to be forwarded when you use Caddy.

### 3. Run Caddy in front of the server

Caddy (https://caddyserver.com/download) gets and renews the HTTPS certificate by itself.

1. Download Caddy into the `server` folder (`caddy.exe` on Windows).
2. Copy `server/Caddyfile.example` to `server/Caddyfile` and put your name in it:
   ```
   chat.example.org {
       reverse_proxy 127.0.0.1:8080
   }
   ```
3. Start it next to the server: `server/start-https.bat` on Windows, or `caddy run --config
   Caddyfile` in the `server` folder.

Now the app's server address is `https://chat.example.org` and the web app is at the same address.

## The share link

**Profile > Share App / Invite** in the app (and **Share app link** on the web) shares
`<public address>/download`, the page where people download the Android app.

The server learns its public address from the first people who sign in through it (through
Caddy that is your domain with `https://`). Home network addresses (192.168.x, 10.x, 172.16-31.x,
localhost) are never used, because they don't work from outside. To set it yourself, add it to `server/data/config.json`
and restart the server:

```json
{
  "publicUrl": "https://chat.example.org"
}
```

If the server doesn't know a public address yet, the app shares its own server address instead.

## Admin panel from another computer

The admin panel (port 8081) only answers on the server computer. To allow other computers on your
network add `"adminRemote": true` to `server/data/config.json`. Don't forward port 8081 on your
router.

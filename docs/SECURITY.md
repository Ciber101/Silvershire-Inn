# Security and privacy

## What the server can and can't see

- **Messages, pictures, videos, voice notes and files are end-to-end encrypted** on the sending
  device (AES-256-GCM, with the key wrapped by RSA-OAEP for each recipient and for your own
  devices). Each account's key pair is made on the device; the private key never leaves its
  owner's devices (the web app gets it only when you paste your exported identity key).
- **Nothing is ever sent unencrypted.** If a friend's (or every group member's) public key isn't
  known yet, the message waits on your phone and goes out encrypted when the key arrives (the web
  app shows "Not sent. Retry"). The server also **refuses** any chat message or chat file that
  isn't end-to-end encrypted, so even a modified app can't put readable messages on it.
- Group chats: every message has a fresh key, wrapped for each member.
- Voice and video calls use WebRTC, which encrypts audio and video (DTLS-SRTP) between the
  phones; the TURN relay only forwards encrypted packets.
- The server does know: usernames, display names, who is friends with whom, who is in which
  group, when people are online, message sizes and times, profile pictures and banners (these
  are shared with your friends and are not end-to-end encrypted; they are encrypted at rest).
- **Trust in public keys:** the server hands out the public keys. Since you run the server, that
  is you; someone who controls the server could swap a key for a new conversation. There is no
  key-verification screen yet.
- **Use HTTPS (or Tailscale) outside your home network.** Message contents are protected either
  way, but over plain HTTP the server password and sign-in tokens travel readable.

## Encryption at rest (the vault)

Everything the server keeps in `server/data` (accounts, chat history, groups, receipts, pictures,
upload details, crash reports) is encrypted with one random **data key** (AES-256-GCM). The data
key sits in `data/keyring.json`, wrapped by:

- **Windows**: DPAPI, tied to the Windows account running the server. Copies of the folder are
  unreadable elsewhere.
- **Linux / macOS**: no system protection is used, so **set a master password**, or the key is
  readable by anyone who can read the folder.
- **Master password** (optional, recommended): nothing can be read until it's entered after each
  start, on http://localhost:8081/admin/vault or in the server window. **Lock now** on that page
  drops the key from memory and disconnects everyone.
- **Recovery key**: shown once on the Vault page. It is the data key itself: the only way back in
  if the master password is lost or Windows is reinstalled. Store it offline.

The server and admin passwords are stored inside the vault too, and can only be changed on the
Vault page with the current password (editing files does nothing).

## Passwords

| Password | Who uses it | Where to change it |
| --- | --- | --- |
| Server password | Everyone, once, when creating an account on your server | Vault page |
| Admin password | Only you: admin panel, publishing updates, app branding | Vault page |
| Master password (optional) | Only you: unlocks the data after each start | Vault page |
| Account password | Each person, for their own account | In the app |

Pick long ones and keep the admin and master passwords to yourself.

## Panic phrase

In the app (Profile > Panic word) each person can set a lock phrase and an unlock phrase. Typing
or (optionally) saying the lock phrase locks the account on every device, closes their chats on
friends' devices, and keeps them closed, even after reinstalling, until the unlock phrase. Only
salted hashes of the phrases leave the phone.

The server owner can also list accounts whose panic phrase **locks the whole server** (Vault
page). The unlock phrase then opens it again without a restart.

## Network

- Use HTTPS when the server is reachable from the internet (see [INTERNET.md](INTERNET.md)).
- The admin panel answers only on the server computer unless you set `"adminRemote": true`.
  Never forward port 8081.
- Repeated wrong passwords are slowed down; addresses can be banned from the admin panel.

## Backups

Back up the whole `server/data` folder (it's encrypted) **and** your recovery key, kept apart.
Without the recovery key (or the same Windows account / master password) a backup can't be read.
Also back up the Android signing key (see [ANDROID.md](ANDROID.md#signing-important-for-updates)).

## Reporting a problem

If you find a security problem, please open an issue without exploit details and ask for a
private contact, or contact the maintainer through Ko-fi.

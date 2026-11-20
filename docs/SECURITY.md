# Security and privacy

## What the server can and can't see

- **Messages, pictures, videos, voice notes and files are end-to-end encrypted** on the sending
  device. Each account has a key pair made on the device; the private key never leaves the
  devices of its owner (the web app gets it only when you paste your exported identity key).
  The server stores and forwards encrypted data only.
- Group chats use a group key shared encrypted to each member.
- The server does know: usernames, who is friends with whom, who is in which group, when people
  are online, message sizes and times, profile pictures, banners and display names.

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

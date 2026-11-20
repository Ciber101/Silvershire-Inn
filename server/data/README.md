# Server data

Everything the server stores lives here: accounts, encrypted chat history, pictures, the
encryption keyring, the published APK (`download/silvershire-inn.apk`). It is created on the
first start and is never committed to git (see `.gitignore`).

Optional settings go in `config.json` (copy `config.example.json`):

| Key | Meaning |
| --- | --- |
| `publicUrl` | The address people reach the server on, e.g. `"https://chat.example.org"`, used for the share / invite link. Learned automatically if left out. |
| `adminRemote` | `true` lets other computers open the admin panel (port 8081). Default: this computer only. |

Passwords are not set here: they live encrypted in the vault (change them on the admin Vault page).
Back this folder up together with your recovery key (see `docs/SECURITY.md`).

# Troubleshooting

**The app says it can't reach the server**
- Is the server window open and showing "running on port 8080"?
- At home: use the server computer's local address (`192.168.x.x:8080`), not `localhost`.
- Firewall: allow Node.js on private networks (Windows asks on the first start; otherwise
  Windows Security > Firewall > Allow an app).
- From outside: see [INTERNET.md](INTERNET.md). Test the address in the phone's browser:
  `<address>/api/version` should show a short text with the version.

**"Server is locked"**
The server has a master password and was restarted. Open http://localhost:8081/admin/vault on
the server computer and unlock it (or type the master password in the server window).

**I lost the master password**
Use the recovery key on the Vault page. Without it the data can't be read; start over with an
empty `server/data` folder.

**Creating an account fails**
Check the server password (it's the one from the Vault page / `FIRST-RUN-PASSWORDS.txt`, not the
admin password). Usernames must be unique on the server.

**The web app can't sign in to my phone's account**
On the phone: Profile > Export Identity Key, then paste it on the web login. Signing in with a
different key is refused on purpose (it would lock the phone out).

**Nobody got the update message**
Raise `versionCode` in both `android/app/build.gradle.kts` and `APP_VERSION` in
`server/server.js`, publish again, and make sure the server is unlocked. The server announces
each version only once.

**"App not installed" when updating**
The new APK was signed with a different key (built on another computer). See
[ANDROID.md](ANDROID.md#signing-important-for-updates).

**Calls connect at home but not across networks**
Forward 3478 (UDP + TCP) and 49160-49200 (UDP) to the server, or use Tailscale.

**The share link opens the wrong address**
Set `"publicUrl"` in `server/data/config.json` and restart the server (see
[INTERNET.md](INTERNET.md#the-share-link)).

**Android build fails with a Java error**
Use JDK 17 (Android Studio's bundled `jbr`). Gradle doesn't run on much newer Java versions.

**Where are the logs?**
In the server window. Crash reports sent by apps are stored (encrypted) in
`server/data/crash_reports`.

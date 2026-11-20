// The server now sends the "new version" notice by itself the first time it runs (and is
// unlocked) with a newer app version, so nothing has to hold a password here.
console.log('  The update notice goes out automatically once the server is running and unlocked.');
console.log('  If the server has a master password: open http://localhost:8081/admin/vault and unlock it.');

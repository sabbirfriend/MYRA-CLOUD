# MYRA Cloud — Google Account Authentication + Shared Learning

This reference backend provides:
- Sign in with Google ID-token verification.
- Per-user session tokens.
- User IDs derived from the verified Google `sub` claim.
- Admin detection via `MYRA_ADMIN_EMAIL`.
- Shared approved knowledge available to all authenticated MYRA users.
- No user can authenticate as another user by changing a client-side user ID.

Environment variables:
- `MYRA_GOOGLE_WEB_CLIENT_ID` — Google OAuth Web client ID used by the Android app.
- `MYRA_ADMIN_EMAIL` — your admin Google email.
- `PORT` — optional, defaults to 8080.

Important production requirements:
- Deploy behind HTTPS.
- Replace in-memory `sessions`, `users`, and `notes` with a durable database.
- Add rate limiting, audit logging, key rotation and data deletion/export endpoints.
- Never put the Google OAuth client secret or service-account private key in the Android APK.
- Only privacy-filtered/approved knowledge should enter the shared pool.


## Google Drive + login
Set `MYRA_GOOGLE_WEB_CLIENT_ID`, `MYRA_DRIVE_ROOT_FOLDER_ID`, `MYRA_SESSION_SECRET`, and `GOOGLE_APPLICATION_CREDENTIALS` on the server. The server stores per-user profiles under a private `users/` folder and shared learning in `shared_learning.json`. The Android app sends only its cloud session token after Google login. Never package the service-account JSON or OAuth client secret in the Android app.

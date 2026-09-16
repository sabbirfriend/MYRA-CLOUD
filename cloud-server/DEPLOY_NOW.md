# MYRA Cloud — One-click deployment guide

## Deploy
1. Create a new service from this repository/folder using Docker.
2. Set the following server environment variables in the hosting provider's secret/environment settings:
   - MYRA_GOOGLE_WEB_CLIENT_ID = 538678379453-2uh1m2kso72gp60524oo7htjr6fvb7o4.apps.googleusercontent.com
   - MYRA_ADMIN_EMAIL = your admin Google account email
   - MYRA_DRIVE_ROOT_FOLDER_ID = 1-WTZG9WdxtLGytW0mpcH4fyMXtknlFmM
   - MYRA_SESSION_SECRET = a long random secret (32+ bytes recommended)
   - GOOGLE_APPLICATION_CREDENTIALS = provider-specific secure path to the service-account JSON, if the host supports file secrets.
3. Deploy and wait for the health check.
4. Open https://YOUR-HOST/health. It must return ok:true, googleAuth:true, drive:true, session:true.
5. Put https://YOUR-HOST into MYRA's Cloud URL setting.

## Important
- Do not commit or upload the service-account JSON.
- Do not put OAuth client secret or service-account private key in the Android app.
- If the hosting provider cannot mount a credential file, configure its native secret-file mechanism or switch to a workload identity/service-account integration supported by the provider.

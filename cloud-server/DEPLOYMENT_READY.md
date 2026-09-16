# MYRA Cloud — deployment-ready package

Configured values:
- Google Web Client ID: 538678379453-2uh1m2kso72gp60524oo7htjr6fvb7o4.apps.googleusercontent.com
- Google Drive root folder: 1-WTZG9WdxtLGytW0mpcH4fyMXtknlFmM

Required before production:
1. Set `MYRA_ADMIN_EMAIL` to the admin Google account email.
2. Upload the Service Account JSON to the hosting provider as a protected secret/file. Do not commit it.
3. Set `GOOGLE_APPLICATION_CREDENTIALS` to that protected file path.
4. Deploy the `cloud-server` service over HTTPS.
5. Copy the resulting HTTPS service URL into MYRA Settings → Learning/Cloud URL.
6. Open `<service-url>/health`; it should report `ok:true`.

The generated session secret is included only in this local example file. For production, generate a fresh secret in the hosting provider's secret manager.

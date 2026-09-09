# Google Sheets direct login setup

WoongPilot writes completed trip records directly to Google Sheets after the driver signs in with Google. Apps Script deployment is not used.

## One-time Google Cloud setup

1. In [Google Cloud Console](https://console.cloud.google.com/), create or select a project.
2. Enable **Google Sheets API** under APIs & Services → Library.
3. Configure the OAuth consent screen. For a personal/test build, add the Google account used in WoongPilot as a test user.
4. Under Credentials, create an **OAuth client ID → Android** client.
   - Package name: `kr.co.tesla.cameraalert`
   - For the debug APK produced in this folder, add this SHA-1 certificate fingerprint:
     `93:6B:73:C5:87:EF:BD:CD:67:2B:7C:36:B2:8C:3C:C9:70:69:C9:A7`
     If you later distribute a release-signed APK, register that release signing certificate's SHA-1 as well.
5. Install the new APK. Open **차계부 → Google Sheets 로그인 · 자동 기록**, paste the spreadsheet URL, then sign in with the same Google account that can edit that sheet.

The app requests the Google Sheets scope only to append trip rows and test access to the selected spreadsheet. A failed transmission remains queued on the phone and will be retried after a later completed trip.

## Columns written

Each completed trip appends this row to the first worksheet:

`VIN, start time (epoch ms), end time (epoch ms), distance km, battery used %, estimated kWh, Wh/km`

You can format the second and third columns as dates in Google Sheets if desired.

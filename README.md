# Lauf doch!

Android-App für adaptives Lauftraining: Tagesansicht, 14-Tage-Plan, Wochenrückblick mit Leistungskennzahlen und ein Dialog mit Claude, der den Plan nach Rückmeldungen, Strava-Daten und Beschwerden anpasst.

Aufbau wie „Luises Stempelkarte“: Die Oberfläche ist eine Web-App (`app/src/main/assets/index.html`), `MainActivity` stellt ihr über `window.Android` Claude, Strava und die Datensicherung bereit. Die Daten liegen lokal auf dem Handy.

## Einrichtung

1. **Signatur-Secrets** im Repository anlegen (Settings → Secrets and variables → Actions): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`. Danach baut jeder Push auf `main` eine APK und veröffentlicht sie als Release.
2. **Claude:** In der [Anthropic Console](https://console.anthropic.com) einen API-Schlüssel anlegen und in der App unter Einstellungen → Verbindungen eintragen. Abrechnung nach Verbrauch.
3. **Strava:** Unter [strava.com/settings/api](https://www.strava.com/settings/api) eine Anwendung anlegen, als Autorisierungs-Callback-Domain `laufdoch.app` eintragen. Client-ID und Client-Secret in der App eintragen und „Mit Strava verbinden“ tippen.
4. **Garmin (optional):** intervals.icu-Konto mit Garmin Connect verbinden und dort das Hochladen geplanter Trainings aktivieren. Den API-Schlüssel (intervals.icu → Einstellungen → Entwickler) in der App unter Verbindungen → Garmin eintragen, Zielvorgabe wählen (HF, Pace oder RPE als Text; beim Rad Watt statt Pace).
5. **Daten übernehmen:** Eine Sicherung (`lauf-doch-sicherung-….json`) in „Downloads“ legen und in der App unter Einstellungen → Datensicherung laden.

## Dateien

- `app/src/main/assets/index.html` – Oberfläche und Planungslogik
- `app/src/main/assets/shim.js` – Brücke zwischen Oberfläche und App (Speicher, Strava, Claude)
- `app/src/main/java/de/laufdoch/MainActivity.java` – WebView, Strava-Anmeldung, API-Aufrufe, Sicherung

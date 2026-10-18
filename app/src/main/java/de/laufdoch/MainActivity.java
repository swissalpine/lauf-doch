package de.laufdoch;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * „Lauf doch!“: Die Oberfläche ist eine Web-App (assets/index.html).
 * Diese Activity stellt ihr über window.Android bereit:
 *  - Claude (Anthropic Messages API, eigener API-Schlüssel)
 *  - Strava (OAuth-Anmeldung und API-Abrufe)
 *  - Datensicherung (Export nach „Downloads“, Import per Dateiauswahl)
 * Schlüssel und Tokens liegen in den SharedPreferences, nicht im JavaScript.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "laufdoch";
    /** Callback-Domain der eigenen Strava-API-Anwendung; der Rücksprung lautet laufdoch://<domain>/strava. */
    private static final String STRAVA_DOMAIN = "swissalpine.github.io";
    private static final String DEFAULT_MODEL = "claude-sonnet-5-5";
    private static final int REQ_BACKUP = 42;
    private static final int REQ_NOTIF = 43;
    private volatile String openTab = null;

    private WebView web;
    private SharedPreferences prefs;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#F3F5FA"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // localStorage = Datenspeicher der App
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                openExternal(u);
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.evaluateJavascript("window.dispatchEvent(new Event('appresume'))", null);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.appBack ? window.appBack() : false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) web.evaluateJavascript("window.dispatchEvent(new Event('appresume'))", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_BACKUP || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            String text = new String(readAll(in), StandardCharsets.UTF_8);
            web.evaluateJavascript("window.onBackupLoaded && window.onBackupLoaded(" + JSONObject.quote(text) + ")", null);
        } catch (Exception e) {
            toast("Datei konnte nicht gelesen werden.");
        }
    }

    // ---------------------------------------------------------------- Strava-Anmeldung

    /** Rücksprung aus der Strava-Anmeldung: Code gegen Tokens tauschen. */
    private void handleIntent(Intent intent) {
        if (intent != null && intent.getStringExtra("open_tab") != null) {
            openTab = intent.getStringExtra("open_tab");
            intent.removeExtra("open_tab");
            if (web != null) web.evaluateJavascript("window.dispatchEvent(new Event('appresume'))", null);
        }
        if (intent == null || intent.getData() == null) return;
        Uri u = intent.getData();
        if (!"laufdoch".equals(u.getScheme())) return;
        String code = u.getQueryParameter("code");
        String error = u.getQueryParameter("error");
        String scope = u.getQueryParameter("scope");
        if (error != null || code == null) {
            toast("Strava-Verbindung abgebrochen.");
            notifyStrava();
            return;
        }
        pool.execute(() -> {
            try {
                String body = "client_id=" + enc(prefs.getString("strava_client_id", ""))
                        + "&client_secret=" + enc(prefs.getString("strava_client_secret", ""))
                        + "&code=" + enc(code) + "&grant_type=authorization_code";
                Resp r = http("POST", "https://www.strava.com/oauth/token",
                        "application/x-www-form-urlencoded", body, null);
                if (r.status != 200) throw new Exception("HTTP " + r.status);
                JSONObject j = new JSONObject(r.body);
                String name = "";
                JSONObject ath = j.optJSONObject("athlete");
                if (ath != null) name = (ath.optString("firstname", "") + " " + ath.optString("lastname", "")).trim();
                prefs.edit()
                        .putString("strava_access", j.getString("access_token"))
                        .putString("strava_refresh", j.getString("refresh_token"))
                        .putLong("strava_expires", j.getLong("expires_at"))
                        .putString("strava_athlete", name)
                        .putString("strava_scope", scope == null ? "" : scope)
                        .apply();
                toast("Mit Strava verbunden.");
            } catch (Exception e) {
                toast("Strava-Verbindung fehlgeschlagen. Client-ID und Secret prüfen.");
            }
            notifyStrava();
        });
    }

    private void notifyStrava() {
        runOnUiThread(() -> web.evaluateJavascript("window.onAppStatus && window.onAppStatus()", null));
    }

    /** Liefert ein gültiges Access-Token und erneuert es bei Bedarf. Null = nicht verbunden. */
    private synchronized String stravaToken(boolean forceRefresh) throws Exception {
        String access = prefs.getString("strava_access", null);
        String refresh = prefs.getString("strava_refresh", null);
        if (access == null || refresh == null) return null;
        long expires = prefs.getLong("strava_expires", 0);
        if (!forceRefresh && expires > System.currentTimeMillis() / 1000 + 120) return access;
        String body = "client_id=" + enc(prefs.getString("strava_client_id", ""))
                + "&client_secret=" + enc(prefs.getString("strava_client_secret", ""))
                + "&grant_type=refresh_token&refresh_token=" + enc(refresh);
        Resp r = http("POST", "https://www.strava.com/oauth/token", "application/x-www-form-urlencoded", body, null);
        if (r.status == 400 || r.status == 401) throw new BridgeError("needs_reauth", "Strava-Anmeldung abgelaufen.");
        if (r.status != 200) throw new BridgeError("server_unavailable", "Strava antwortet nicht (HTTP " + r.status + ").");
        JSONObject j = new JSONObject(r.body);
        prefs.edit()
                .putString("strava_access", j.getString("access_token"))
                .putString("strava_refresh", j.getString("refresh_token"))
                .putLong("strava_expires", j.getLong("expires_at"))
                .apply();
        return j.getString("access_token");
    }

    /** GET auf die Strava-API; payload = {path, query:{…}}. */
    private String strava(JSONObject p) throws Exception {
        String token = stravaToken(false);
        if (token == null) throw new BridgeError("server_not_connected", "Strava ist nicht verbunden.");
        StringBuilder url = new StringBuilder("https://www.strava.com/api/v3").append(p.getString("path"));
        JSONObject q = p.optJSONObject("query");
        if (q != null && q.length() > 0) {
            url.append('?');
            Iterator<String> keys = q.keys();
            boolean first = true;
            while (keys.hasNext()) {
                String k = keys.next();
                if (!first) url.append('&');
                url.append(enc(k)).append('=').append(enc(q.get(k).toString()));
                first = false;
            }
        }
        String method = p.optString("method", "GET");
        String body = p.has("body") ? p.get("body").toString() : null;
        String ct = body != null ? "application/json" : null;
        JSONObject form = p.optJSONObject("form");
        if (form != null) {
            StringBuilder fb = new StringBuilder();
            Iterator<String> fk = form.keys();
            while (fk.hasNext()) {
                String k = fk.next();
                if (fb.length() > 0) fb.append('&');
                fb.append(enc(k)).append('=').append(enc(form.get(k).toString()));
            }
            body = fb.toString();
            ct = "application/x-www-form-urlencoded";
        }
        Resp r = http(method, url.toString(), ct, body, "Bearer " + token);
        if (r.status == 401) {
            token = stravaToken(true);
            r = http(method, url.toString(), ct, body, "Bearer " + token);
            if (r.status == 401 && !"GET".equals(method)) throw new BridgeError("needs_scope", "Strava erlaubt der App das Anlegen von Aktivitäten noch nicht. Bitte Strava neu verbinden.");
            if (r.status == 401) throw new BridgeError("needs_reauth", "Strava-Anmeldung abgelaufen.");
        }
        if (r.status == 403 && !"GET".equals(method)) throw new BridgeError("needs_scope", "Strava erlaubt der App das Anlegen von Aktivitäten noch nicht. Bitte Strava neu verbinden.");
        if (r.status == 429) throw new BridgeError("server_unavailable", "Strava-Abruflimit erreicht. Später erneut versuchen.");
        if (r.status >= 500) throw new BridgeError("server_unavailable", "Strava antwortet nicht (HTTP " + r.status + ").");
        if (r.status >= 400) {
            String detail = "";
            try { detail = new JSONObject(r.body).optString("message", ""); } catch (Exception ignored) {}
            throw new BridgeError("tool_error", "Strava-Fehler HTTP " + r.status + (detail.isEmpty() ? "" : ": " + detail));
        }
        return r.body;
    }

    // ---------------------------------------------------------------- intervals.icu

    /** Aufruf der intervals.icu-API; payload = {method, path, body}. Liefert {"status":…, "body":…}. */
    private String intervals(JSONObject p) throws Exception {
        String key = prefs.getString("icu_key", "");
        if (key.isEmpty()) throw new BridgeError("not_configured", "Kein intervals.icu-API-Schlüssel hinterlegt.");
        String auth = "Basic " + android.util.Base64.encodeToString(("API_KEY:" + key).getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        String method = p.optString("method", "GET");
        String body = p.has("body") ? p.get("body").toString() : null;
        Resp r = http(method, "https://intervals.icu/api/v1" + p.getString("path"), body != null ? "application/json" : null, body, auth);
        if (r.status == 401 || r.status == 403) throw new BridgeError("bad_key", "intervals.icu-Schlüssel ungültig.");
        if (r.status >= 500) throw new BridgeError("server_unavailable", "intervals.icu antwortet nicht (HTTP " + r.status + ").");
        if (r.status >= 400) throw new BridgeError("tool_error", "intervals.icu-Fehler HTTP " + r.status + ": " + r.body);
        JSONObject out = new JSONObject();
        out.put("status", r.status);
        out.put("body", r.body);
        return out.toString();
    }

    // ---------------------------------------------------------------- Claude

    /** Anthropic Messages API; payload = {system, messages:[{role, content}]}. Liefert {"text": …}. */
    private String claude(JSONObject p) throws Exception {
        String key = prefs.getString("claude_key", "");
        if (key.isEmpty()) throw new BridgeError("not_configured", "Kein Claude-API-Schlüssel hinterlegt.");
        JSONObject req = new JSONObject();
        req.put("model", prefs.getString("claude_model", DEFAULT_MODEL));
        req.put("max_tokens", 20000);
        if (p.has("system")) req.put("system", p.getString("system"));
        req.put("messages", p.getJSONArray("messages"));

        HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(420000);
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        c.setRequestProperty("x-api-key", key);
        c.setRequestProperty("anthropic-version", "2023-06-01");
        Resp r;
        try {
            try (OutputStream os = c.getOutputStream()) {
                os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            }
            r = read(c);
        } catch (java.io.IOException e) {
            throw new BridgeError("upstream_error", "Keine Verbindung zu Claude.");
        } finally {
            c.disconnect();
        }
        if (r.status == 401 || r.status == 403) throw new BridgeError("bad_key", "API-Schlüssel ungültig.");
        if (r.status == 429) throw new BridgeError("rate_limited", "Abruflimit erreicht.");
        if (r.status == 400) {
            String msg = "Anfrage abgelehnt.";
            try { msg = new JSONObject(r.body).getJSONObject("error").getString("message"); } catch (Exception ignored) { }
            if (msg.toLowerCase().contains("credit")) throw new BridgeError("no_credit", msg);
            throw new BridgeError("invalid_request", msg);
        }
        if (r.status != 200) throw new BridgeError("upstream_error", "Claude antwortet nicht (HTTP " + r.status + ").");
        JSONObject res = new JSONObject(r.body);
        JSONArray content = res.getJSONArray("content");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.getJSONObject(i);
            if ("text".equals(b.optString("type"))) text.append(b.optString("text"));
        }
        JSONObject out = new JSONObject();
        out.put("text", text.toString());
        out.put("truncated", "max_tokens".equals(res.optString("stop_reason")));
        if (res.has("usage")) out.put("usage", res.getJSONObject("usage"));
        return out.toString();
    }

    // ---------------------------------------------------------------- App-Update über GitHub-Releases

    private static final String RELEASES = "https://api.github.com/repos/swissalpine/lauf-doch/releases/latest";

    /** Neueste Version: {version, name, date, url, notes}. */
    private String latestRelease() throws Exception {
        Resp r = http("GET", RELEASES, null, null, null);
        if (r.status == 404) throw new BridgeError("not_found", "Keine Releases gefunden (Repository privat?).");
        if (r.status >= 400) throw new BridgeError("tool_error", "GitHub antwortet mit HTTP " + r.status);
        JSONObject j = new JSONObject(r.body);
        String url = null;
        JSONArray assets = j.optJSONArray("assets");
        if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break; }
        }
        JSONObject out = new JSONObject();
        out.put("version", j.optString("tag_name").replaceFirst("^v", ""));
        out.put("name", j.optString("name"));
        out.put("date", j.optString("published_at"));
        out.put("notes", j.optString("body"));
        out.put("url", url == null ? JSONObject.NULL : url);
        out.put("current", BuildConfig.VERSION_NAME);
        return out.toString();
    }

    /** APK herunterladen und über den Paketinstaller installieren (Android fragt nach Bestätigung). */
    private String installUpdate(String url) throws Exception {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {}
            });
            throw new BridgeError("needs_permission", "Bitte „Installation aus dieser Quelle zulassen“ für Lauf doch! erlauben und dann erneut tippen.");
        }
        java.io.File f = new java.io.File(getCacheDir(), "update.apk");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code >= 400) throw new BridgeError("tool_error", "Download fehlgeschlagen (HTTP " + code + ").");
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream(); OutputStream out = new java.io.FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                long done = 0; int n, last = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n); done += n;
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    if (pct != last && pct >= 0 && pct % 5 == 0) {
                        last = pct;
                        final int pp = pct;
                        runOnUiThread(() -> web.evaluateJavascript("window.onUpdateProgress && window.onUpdateProgress(" + pp + ")", null));
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new BridgeError("server_unavailable", "Download abgebrochen. Internetverbindung prüfen.");
        } finally {
            if (c != null) c.disconnect();
        }
        android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
        android.content.pm.PackageInstaller.SessionParams params =
                new android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(getPackageName());
        int sid = pi.createSession(params);
        try (android.content.pm.PackageInstaller.Session session = pi.openSession(sid)) {
            try (InputStream in = new java.io.FileInputStream(f); OutputStream out = session.openWrite("laufdoch", 0, f.length())) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent cb = new Intent(this, UpdateReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent p = PendingIntent.getBroadcast(this, sid, cb, flags);
            session.commit(p.getIntentSender());
        }
        return "{\"ok\":true}";
    }

    // ---------------------------------------------------------------- Bridge

    /** Wird aus JavaScript als window.Android aufgerufen. */
    private class Bridge {

        /** Asynchroner Aufruf; das Ergebnis geht an window.__bridgeDone(id, ok, jsonText). */
        @JavascriptInterface
        public void request(String id, String kind, String payload) {
            pool.execute(() -> {
                boolean ok = true;
                String result;
                try {
                    JSONObject p = new JSONObject(payload);
                    if ("claude".equals(kind)) result = claude(p);
                    else if ("strava".equals(kind)) result = strava(p);
                    else if ("intervals".equals(kind)) result = intervals(p);
                    else if ("update".equals(kind)) result = latestRelease();
                    else if ("updateInstall".equals(kind)) result = installUpdate(p.getString("url"));
                    else throw new BridgeError("bad_request", "Unbekannter Aufruf.");
                } catch (BridgeError e) {
                    ok = false;
                    result = e.toJson();
                } catch (Exception e) {
                    ok = false;
                    result = new BridgeError("upstream_error", String.valueOf(e.getMessage())).toJson();
                }
                final String js = "window.__bridgeDone(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(result) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            });
        }

        /** Verbindungsstatus für die Einstellungen (ohne Schlüssel). */
        @JavascriptInterface
        public String getStatus() {
            try {
                JSONObject j = new JSONObject();
                j.put("claudeKey", !prefs.getString("claude_key", "").isEmpty());
                j.put("model", prefs.getString("claude_model", DEFAULT_MODEL));
                j.put("appVersion", BuildConfig.VERSION_NAME);
                j.put("buildDate", BuildConfig.BUILD_DATE);
                j.put("stravaClient", !prefs.getString("strava_client_id", "").isEmpty()
                        && !prefs.getString("strava_client_secret", "").isEmpty());
                j.put("stravaClientId", prefs.getString("strava_client_id", ""));
                j.put("stravaConnected", prefs.getString("strava_refresh", null) != null);
                j.put("stravaAthlete", prefs.getString("strava_athlete", ""));
                j.put("stravaWrite", prefs.getString("strava_scope", "").contains("activity:write"));
                j.put("icuKey", !prefs.getString("icu_key", "").isEmpty());
                return j.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void setClaude(String key, String model) {
            SharedPreferences.Editor e = prefs.edit();
            if (key != null && !key.trim().isEmpty()) e.putString("claude_key", key.trim());
            if (model != null && !model.trim().isEmpty()) e.putString("claude_model", model.trim());
            e.apply();
            toast("Claude-Einstellungen gespeichert.");
        }

        @JavascriptInterface
        public void setIntervals(String key) {
            if (key == null || key.trim().isEmpty()) prefs.edit().remove("icu_key").apply();
            else prefs.edit().putString("icu_key", key.trim()).apply();
        }

        private final String[] SECRET_KEYS = {"claude_key", "claude_model", "strava_client_id", "strava_client_secret", "icu_key"};

        /** Zugangsdaten für eine Sicherung (nur auf ausdrücklichen Wunsch). Strava-Anmeldung bleibt außen vor. */
        @JavascriptInterface
        public String getSecrets() {
            try {
                JSONObject j = new JSONObject();
                for (String k : SECRET_KEYS) { String v = prefs.getString(k, ""); if (!v.isEmpty()) j.put(k, v); }
                return j.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void setSecrets(String json) {
            try {
                JSONObject j = new JSONObject(json);
                SharedPreferences.Editor e = prefs.edit();
                for (String k : SECRET_KEYS) { String v = j.optString(k, "").trim(); if (!v.isEmpty()) e.putString(k, v); }
                e.apply();
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void clearClaude() {
            prefs.edit().remove("claude_key").apply();
        }

        @JavascriptInterface
        public void setStravaClient(String id, String secret) {
            SharedPreferences.Editor e = prefs.edit();
            if (id != null && !id.trim().isEmpty()) e.putString("strava_client_id", id.trim());
            if (secret != null && !secret.trim().isEmpty()) e.putString("strava_client_secret", secret.trim());
            e.apply();
        }

        /** Öffnet die Strava-Anmeldung (Strava-App, sonst Browser). */
        @JavascriptInterface
        public void stravaConnect() {
            String id = prefs.getString("strava_client_id", "");
            if (id.isEmpty()) { toast("Zuerst Client-ID und Secret eintragen."); return; }
            Uri u = Uri.parse("https://www.strava.com/oauth/mobile/authorize").buildUpon()
                    .appendQueryParameter("client_id", id)
                    .appendQueryParameter("redirect_uri", "laufdoch://" + STRAVA_DOMAIN + "/strava")
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("approval_prompt", "auto")
                    .appendQueryParameter("scope", "read,activity:read_all,activity:write,profile:read_all")
                    .build();
            runOnUiThread(() -> openExternal(u));
        }

        @JavascriptInterface
        public void stravaDisconnect() {
            prefs.edit().remove("strava_access").remove("strava_refresh")
                    .remove("strava_expires").remove("strava_athlete").apply();
        }

        /** Speichert eine Datensicherung (JSON) in „Downloads“. */
        @JavascriptInterface
        public boolean saveBackup(String filename, String json) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return false;
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                toast("Sicherung in „Downloads“ gespeichert: " + filename);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Öffnet die Dateiauswahl; der Inhalt geht an window.onBackupLoaded(text). */
        @JavascriptInterface
        public void pickBackup() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_BACKUP);
                } catch (Exception e) {
                    toast("Dateiauswahl nicht verfügbar.");
                }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> openExternal(Uri.parse(url)));
        }

        @JavascriptInterface
        public void toast(String msg) {
            MainActivity.this.toast(msg);
        }

        /** Erinnerungen: Einstellungen und Texte je Tag ablegen und Weckzeiten setzen. */
        @JavascriptInterface
        public void setReminders(String json) {
            prefs.edit().putString("remind_cfg", json).apply();
            ReminderReceiver.schedule(MainActivity.this);
        }

        /** "granted", "denied" oder "ask" (Android 13+: noch nicht gefragt bzw. erneut fragbar). */
        @JavascriptInterface
        public String notifPermission() {
            if (Build.VERSION.SDK_INT < 33) return "granted";
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return "granted";
            return prefs.getBoolean("notif_asked", false) && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ? "denied" : "ask";
        }

        @JavascriptInterface
        public void requestNotifPermission() {
            if (Build.VERSION.SDK_INT < 33) return;
            prefs.edit().putBoolean("notif_asked", true).apply();
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF));
        }

        /** Bildschirm während des Krafttrainings eingeschaltet lassen. */
        @JavascriptInterface
        public void keepAwake(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        /** Tab, den eine angetippte Benachrichtigung öffnen soll (einmalig). */
        @JavascriptInterface
        public String takeOpenTab() {
            String t = openTab;
            openTab = null;
            return t == null ? "" : t;
        }
    }

    // ---------------------------------------------------------------- Hilfen

    private static class Resp {
        int status;
        String body;
    }

    private static class BridgeError extends Exception {
        final String code;

        BridgeError(String code, String message) {
            super(message);
            this.code = code;
        }

        String toJson() {
            try {
                JSONObject j = new JSONObject();
                j.put("code", code);
                j.put("message", getMessage());
                return j.toString();
            } catch (Exception e) {
                return "{\"code\":\"upstream_error\"}";
            }
        }
    }

    private Resp http(String method, String url, String contentType, String body, String auth) throws BridgeError {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            if (auth != null) c.setRequestProperty("Authorization", auth);
            if (body != null) {
                c.setDoOutput(true);
                if (contentType != null) c.setRequestProperty("Content-Type", contentType);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            return read(c);
        } catch (java.io.IOException e) {
            throw new BridgeError("server_unavailable", "Keine Verbindung.");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Resp read(HttpURLConnection c) throws java.io.IOException {
        Resp r = new Resp();
        r.status = c.getResponseCode();
        InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
        return r;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) {
            toast("Link konnte nicht geöffnet werden.");
        }
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onDestroy() {
        pool.shutdown();
        super.onDestroy();
    }
}

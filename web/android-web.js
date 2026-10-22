/*
 * Lauf doch! – Web-Version
 * Ersetzt die Android-Brücke (window.Android) durch Browser-Aufrufe.
 * Claude, Strava und intervals.icu werden direkt per fetch angesprochen.
 * Schlüssel und Zugangsdaten liegen im localStorage dieses Browsers (Präfix "ldw:").
 */
(function(){
  'use strict';
  window.IS_WEB = true;
  const P = 'ldw:';
  const get = (k, d) => { try { const v = localStorage.getItem(P + k); return v == null ? d : v; } catch(e) { return d; } };
  const set = (k, v) => { try { v == null ? localStorage.removeItem(P + k) : localStorage.setItem(P + k, String(v)); } catch(e) {} };
  const DEFAULT_MODEL = 'claude-sonnet-5-5';
  const REDIRECT = location.origin + location.pathname;   // Rücksprung von Strava auf diese Seite

  class BridgeError { constructor(code, message){ this.code = code; this.message = message; } }

  function toast(msg){
    let el = document.getElementById('webToast');
    if(!el){ el = document.createElement('div'); el.id = 'webToast';
      el.style.cssText = 'position:fixed;left:50%;bottom:96px;transform:translateX(-50%);max-width:88%;background:#141A26;color:#fff;padding:10px 16px;border-radius:12px;font:15px/1.35 system-ui,sans-serif;z-index:9999;box-shadow:0 6px 24px rgba(0,0,0,.25);transition:opacity .3s';
      document.body.appendChild(el); }
    el.textContent = msg; el.style.opacity = '1';
    clearTimeout(toast.t); toast.t = setTimeout(() => { el.style.opacity = '0'; }, 3200);
  }

  async function http(method, url, opt){
    try { return await fetch(url, Object.assign({method}, opt||{})); }
    catch(e){ throw new BridgeError('server_unavailable', 'Keine Verbindung.'); }
  }
  const form = o => Object.keys(o).map(k => encodeURIComponent(k) + '=' + encodeURIComponent(o[k])).join('&');

  // ---------- Strava ----------
  async function stravaTokenExchange(params){
    const r = await http('POST', 'https://www.strava.com/oauth/token', {headers:{'Content-Type':'application/x-www-form-urlencoded'},
      body: form(Object.assign({client_id: get('strava_client_id',''), client_secret: get('strava_client_secret','')}, params))});
    if(r.status === 400 || r.status === 401) throw new BridgeError('needs_reauth', 'Strava-Anmeldung abgelaufen.');
    if(!r.ok) throw new BridgeError('server_unavailable', 'Strava antwortet nicht (HTTP ' + r.status + ').');
    const j = await r.json();
    set('strava_access', j.access_token); set('strava_refresh', j.refresh_token); set('strava_expires', j.expires_at);
    if(j.athlete) set('strava_athlete', ((j.athlete.firstname||'') + ' ' + (j.athlete.lastname||'')).trim());
    return j.access_token;
  }
  async function stravaToken(force){
    const access = get('strava_access', null), refresh = get('strava_refresh', null);
    if(!access || !refresh) return null;
    if(!force && Number(get('strava_expires', 0)) > Date.now()/1000 + 120) return access;
    return stravaTokenExchange({grant_type:'refresh_token', refresh_token: refresh});
  }
  async function strava(p){
    let token = await stravaToken(false);
    if(!token) throw new BridgeError('server_not_connected', 'Strava ist nicht verbunden.');
    let url = 'https://www.strava.com/api/v3' + p.path;
    if(p.query && Object.keys(p.query).length) url += '?' + form(p.query);
    const method = p.method || 'GET';
    const headers = {};
    let body;
    if(p.form){ body = form(p.form); headers['Content-Type'] = 'application/x-www-form-urlencoded'; }
    else if(p.body !== undefined){ body = JSON.stringify(p.body); headers['Content-Type'] = 'application/json'; }
    const go = t => http(method, url, {headers: Object.assign({Authorization: 'Bearer ' + t}, headers), body});
    let r = await go(token);
    if(r.status === 401){
      token = await stravaToken(true); r = await go(token);
      if(r.status === 401 && method !== 'GET') throw new BridgeError('needs_scope', 'Strava erlaubt der App das Anlegen von Aktivitäten noch nicht. Bitte Strava neu verbinden.');
      if(r.status === 401) throw new BridgeError('needs_reauth', 'Strava-Anmeldung abgelaufen.');
    }
    if(r.status === 403 && method !== 'GET') throw new BridgeError('needs_scope', 'Strava erlaubt der App das Anlegen von Aktivitäten noch nicht. Bitte Strava neu verbinden.');
    if(r.status === 429) throw new BridgeError('server_unavailable', 'Strava-Abruflimit erreicht. Später erneut versuchen.');
    if(r.status >= 500) throw new BridgeError('server_unavailable', 'Strava antwortet nicht (HTTP ' + r.status + ').');
    const text = await r.text();
    if(r.status >= 400){ let d = ''; try { d = JSON.parse(text).message || ''; } catch(e) {}
      throw new BridgeError('tool_error', 'Strava-Fehler HTTP ' + r.status + (d ? ': ' + d : '')); }
    return text;
  }

  // ---------- intervals.icu ----------
  async function intervals(p){
    const key = get('icu_key', '');
    if(!key) throw new BridgeError('not_configured', 'Kein intervals.icu-API-Schlüssel hinterlegt.');
    const body = p.body !== undefined ? JSON.stringify(p.body) : undefined;
    const r = await http(p.method || 'GET', 'https://intervals.icu/api/v1' + p.path,
      {headers: Object.assign({Authorization: 'Basic ' + btoa('API_KEY:' + key)}, body ? {'Content-Type':'application/json'} : {}), body});
    const text = await r.text();
    if(r.status === 401 || r.status === 403) throw new BridgeError('bad_key', 'intervals.icu-Schlüssel ungültig.');
    if(r.status >= 500) throw new BridgeError('server_unavailable', 'intervals.icu antwortet nicht (HTTP ' + r.status + ').');
    if(r.status >= 400) throw new BridgeError('tool_error', 'intervals.icu-Fehler HTTP ' + r.status + ': ' + text);
    return JSON.stringify({status: r.status, body: text});
  }

  // ---------- Claude ----------
  async function claude(p){
    const key = get('claude_key', '');
    if(!key) throw new BridgeError('not_configured', 'Kein Claude-API-Schlüssel hinterlegt.');
    const req = {model: get('claude_model', DEFAULT_MODEL), max_tokens: 20000, messages: p.messages};
    if(p.system) req.system = p.system;
    req.stream = true;
    // Streaming hält die Verbindung aktiv (lange Planantworten); ein zweiter Versuch bei Netz- oder Serverfehlern
    let last;
    for(let attempt = 0; attempt < 3; attempt++){
      if(attempt) await new Promise(r => setTimeout(r, attempt === 1 ? 4000 : 12000));
      try { return await claudeOnce(req, key); }
      catch(e){ last = e; if(!['network','overloaded','server_error'].includes(e.code)) throw e; }
    }
    throw last;
  }

  async function claudeOnce(req, key){
    let r;
    try {
      r = await fetch('https://api.anthropic.com/v1/messages', {method:'POST', headers:{'content-type':'application/json', 'accept':'text/event-stream', 'x-api-key': key,
        'anthropic-version':'2023-06-01', 'anthropic-dangerous-direct-browser-access':'true'}, body: JSON.stringify(req)});
    } catch(e){ throw new BridgeError('network', 'Netzwerkverbindung abgebrochen.'); }
    if(r.status !== 200){
      const text = await r.text().catch(() => '');
      if(r.status === 401 || r.status === 403) throw new BridgeError('bad_key', 'API-Schlüssel ungültig.');
      if(r.status === 429) throw new BridgeError('rate_limited', 'Abruflimit erreicht.');
      if(r.status === 400){ let msg = 'Anfrage abgelehnt.'; try { msg = JSON.parse(text).error.message; } catch(e) {}
        throw new BridgeError(/credit/i.test(msg) ? 'no_credit' : 'invalid_request', msg); }
      if(r.status === 529) throw new BridgeError('overloaded', 'Claude ist gerade überlastet (HTTP 529).');
      throw new BridgeError('server_error', 'Claude antwortet nicht (HTTP ' + r.status + ').');
    }
    let out = '', usage = {}, stop = '', done = false, buf = '';
    const dec = new TextDecoder();
    const handle = line => {
      if(!line.startsWith('data:')) return;
      let ev; try { ev = JSON.parse(line.slice(5).trim()); } catch(e) { return; }
      if(ev.type === 'content_block_delta' && ev.delta && ev.delta.type === 'text_delta') out += ev.delta.text;
      else if(ev.type === 'message_start' && ev.message && ev.message.usage) usage = Object.assign({}, ev.message.usage);
      else if(ev.type === 'message_delta'){ if(ev.delta && ev.delta.stop_reason) stop = ev.delta.stop_reason; if(ev.usage) Object.assign(usage, ev.usage); }
      else if(ev.type === 'message_stop') done = true;
      else if(ev.type === 'error'){ const er = ev.error || {};
        if(er.type === 'overloaded_error') throw new BridgeError('overloaded', 'Claude ist gerade überlastet.');
        throw new BridgeError('server_error', 'Claude hat die Antwort abgebrochen' + (er.message ? ': ' + er.message : '.')); }
    };
    try {
      const rd = r.body.getReader();
      for(;;){
        const {value, done: end} = await rd.read();
        if(end) break;
        buf += dec.decode(value, {stream:true});
        let k; while((k = buf.indexOf('\n')) >= 0){ handle(buf.slice(0, k).replace(/\r$/, '')); buf = buf.slice(k + 1); }
        if(done) break;
      }
      if(buf) handle(buf);
    } catch(e){ if(e instanceof BridgeError) throw e; throw new BridgeError('network', (e && e.name || 'Fehler') + (e && e.message ? ': ' + e.message : '') + ' (' + out.length + ' Zeichen empfangen).'); }
    if(!done) throw new BridgeError('network', 'Antwort unvollständig (' + out.length + ' Zeichen empfangen).');
    return JSON.stringify({text: out, truncated: stop === 'max_tokens', usage});
  }

  // ---------- Strava-Anmeldung: Rücksprung mit ?code=… ----------
  (async function handleRedirect(){
    const q = new URLSearchParams(location.search);
    if(!q.has('code') && !q.has('error')) return;
    history.replaceState(null, '', REDIRECT);
    if(q.get('error') || !q.get('code')){ setTimeout(() => toast('Strava-Verbindung abgebrochen.'), 500); return; }
    try {
      await stravaTokenExchange({code: q.get('code'), grant_type: 'authorization_code'});
      set('strava_scope', q.get('scope') || '');
      setTimeout(() => { toast('Mit Strava verbunden.'); window.onAppStatus && window.onAppStatus(); }, 600);
    } catch(e){ setTimeout(() => toast('Strava-Verbindung fehlgeschlagen. Client-ID und Secret prüfen.'), 600); }
  })();

  let wakeLock = null;

  window.Android = {
    request(id, kind, payload){
      (async () => {
        const p = JSON.parse(payload || '{}');
        if(kind === 'claude') return claude(p);
        if(kind === 'strava') return strava(p);
        if(kind === 'intervals') return intervals(p);
        throw new BridgeError('bad_request', 'In der Web-Version nicht verfügbar.');
      })().then(res => window.__bridgeDone(id, true, res),
        e => window.__bridgeDone(id, false, JSON.stringify(e instanceof BridgeError ? {code: e.code, message: e.message} : {code:'upstream_error', message: String(e && e.message || e)})));
    },
    getStatus(){
      return JSON.stringify({
        web: true,
        claudeKey: !!get('claude_key', ''), model: get('claude_model', DEFAULT_MODEL),
        appVersion: window.WEB_VERSION ? 'Web ' + window.WEB_VERSION : 'Web', buildDate: '',
        stravaClient: !!get('strava_client_id', '') && !!get('strava_client_secret', ''), stravaClientId: get('strava_client_id', ''),
        stravaConnected: !!get('strava_refresh', null), stravaAthlete: get('strava_athlete', ''),
        stravaWrite: /activity:write/.test(get('strava_scope', '')), icuKey: !!get('icu_key', '')
      });
    },
    setClaude(key, model){ if(key && key.trim()) set('claude_key', key.trim()); if(model && model.trim()) set('claude_model', model.trim()); toast('Claude-Einstellungen gespeichert.'); },
    clearClaude(){ set('claude_key', null); },
    getSecrets(){ const o={}; ['claude_key','claude_model','strava_client_id','strava_client_secret','icu_key'].forEach(k=>{ const v=get(k); if(v) o[k]=v; }); return JSON.stringify(o); },
    setSecrets(json){ let o={}; try{ o=JSON.parse(json)||{}; }catch(e){} ['claude_key','claude_model','strava_client_id','strava_client_secret','icu_key'].forEach(k=>{ const v=typeof o[k]==='string'?o[k].trim():''; if(v) set(k,v); }); },
    setIntervals(key){ set('icu_key', key && key.trim() ? key.trim() : null); },
    setStravaClient(id, secret){ if(id && id.trim()) set('strava_client_id', id.trim()); if(secret && secret.trim()) set('strava_client_secret', secret.trim()); },
    stravaConnect(){
      const id = get('strava_client_id', '');
      if(!id){ toast('Zuerst Client-ID und Secret eintragen.'); return; }
      location.href = 'https://www.strava.com/oauth/authorize?' + form({client_id: id, redirect_uri: REDIRECT, response_type: 'code',
        approval_prompt: 'auto', scope: 'read,activity:read_all,activity:write,profile:read_all'});
    },
    stravaDisconnect(){ ['strava_access','strava_refresh','strava_expires','strava_athlete','strava_scope'].forEach(k => set(k, null)); },
    saveBackup(filename, json){
      try {
        const url = URL.createObjectURL(new Blob([json], {type:'application/json'}));
        const a = document.createElement('a'); a.href = url; a.download = filename; document.body.appendChild(a); a.click(); a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 5000);
        toast('Sicherung heruntergeladen: ' + filename);
        return true;
      } catch(e){ return false; }
    },
    pickBackup(){
      const i = document.createElement('input'); i.type = 'file'; i.accept = 'application/json,.json';
      i.onchange = () => { const f = i.files && i.files[0]; if(!f) return; f.text().then(t => window.onBackupLoaded && window.onBackupLoaded(t)); };
      i.click();
    },
    openUrl(url){ window.open(url, '_blank', 'noopener'); },
    toast,
    setReminders(){}, notifPermission(){ return 'denied'; }, requestNotifPermission(){}, takeOpenTab(){ return ''; },
    keepAwake(on){
      try {
        if(on && navigator.wakeLock && !wakeLock) navigator.wakeLock.request('screen').then(l => { wakeLock = l; l.addEventListener('release', () => { wakeLock = null; }); }).catch(() => {});
        if(!on && wakeLock){ wakeLock.release(); wakeLock = null; }
      } catch(e) {}
    }
  };
  // Bildschirmsperre nach Rückkehr in die App erneut anfordern (Safari gibt sie beim Wechsel frei)
  document.addEventListener('visibilitychange', () => {
    if(!document.hidden){ window.dispatchEvent(new Event('appresume')); if(document.body.classList.contains('wkon')) window.Android.keepAwake(true); }
  });
})();

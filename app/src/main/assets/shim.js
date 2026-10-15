/*
 * Ersatz für die claude.ai-Laufzeit (window.claude.use) in der Android-App.
 * Die Web-App bleibt unverändert; nur diese Schicht ist app-spezifisch:
 *  - db:     Dokumente im localStorage der WebView
 *  - user:   fester lokaler Nutzer
 *  - mcp:    Strava-Werkzeuge (list_activities, get_activity_performance, get_athlete_zones)
 *            über die native Bridge, Antworten in das Format des Strava-Konnektors übersetzt
 *  - sample: Claude über die native Bridge (Anthropic Messages API)
 */
(function(){
  if (!window.Android || window.claude) return;

  // ---------- Bridge: asynchrone Aufrufe an MainActivity ----------
  const pending = {}; let seq = 0;
  window.__bridgeDone = function(id, ok, text){
    const p = pending[id]; if (!p) return; delete pending[id];
    let data; try { data = JSON.parse(text); } catch(e) { data = {code:'upstream_error', message:String(text)}; }
    ok ? p.res(data) : p.rej(data);
  };
  function call(kind, payload){
    return new Promise(function(res, rej){
      const id = 'r' + (++seq);
      pending[id] = {res: res, rej: rej};
      window.Android.request(id, kind, JSON.stringify(payload || {}));
    });
  }

  // ---------- db: localStorage ----------
  const PREFIX = 'ld:';
  const listeners = {};
  function snap(path){
    let v = null;
    try { v = JSON.parse(localStorage.getItem(PREFIX + path)); } catch(e) {}
    return {id: path.split('/').pop(), exists: v != null, data: function(){ return v == null ? undefined : v; }, metadata: {fromCache:false, hasPendingWrites:false}};
  }
  function emit(path){ (listeners[path] || []).forEach(function(f){ try { f(snap(path)); } catch(e) {} }); }
  const db = {
    doc: function(path){
      return {
        id: path.split('/').pop(), path: path,
        get: function(){ return Promise.resolve(snap(path)); },
        set: function(data){
          try { localStorage.setItem(PREFIX + path, JSON.stringify(data)); }
          catch(e) { return Promise.reject({code:'quota_exceeded', message:'Speicher voll'}); }
          setTimeout(function(){ emit(path); }, 0);
          return Promise.resolve();
        },
        delete: function(){ localStorage.removeItem(PREFIX + path); setTimeout(function(){ emit(path); }, 0); return Promise.resolve(); },
        onSnapshot: function(next){
          (listeners[path] = listeners[path] || []).push(next);
          setTimeout(function(){ next(snap(path)); }, 0);
          return function(){ listeners[path] = (listeners[path] || []).filter(function(f){ return f !== next; }); };
        }
      };
    }
  };
  const USER = 'local';
  const user = { id: function(){ return Promise.resolve(USER); }, isOwner: function(){ return true; }, canEdit: function(){ return true; } };

  // Datensicherung: alle Dokumente als {profile, plan, log, chat}
  const DOCS = ['profile','plan','log','chat'];
  window.__ldExport = function(){
    const out = {app:'Lauf doch!', exportedAt:new Date().toISOString()};
    DOCS.forEach(function(n){ const s = snap('data/users/' + USER + '/' + n); out[n] = s.exists ? s.data() : null; });
    return out;
  };
  window.__ldImport = function(obj){
    DOCS.forEach(function(n){
      if (obj && obj[n] && typeof obj[n] === 'object') {
        const path = 'data/users/' + USER + '/' + n;
        localStorage.setItem(PREFIX + path, JSON.stringify(obj[n]));
        emit(path);
      }
    });
  };

  // ---------- mcp: Strava ----------
  function epoch(localIso){ const d = new Date(localIso); return Math.floor(d.getTime() / 1000); }
  function mapActivity(a){
    return {
      id: String(a.id), name: a.name, sport_type: a.sport_type || a.type,
      start_local: String(a.start_date_local || '').replace('Z',''),
      summary: {
        distance: a.distance || 0, moving_time: a.moving_time || 0, elapsed_time: a.elapsed_time || 0,
        elevation_gain: a.total_elevation_gain || 0, avg_speed: a.average_speed || 0,
        relative_effort: a.suffer_score != null ? Math.round(a.suffer_score) : null,
        avg_cadence: a.average_cadence || null
      }
    };
  }
  const strava = {
    list_activities: async function(input){
      const q = {per_page: Math.min(Number(input && input.first) || 30, 100)};
      if (input && input.range_start) q.after = epoch(input.range_start);
      const list = await call('strava', {path:'/athlete/activities', query:q});
      return {activities: (list || []).map(mapActivity), has_next_page: false};
    },
    get_athlete_zones: async function(){
      const z = await call('strava', {path:'/athlete/zones'});
      let ftp = null;
      try { ftp = (await call('strava', {path:'/athlete'})).ftp || null; } catch(e) {}
      const hr = (z && z.heart_rate && z.heart_rate.zones) || [];
      return {heart_rate_zones: hr.map(function(x){ return {min:x.min, max:x.max}; }), ftp: ftp};
    },
    get_activity_performance: async function(input){
      const a = await call('strava', {path:'/activities/' + encodeURIComponent(input.activity_id)});
      // Kilometer-Splits bevorzugen (Läufe), sonst Runden
      let laps = [];
      const isRun = /Run|Walk|Hike/.test(a.sport_type || a.type || '');
      // Rad: Runden der Uhr (mit Watt); nur wenn es keine gibt, die Kilometer-Splits
      const devLaps = Array.isArray(a.laps) && a.laps.length > 1;
      if ((isRun || !devLaps) && Array.isArray(a.splits_metric) && a.splits_metric.length) {
        laps = a.splits_metric.map(function(s){ return {
          distance: s.distance, moving_time: s.moving_time, elapsed_time: s.elapsed_time || null, avg_hr: s.average_heartrate || null, avg_cadence: null,
          avg_grade: s.distance ? (s.elevation_difference || 0) / s.distance * 100 : null, avg_watts: null }; });
      } else if (Array.isArray(a.laps)) {
        laps = a.laps.map(function(l){ return {
          distance: l.distance, moving_time: l.moving_time, elapsed_time: l.elapsed_time || null, avg_hr: l.average_heartrate || null, avg_cadence: l.average_cadence || null,
          avg_grade: l.distance ? (l.total_elevation_gain || 0) / l.distance * 100 : null, avg_watts: l.average_watts || null }; });
      }
      return {
        has_heartrate: !!a.has_heartrate, average_heartrate: a.average_heartrate || null, max_heartrate: a.max_heartrate || null,
        average_watts: a.average_watts || null, has_device_watts: !!a.device_watts, laps: laps,
        perceived_exertion: a.perceived_exertion != null ? a.perceived_exertion : null,
        // Runden der Uhr (Intervalle), unabhängig von den Kilometer-Splits
        rounds: Array.isArray(a.laps) ? a.laps.map(function(l){ return {
          distance: l.distance, moving_time: l.moving_time, elapsed_time: l.elapsed_time || null, avg_hr: l.average_heartrate || null,
          avg_cadence: l.average_cadence || null, avg_grade: l.distance ? (l.total_elevation_gain || 0) / l.distance * 100 : null, avg_watts: l.average_watts || null }; }) : []
      };
    }
  };
  // Verlaufsdaten (Streams), Namen wie beim Strava-Konnektor
  strava.get_activity_streams = async function(input){
    const map = {time:'time', watts:'watts', heart_rate:'heartrate', cadence:'cadence', velocity_smooth:'velocity_smooth', altitude:'altitude', distance:'distance'};
    const want = (input.streams || ['time']).filter(function(k){ return map[k]; });
    const r = await call('strava', {path:'/activities/' + encodeURIComponent(input.activity_id) + '/streams', query:{keys: want.map(function(k){ return map[k]; }).join(','), key_by_type: true}});
    const out = {};
    want.forEach(function(k){ const v = r && r[map[k]]; if (v && Array.isArray(v.data)) out[k] = v.data; });
    return out;
  };
  const mcp = {
    callTool: async function(server, tool, input){
      if (server !== 'Strava' || !strava[tool]) throw {code:'not_in_manifest', message:'Unbekanntes Werkzeug'};
      return {payload: await strava[tool](input || {})};
    },
    listTools: async function(){ return {servers:[{server:'Strava', authStatus:'unknown', tools:[]}]}; }
  };

  // ---------- sample: Claude ----------
  function merge(turns){
    const out = [];
    (turns || []).forEach(function(t){
      const last = out[out.length - 1];
      if (last && last.role === t.role) last.content += '\n\n' + t.content;
      else out.push({role: t.role, content: String(t.content)});
    });
    return out;
  }
  function parseJson(text){
    try { return JSON.parse(text); } catch(e) {}
    const f = text.match(/```(?:json)?\s*([\s\S]*?)```/);
    if (f) { try { return JSON.parse(f[1]); } catch(e) {} }
    const a = text.search(/[\{\[]/), b = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));
    if (a >= 0 && b > a) { try { return JSON.parse(text.slice(a, b + 1)); } catch(e) {} }
    throw {code:'invalid_json', message:'Antwort war kein JSON', text:text};
  }
  async function ask(input, opts, system){
    const messages = typeof input === 'string' ? [{role:'user', content:input}] : merge(input);
    // Feste Regeln (bis zur Marke) als zwischengespeicherten Block senden (Prompt-Caching)
    const MARK = '\n<<<CACHE>>>';
    if (messages[0] && typeof messages[0].content === 'string' && messages[0].content.indexOf(MARK) > 0) {
      const c = messages[0].content, i = c.indexOf(MARK);
      messages[0].content = [
        {type:'text', text:c.slice(0, i), cache_control:{type:'ephemeral'}},
        {type:'text', text:c.slice(i + MARK.length).replace(/^\s+/, '')}
      ];
    }
    const res = await call('claude', {messages: messages, system: system});
    if (res && res.usage) console.log('Claude-Token', JSON.stringify(res.usage));
    if (opts && typeof opts.onText === 'function') { try { opts.onText({text: res.text, delta: res.text}); } catch(e) {} }
    return res;
  }
  const sample = async function(input, opts){
    const r = await ask(input, opts);
    return {text: r.text, truncated: !!r.truncated, modelTierApplied: 'default'};
  };
  sample.json = async function(input, opts){
    const r = await ask(input, opts, 'Deine Antwort wird maschinell ausgewertet. Antworte ausschließlich mit genau einem JSON-Wert, ohne Text davor oder danach.');
    if (r.truncated) throw {code:'invalid_json', message:'Antwort abgeschnitten', text:r.text};
    return parseJson(r.text);
  };
  sample.limits = async function(){ return {maxPromptBytes: 262144}; };

  const caps = {db: db, user: user, mcp: mcp, sample: sample};
  window.claude = { use: function(name){ return Promise.resolve(caps[name] || null); } };
  window.IN_APP = true;
  window.__bridgeCall = call;
})();

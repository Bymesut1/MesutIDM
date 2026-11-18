package com.bymesut1.idm

/** Her sayfaya ve iframe'e belge başlangıcında enjekte edilir (IDM'in "sniffer" mantığı). */
object Hook {
    val JS = """
(function(){
  if (window.__idmHooked) return;
  window.__idmHooked = true;
  var seen = {};
  var __q = [];
  function __call(n, a){
    if (n === 'found') IDM.found(a[0], a[1], a[2]);
    else if (n === 'sub') IDM.sub(a[0], a[1], a[2]);
    else if (n === 'subData') IDM.subData(a[0], a[1], a[2], a[3], a[4]);
  }
  // IDM köprüsü henüz hazır değilse kayıt kaybolmasın: kuyruğa al, hazır olunca gönder
  function send(n, a){
    try { if (typeof IDM !== 'undefined') { __call(n, a); return; } } catch (e) {}
    __q.push([n, a]);
    if (__q.length > 300) __q.shift();
  }
  setInterval(function(){
    if (!__q.length || typeof IDM === 'undefined') return;
    var q = __q; __q = [];
    for (var i = 0; i < q.length; i++) { try { __call(q[i][0], q[i][1]); } catch (e) {} }
  }, 600);
  var MEDIA = /\.(mp4|m4v|mkv|webm|mov|avi|wmv|flv|f4v|3gp|3g2|mpg|mpeg|mpv|ogv|vob|m2ts|mts|asf|rmvb|divx|m3u8|mpd|mp3|m4a|aac|ogg|oga|opus|wav|flac|wma|amr|mka|ac3|zip|rar|7z|tar|gz|tgz|bz2|xz|apk|pdf|iso|exe|msi|dmg|docx|xlsx|pptx|epub|srt|vtt|ass|ssa|ttml|dfxp|sbv)(\?|#|&|$)/i;

  function kindOf(u){
    var p = String(u).split('?')[0].toLowerCase();
    if (/\.(srt|vtt|ass|ssa|ttml|dfxp|sbv)$/.test(p)) return 'sub';
    if (/\.m3u8$/.test(p)) return 'hls';
    if (/\.mpd$/.test(p)) return 'dash';
    return 'video';
  }
  function rep(u, kind){
    try {
      u = String(u);
      if (/^(blob|data|javascript|about):/i.test(u)) return;
      try { u = new URL(u, location.href).href; } catch (e) { return; }
      if (!/^https?:/i.test(u) || seen[u]) return;
      seen[u] = 1;
      send('found', [u, location.href, kind || kindOf(u)]);
    } catch (e) {}
  }
  // Metin / JSON içinde gömülü medya adreslerini bul (API cevapları, player ayarları)
  function scanText(t){
    try {
      if (!t || t.indexOf('http') < 0) return;
      if (t.length > 4000000) t = t.substring(0, 4000000);
      t = t.replace(/\\\//g, '/').replace(/\\u0026/g, '&').replace(/&amp;/g, '&').replace(/&quot;/g, '"');
      scanTracks(t);
      scanKeys(t);
      var re = /https?:\/\/[^\s"'<>\\()]+/g, m, c = 0;
      while ((m = re.exec(t)) && c < 60) {
        if (MEDIA.test(m[0])) { rep(m[0], kindOf(m[0])); c++; }
      }
    } catch (e) {}
  }
  function chk(u, ct, cl){
    ct = (ct || '').toLowerCase();
    var len = parseInt(cl || '0', 10);
    if (ct.indexOf('vtt') >= 0 || ct.indexOf('subrip') >= 0 || ct.indexOf('ttml') >= 0) { rep(u, 'sub'); return; }
    if (ct.indexOf('mpegurl') >= 0) { rep(u, 'hls'); return; }
    if (ct.indexOf('dash+xml') >= 0) { rep(u, 'dash'); return; }
    if (ct.indexOf('video/mp2t') >= 0) return;
    if (/^(video|audio)\//.test(ct) && (!len || len > 800000)) rep(u, 'video');
  }

  // ---- Altyazı yakalama: içerik WEBVTT/SRT/ASS/TTML ise adres/uzantı ne olursa olsun al ----
  function isSubText(t){
    try {
      if (!t) return false;
      var s = String(t).substring(0, 3000).replace(/^\uFEFF/, '').trim();
      if (/^WEBVTT/.test(s)) return true;
      if (/^\d+\s*\r?\n\d{1,2}:\d\d:\d\d[,.]\d{1,3}\s*-->/.test(s)) return true;
      if (/^\[Script Info\]/i.test(s)) return true;
      if (/<tt[\s>]/.test(s) && /(ttml|xml:lang)/i.test(s)) return true;
    } catch (e) {}
    return false;
  }
  function repSub(u, label){
    try {
      u = String(u);
      try { u = new URL(u, location.href).href; } catch (e) { return; }
      if (!/^https?:/i.test(u) || seen[u]) return;
      seen[u] = 1;
      send('sub', [u, location.href, label || '']);
    } catch (e) {}
  }
  function repSubData(u, t, label){
    try {
      if (!t || t.length > 1500000) return;
      var a = String(u);
      if (!/^blob:/i.test(a)) { try { a = new URL(a, location.href).href; } catch (e) { return; } }
      var key = 'D' + a;
      if (seen[key]) return;
      seen[key] = 1;
      var h = String(t).substring(0, 600).replace(/^\uFEFF/, '').trim();
      var ext = /^WEBVTT/.test(h) ? 'vtt' : (/^\[Script Info\]/i.test(h) ? 'ass' : (/<tt[\s>]/.test(h) ? 'ttml' : 'srt'));
      send('subData', [a, location.href, label || '', ext, String(t)]);
    } catch (e) {}
  }
  // Player ayarlarındaki (JSON) altyazı listeleri: {"file":"..","label":"Türkçe","kind":"captions"}
  function scanTracks(t){
    try {
      var re = /\{[^{}]{0,700}\}/g, m, c = 0;
      while ((m = re.exec(t)) && c < 40) {
        var o = m[0];
        var strict = /"kind"\s*:\s*"(captions|subtitles)"|srclang/i.test(o);
        var um = /https?:\/\/[^"'\\\s]+/.exec(o);
        if (!um) continue;
        if (/\.(jpe?g|png|gif|webp|svg|js|css|mp4|m3u8|ts|ico)(\?|$)/i.test(um[0])) continue;
        var loose = /"label"/i.test(o) && /(sub|caption|altyaz|vtt|srt|[\/_.=-](tur|eng|tr|en)[\/_.&-])/i.test(um[0]);
        if (!strict && !loose) continue;
        var lm = /"(?:label|name|title)"\s*:\s*"([^"]*)"/i.exec(o);
        repSub(um[0], lm ? lm[1] : '');
        c++;
      }
    } catch (e) {}
  }

  // Uzantısız ama bilinen anahtarlı video adresleri (VK url720/hls, OK.ru videos[], contentUrl ...)
  function scanKeys(t){
    try {
      var s = t.replace(/\\"/g, '"');
      var re = /"(url(?:144|240|360|480|720|1080|1440|2160)|hls|hls_ondemand|hls_live|hlsManifestUrl|ondemandHls|hlsMasterPlaylistUrl|dash_sep|dash_webm|dash_uni|ondemandDash|metadataWebmUrl|postlive_mp4|video_url|videoUrl|stream_url|streamUrl|play_url|playUrl|download_url|downloadUrl|contentUrl)"\s*:\s*"(https?:\/\/[^"\s]+)"/g, m, c = 0;
      while ((m = re.exec(s)) && c < 40) {
        var u = m[2];
        if (/\.(jpe?g|png|gif|webp|svg|js|css|ico|woff2?|ttf|html?|json|vtt|srt)(\?|$)/i.test(u)) continue;
        var kind = (/hls|m3u8/i.test(m[1]) || /\.m3u8/i.test(u)) ? 'hls' : ((/dash|mpd|webm/i.test(m[1]) || /\.mpd/i.test(u)) ? 'dash' : 'video');
        rep(u, kind);
        c++;
      }
      var re2 = /"name"\s*:\s*"(mobile|lowest|low|sd|hd|full|quad|ultra)"\s*,\s*"url"\s*:\s*"(https?:\/\/[^"\s]+)"/g;
      while ((m = re2.exec(s)) && c < 60) { rep(m[2], 'video'); c++; }
    } catch (e) {}
  }

  var xo = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(m, u){
    var self = this, url = u;
    this.addEventListener('readystatechange', function(){
      if (self.readyState === 2) {
        try { chk(url, self.getResponseHeader('content-type'), self.getResponseHeader('content-length')); } catch (e) {}
      }
    });
    this.addEventListener('load', function(){
      try {
        if (self.responseType === '' || self.responseType === 'text') {
          var t = self.responseText;
          if (!t || t.length > 3000000) return;
          if (isSubText(t)) { repSubData(url, t, ''); return; }
          if (t.indexOf('#EXTM3U') === 0) rep(url, 'hls');
          else if (t.indexOf('<MPD') >= 0 && t.length < 2000000) rep(url, 'dash');
          else scanText(t);
        }
      } catch (e) {}
    });
    return xo.apply(this, arguments);
  };

  var of = window.fetch;
  if (of) {
    window.fetch = function(inp, init){
      var url = (typeof inp === 'string') ? inp : (inp && inp.url);
      var p = of.apply(this, arguments);
      try {
        p.then(function(r){
          try {
            var ct = (r.headers.get('content-type') || '').toLowerCase();
            var cl = parseInt(r.headers.get('content-length') || '0', 10);
            chk(url, ct, r.headers.get('content-length'));
            var textual = /(json|text|xml|mpegurl|dash)/.test(ct) && ct.indexOf('event-stream') < 0;
            var smallBin = ct.indexOf('octet-stream') >= 0 && cl > 0 && cl < 500000;
            if ((textual && (!cl || cl < 1500000)) || smallBin) {
              r.clone().text().then(function(t){
                if (!t) return;
                if (isSubText(t)) { repSubData(url, t, ''); return; }
                if (t.indexOf('#EXTM3U') === 0) rep(url, 'hls');
                else if (t.indexOf('<MPD') >= 0) rep(url, 'dash');
                else scanText(t);
              }).catch(function(){});
            }
          } catch (e) {}
        }).catch(function(){});
      } catch (e) {}
      return p;
    };
  }

  function scanMedia(){
    try {
      var els = document.querySelectorAll('video,audio,source');
      for (var i = 0; i < els.length; i++) {
        var s = els[i].currentSrc || els[i].src || els[i].getAttribute('src');
        if (s) rep(s, kindOf(s));
      }
      var trs = document.querySelectorAll('track[src]');
      for (var q = 0; q < trs.length; q++) {
        try {
          var tu = new URL(trs[q].getAttribute('src'), location.href).href;
          if (/^https?:/i.test(tu) && !seen[tu]) {
            seen[tu] = 1;
            send('sub', [tu, location.href, trs[q].label || trs[q].srclang || '']);
          }
        } catch (e) {}
      }
      var as = document.querySelectorAll('a[href]');
      for (var j = 0; j < as.length && j < 3000; j++) {
        var h = as[j].href;
        if (h && MEDIA.test(h)) rep(h, kindOf(h));
      }
      var ms = document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[name="twitter:player:stream"]');
      for (var k = 0; k < ms.length; k++) {
        var c = ms[k].getAttribute('content');
        if (c && MEDIA.test(c)) rep(c, kindOf(c));
      }
    } catch (e) {}
  }
  function scanHtml(){
    try { scanText(document.documentElement ? document.documentElement.innerHTML : ''); } catch (e) {}
  }
  window.__idmScan = function(){ scanMedia(); scanHtml(); };
  setInterval(scanMedia, 1500);
  document.addEventListener('DOMContentLoaded', function(){ setTimeout(window.__idmScan, 800); });
  window.addEventListener('load', function(){ setTimeout(window.__idmScan, 1200); setTimeout(window.__idmScan, 5000); });
  document.addEventListener('play', function(){ setTimeout(window.__idmScan, 500); }, true);

  // blob: ile üretilen altyazılar (player metni indirip blob yapar)
  try {
    var cou = URL.createObjectURL;
    URL.createObjectURL = function(o){
      var u = cou.apply(this, arguments);
      try {
        if (o && typeof Blob !== 'undefined' && o instanceof Blob && o.size > 20 && o.size < 1500000 && !/^(video|audio|image)\//.test(o.type || '')) {
          o.text().then(function(t){ if (isSubText(t)) repSubData(u, t, ''); }).catch(function(){});
        }
      } catch (e) {}
      return u;
    };
  } catch (e) {}

  // ---- Chrome kimliği: window.chrome ve userAgentData (Chrome markası) ----
  try {
    if (!window.chrome) {
      window.chrome = { app: { isInstalled: false }, loadTimes: function(){ return {}; }, csi: function(){ return {}; } };
    }
  } catch (e) {}
  try {
    var ud = navigator.userAgentData;
    var ok = ud && ud.brands && ud.brands.some(function(b){ return b.brand === 'Google Chrome'; });
    if (!ok) {
      var um2 = /Chrome\/(\d+)/.exec(navigator.userAgent);
      var major = um2 ? um2[1] : '130';
      var mob = /Mobile/.test(navigator.userAgent);
      var plat = mob ? 'Android' : 'Windows';
      var brands = [{brand:'Chromium',version:major},{brand:'Google Chrome',version:major},{brand:'Not_A Brand',version:'24'}];
      var shim = {
        brands: brands, mobile: mob, platform: plat,
        getHighEntropyValues: function(){
          return Promise.resolve({
            brands: brands, mobile: mob, platform: plat, platformVersion: '10.0.0',
            architecture: mob ? '' : 'x86', model: mob ? 'K' : '', uaFullVersion: major + '.0.0.0',
            fullVersionList: brands.map(function(b){ return {brand:b.brand, version:b.version + '.0.0.0'}; })
          });
        },
        toJSON: function(){ return {brands: brands, mobile: mob, platform: plat}; }
      };
      Object.defineProperty(Navigator.prototype, 'userAgentData', { get: function(){ return shim; }, configurable: true });
    }
  } catch (e) {}
})();
"""
}

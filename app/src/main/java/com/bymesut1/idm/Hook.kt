package com.bymesut1.idm

/** Her sayfaya ve iframe'e belge başlangıcında enjekte edilir (IDM'in "sniffer" mantığı). */
object Hook {
    val JS = """
(function(){
  if (window.__idmHooked) return;
  window.__idmHooked = true;
  var seen = {};
  var MEDIA = /\.(mp4|m3u8|mpd|mkv|webm|mp3|m4a|flv|avi|mov|aac|ogg|opus|wav|zip|rar|7z|apk|pdf|iso|srt|vtt|ass|ssa|ttml|dfxp|sbv)(\?|#|&|$)/i;

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
      if (typeof IDM !== 'undefined') IDM.found(u, location.href, kind || kindOf(u));
    } catch (e) {}
  }
  // Metin / JSON içinde gömülü medya adreslerini bul (API cevapları, player ayarları)
  function scanText(t){
    try {
      if (!t || t.indexOf('http') < 0) return;
      if (t.length > 4000000) t = t.substring(0, 4000000);
      t = t.replace(/\\\//g, '/').replace(/\\u0026/g, '&').replace(/&amp;/g, '&');
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
            if (typeof IDM !== 'undefined') IDM.sub(tu, location.href, trs[q].label || trs[q].srclang || '');
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

  // ---- Chrome kimliği: window.chrome ve userAgentData (Chrome markası) ----
  try {
    if (!window.chrome) {
      window.chrome = { app: { isInstalled: false }, loadTimes: function(){ return {}; }, csi: function(){ return {}; } };
    }
  } catch (e) {}
  try {
    var ud = navigator.userAgentData;
    var ok = ud && ud.brands && ud.brands.some(function(b){ return b.brand === 'Google Chrome'; });
    if (!ok && typeof IDM !== 'undefined' && IDM.chromeMajor) {
      var major = String(IDM.chromeMajor());
      var mob = !!IDM.isMobile();
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

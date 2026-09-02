package com.nhnengineering.rftest.live

/**
 * The live walk view, served to a connected laptop or tablet.
 *
 * A single self-contained page: no CDN, no fonts, no remote scripts. The laptop is cabled to a
 * phone in a basement and has no internet of its own, so anything not in this string does not
 * exist.
 *
 * Satellite tiles are the one exception, and they are not an exception to that rule so much as an
 * application of it: they are fetched from **the phone**, at `/tile/{z}/{x}/{y}`, which fetches
 * and caches them over the connection being surveyed. A tile URL pointing at a provider directly
 * would work on a desk and fail in the basement — the only place it matters.
 *
 * The colour thresholds below **duplicate** `RsrpBucket` and `RssiBucket`. That is a real cost and
 * it is taken deliberately — the alternative is generating JavaScript from Kotlin enums, which is
 * more machinery than a colour scale deserves. `LiveScaleTest` pins the two against each other so
 * the duplication fails a build rather than quietly showing the operator a different colour from
 * the one the report will print.
 */
internal object LivePage {

    val HTML: String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>NHN live walk view</title>
<style>
  :root {
    --bg: #14110f; --panel: #1f1a17; --line: #3a322d;
    --ink: #f2ece7; --dim: #a2968c; --accent: #ffb59a;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; background: var(--bg); color: var(--ink);
    font: 15px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  }
  header {
    display: flex; align-items: baseline; gap: 14px; flex-wrap: wrap;
    padding: 10px 16px; border-bottom: 1px solid var(--line);
  }
  h1 { font-size: 15px; margin: 0; letter-spacing: .02em; }
  #status { font-size: 13px; color: var(--dim); }
  #status.live { color: #7fd67f; }
  #status.stale { color: #ffb04a; }
  main { display: grid; grid-template-columns: 340px 1fr; gap: 14px; padding: 14px; }
  @media (max-width: 820px) { main { grid-template-columns: 1fr; } }
  .card {
    background: var(--panel); border: 1px solid var(--line);
    border-radius: 10px; padding: 12px 14px;
  }
  .card h2 {
    font-size: 11px; text-transform: uppercase; letter-spacing: .09em;
    color: var(--dim); margin: 0 0 8px;
  }
  .big { font-size: 40px; font-weight: 650; line-height: 1.05; font-variant-numeric: tabular-nums; }
  .unit { font-size: 14px; color: var(--dim); margin-left: 6px; }
  table { width: 100%; border-collapse: collapse; font-variant-numeric: tabular-nums; }
  td { padding: 3px 0; }
  td:last-child { text-align: right; font-weight: 600; }
  td.k { color: var(--dim); font-weight: 400; }
  .stack { display: flex; flex-direction: column; gap: 12px; }
  canvas { width: 100%; height: auto; display: block; border-radius: 6px; background: #100d0c; }
  .legend { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 10px; font-size: 12px; color: var(--dim); }
  .legend i { display: inline-block; width: 11px; height: 11px; border-radius: 2px; margin-right: 5px; }
  .tp { display: flex; gap: 22px; }
  .tp div { flex: 1; }
  .busy { color: var(--accent); font-size: 12px; }
  .note { font-size: 12px; color: var(--dim); margin-top: 8px; }
</style>
</head>
<body>
<header>
  <h1>NHN Engineering &mdash; live walk view</h1>
  <span id="status">connecting&hellip;</span>
  <span id="area" style="font-size:13px;color:var(--dim)"></span>
  <span id="floor" style="font-size:13px;color:var(--dim)"></span>
  <label style="margin-left:auto;font-size:13px;color:var(--dim);cursor:pointer">
    <input type="checkbox" id="imagery" checked> satellite
  </label>
</header>

<main>
  <div class="stack">
    <div class="card">
      <h2>Serving cell</h2>
      <div><span class="big" id="rsrp">&mdash;</span><span class="unit" id="rsrpUnit">dBm</span></div>
      <table>
        <tr><td class="k">Technology</td><td id="rat">&mdash;</td></tr>
        <tr><td class="k">Operator</td><td id="op">&mdash;</td></tr>
        <tr><td class="k">Band</td><td id="band">&mdash;</td></tr>
        <tr><td class="k">PCI / channel</td><td id="pci">&mdash;</td></tr>
        <tr><td class="k">SINR</td><td id="sinr">&mdash;</td></tr>
        <tr><td class="k">RSRQ</td><td id="rsrq">&mdash;</td></tr>
        <tr><td class="k">Neighbours</td><td id="nbr">&mdash;</td></tr>
      </table>
      <div class="note" id="cadenceNote"></div>
    </div>

    <div class="card">
      <h2>Throughput</h2>
      <div class="tp">
        <div>
          <div class="big" id="dl">&mdash;</div>
          <div class="unit">Mbps down</div>
        </div>
        <div>
          <div class="big" id="ul">&mdash;</div>
          <div class="unit">Mbps up</div>
        </div>
      </div>
      <div class="busy" id="tpBusy"></div>
      <table style="margin-top:8px">
        <tr><td class="k">Endpoint</td><td id="tpServer">&mdash;</td></tr>
      </table>
      <div class="note" id="tpNote"></div>
    </div>

    <div class="card">
      <h2>Session</h2>
      <table>
        <tr><td class="k">Samples</td><td id="rows">&mdash;</td></tr>
        <tr><td class="k">Elapsed</td><td id="elapsed">&mdash;</td></tr>
        <tr><td class="k">Distance</td><td id="dist">&mdash;</td></tr>
        <tr><td class="k">Fix accuracy</td><td id="acc">&mdash;</td></tr>
        <tr><td class="k">Speed</td><td id="spd">&mdash;</td></tr>
      </table>
    </div>
  </div>

  <div class="card">
    <h2>Route walked &mdash; <span id="pts">0</span> points</h2>
    <canvas id="map" width="1000" height="720"></canvas>
    <div class="legend" id="legend"></div>
    <div class="note">
      Ring marks your current position. North is up and both axes share one scale, so the
      trail is a map rather than a stretched scatter. Only GPS-located samples appear here.
    </div>
    <div class="note" id="attribution"></div>
  </div>
</main>

<script>
// Duplicated from RsrpBucket / RssiBucket in the app. LiveScaleTest pins these against the Kotlin
// so the operator never sees a different colour from the one the report prints.
var RSRP_SCALE = [
  { min: -85,  color: '#2E7D32', label: '≥ −85 dBm' },
  { min: -95,  color: '#689F38', label: '−86 to −95' },
  { min: -105, color: '#F9A825', label: '−96 to −105' },
  { min: -115, color: '#EF6C00', label: '−106 to −115' },
  { min: -999, color: '#C62828', label: '< −115 dBm' }
];
var RSSI_SCALE = [
  { min: -55, color: '#2E7D32', label: '≥ −55 dBm' },
  { min: -65, color: '#689F38', label: '−56 to −65' },
  { min: -72, color: '#F9A825', label: '−66 to −72' },
  { min: -80, color: '#EF6C00', label: '−73 to −80' },
  { min: -999, color: '#C62828', label: '< −80 dBm' }
];

function bucket(scale, v) {
  if (v === null || v === undefined) return null;
  for (var i = 0; i < scale.length; i++) if (v >= scale[i].min) return scale[i];
  return scale[scale.length - 1];
}
function colorFor(p) {
  var b = (p.rsrp !== null && p.rsrp !== undefined)
    ? bucket(RSRP_SCALE, p.rsrp) : bucket(RSSI_SCALE, p.rssi);
  return b ? b.color : '#7a7a7a';
}
function txt(id, v) { document.getElementById(id).textContent = v; }
function dash(v, suffix) {
  return (v === null || v === undefined) ? '—' : v + (suffix || '');
}
function hhmmss(ms) {
  var s = Math.floor(ms / 1000);
  var m = Math.floor(s / 60);
  return m + 'm ' + String(s % 60).padStart(2, '0') + 's';
}

var lastScale = null;
function drawLegend(scale) {
  if (scale === lastScale) return;
  lastScale = scale;
  document.getElementById('legend').innerHTML = scale.map(function (b) {
    return '<span><i style="background:' + b.color + '"></i>' + b.label + '</span>';
  }).join('');
}

// ---- Web Mercator -------------------------------------------------------
// The tiles are Web Mercator, so the trail has to be drawn in Web Mercator too. The earlier
// equirectangular projection was fine on its own but would slide progressively out of register
// against imagery -- and a trail that is *nearly* on the right building is worse than no imagery,
// because it looks authoritative.
function lonToWorld(lon, z) { return (lon + 180) / 360 * Math.pow(2, z); }
function latToWorld(lat, z) {
  var r = lat * Math.PI / 180;
  return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * Math.pow(2, z);
}

var tileCache = {};
function tileImage(z, x, y, onload) {
  var key = z + '/' + x + '/' + y;
  if (tileCache[key]) return tileCache[key];
  var img = new Image();
  img.onload = onload;
  // A tile the phone could not fetch stays blank rather than retrying forever over the same
  // radio the survey is measuring.
  img.onerror = function () { img.failed = true; };
  img.src = '/tile/' + key;
  tileCache[key] = img;
  return img;
}

function drawTiles(g, cv, minLat, maxLat, minLon, maxLon, project) {
  // Pick the zoom whose tiles are closest to 1:1 on this canvas, capped at Esri's deepest level.
  var z = 19;
  for (var t = 19; t >= 1; t--) {
    var w = Math.abs(lonToWorld(maxLon, t) - lonToWorld(minLon, t)) * 256;
    var h = Math.abs(latToWorld(minLat, t) - latToWorld(maxLat, t)) * 256;
    if (w <= cv.width * 1.6 && h <= cv.height * 1.6) { z = t; break; }
  }
  var x0 = Math.floor(lonToWorld(minLon, z)), x1 = Math.floor(lonToWorld(maxLon, z));
  var y0 = Math.floor(latToWorld(maxLat, z)), y1 = Math.floor(latToWorld(minLat, z));
  // A hard ceiling on tiles per frame. Without it, a wide-area session would ask the phone for
  // hundreds of tiles over the cellular link it is trying to measure.
  if ((x1 - x0 + 1) * (y1 - y0 + 1) > 64) return;

  for (var x = x0; x <= x1; x++) {
    for (var y = y0; y <= y1; y++) {
      var img = tileImage(z, x, y, function () { needsRedraw = true; });
      if (!img.complete || img.failed || !img.naturalWidth) continue;
      var nw = project(y / Math.pow(2, z), x / Math.pow(2, z), z);
      var se = project((y + 1) / Math.pow(2, z), (x + 1) / Math.pow(2, z), z);
      g.drawImage(img, nw[0], nw[1], se[0] - nw[0], se[1] - nw[1]);
    }
  }
}

var needsRedraw = false;
var lastTrack = [];

function draw(track) {
  var cv = document.getElementById('map'), g = cv.getContext('2d');
  g.clearRect(0, 0, cv.width, cv.height);
  if (!track.length) return;

  lastTrack = track;
  var lats = track.map(function (p) { return p.lat; });
  var lons = track.map(function (p) { return p.lon; });
  var minLat = Math.min.apply(null, lats), maxLat = Math.max.apply(null, lats);
  var minLon = Math.min.apply(null, lons), maxLon = Math.max.apply(null, lons);
  var midLat = (minLat + maxLat) / 2;

  // A floor on the extent. Standing still, GPS scatter spans a couple of metres, and fitting the
  // canvas to that would zoom to a jitter cloud and imply precision that is not there.
  var mLat = 111320, mLon = 111320 * Math.cos(midLat * Math.PI / 180);
  var MIN_SPAN_M = 25;
  if ((maxLon - minLon) * mLon < MIN_SPAN_M) {
    var padLon = (MIN_SPAN_M - (maxLon - minLon) * mLon) / 2 / mLon;
    minLon -= padLon; maxLon += padLon;
  }
  if ((maxLat - minLat) * mLat < MIN_SPAN_M) {
    var padLat = (MIN_SPAN_M - (maxLat - minLat) * mLat) / 2 / mLat;
    minLat -= padLat; maxLat += padLat;
  }

  // Web Mercator at a reference zoom, so the trail and the tiles share one projection exactly.
  var Z = 21, pad = 26;
  var wx0 = lonToWorld(minLon, Z), wx1 = lonToWorld(maxLon, Z);
  var wy0 = latToWorld(maxLat, Z), wy1 = latToWorld(minLat, Z);
  var scale = Math.min((cv.width - 2 * pad) / Math.max(wx1 - wx0, 1e-9),
                       (cv.height - 2 * pad) / Math.max(wy1 - wy0, 1e-9));
  var offX = pad + ((cv.width - 2 * pad) - (wx1 - wx0) * scale) / 2;
  var offY = pad + ((cv.height - 2 * pad) - (wy1 - wy0) * scale) / 2;

  // Maps a world coordinate at any zoom onto the canvas.
  function projectWorld(wy, wx, z) {
    var f = Math.pow(2, Z - z);
    return [offX + (wx * f - wx0) * scale, offY + (wy * f - wy0) * scale];
  }
  function projectLatLon(lat, lon) {
    return [offX + (lonToWorld(lon, Z) - wx0) * scale, offY + (latToWorld(lat, Z) - wy0) * scale];
  }

  if (document.getElementById('imagery').checked) {
    drawTiles(g, cv, minLat, maxLat, minLon, maxLon, projectWorld);
    document.getElementById('attribution').textContent =
      'Imagery \u00a9 Esri, Maxar, Earthstar Geographics \u2014 fetched over the phone\u2019s own connection and cached.';
  } else {
    document.getElementById('attribution').textContent = '';
  }

  // One metre in canvas pixels, for the scale bar. Mercator distorts with latitude, so this is
  // derived at the plot's own latitude rather than assumed.
  var mPerPx = (maxLat - minLat) * mLat / Math.max((wy1 - wy0) * scale, 1e-9);
  var pxPerM = 1 / mPerPx;

  var pts = track.map(function (p) { return projectLatLon(p.lat, p.lon); });

  // Heavier and brighter than before: a thin grey line vanishes over satellite imagery.
  g.strokeStyle = 'rgba(255,255,255,0.75)';
  g.lineWidth = 2;
  g.beginPath();
  pts.forEach(function (q, i) { i ? g.lineTo(q[0], q[1]) : g.moveTo(q[0], q[1]); });
  g.stroke();

  pts.forEach(function (q, i) {
    g.fillStyle = colorFor(track[i]);
    g.beginPath();
    g.arc(q[0], q[1], 4.5, 0, 6.2832);
    g.fill();
    // A thin dark ring so a green dot stays readable over grass and a red one over a roof.
    g.strokeStyle = 'rgba(0,0,0,0.55)';
    g.lineWidth = 1;
    g.stroke();
  });

  // Current position, so the operator can tell which end of the trail they are standing on.
  var last = pts[pts.length - 1];
  g.strokeStyle = '#ffffff';
  g.lineWidth = 2.5;
  g.beginPath();
  g.arc(last[0], last[1], 11, 0, 6.2832);
  g.stroke();

  // Scale bar, snapped to a round number of metres.
  var targetM = (cv.width - 2 * pad) / 4 / pxPerM;
  var base = Math.pow(10, Math.floor(Math.log(targetM) / Math.LN10));
  var niceM = [5 * base, 2 * base, base].filter(function (v) { return v <= targetM; })[0] || base;
  var barPx = niceM * pxPerM;
  g.strokeStyle = '#cfc6bd'; g.lineWidth = 2;
  g.beginPath();
  g.moveTo(pad, cv.height - 18); g.lineTo(pad + barPx, cv.height - 18);
  g.moveTo(pad, cv.height - 23); g.lineTo(pad, cv.height - 13);
  g.moveTo(pad + barPx, cv.height - 23); g.lineTo(pad + barPx, cv.height - 13);
  g.stroke();
  g.fillStyle = '#cfc6bd';
  g.font = '13px system-ui, sans-serif';
  g.fillText(Math.round(niceM) + ' m', pad + barPx + 8, cv.height - 13);

  // North arrow.
  g.fillStyle = '#cfc6bd';
  g.beginPath();
  g.moveTo(cv.width - 26, 16);
  g.lineTo(cv.width - 32, 36);
  g.lineTo(cv.width - 26, 30);
  g.lineTo(cv.width - 20, 36);
  g.closePath();
  g.fill();
  g.fillText('N', cv.width - 30, 52);
}

var misses = 0;
function poll() {
  fetch('/api/state', { cache: 'no-store' })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      misses = 0;
      var st = document.getElementById('status');
      st.textContent = d.recording ? '● recording' : 'connected — not recording';
      st.className = d.recording ? 'live' : '';
      txt('area', d.area ? 'area: ' + d.area : '');
      txt('floor', d.floor ? 'floor: ' + d.floor : '');

      var c = d.cell;
      if (c) {
        txt('rsrp', c.rsrp === null ? '—' : c.rsrp);
        document.getElementById('rsrp').style.color =
          (bucket(RSRP_SCALE, c.rsrp) || {}).color || 'inherit';
        txt('rsrpUnit', 'dBm ' + (c.rat && c.rat.indexOf('5G') === 0 ? 'SS-RSRP' : 'RSRP'));
        txt('rat', dash(c.rat)); txt('op', dash(c.operator)); txt('band', dash(c.band));
        txt('pci', (c.pci === null ? '—' : c.pci) + ' / ' + (c.channel === null ? '—' : c.channel));
        txt('sinr', dash(c.sinr, ' dB')); txt('rsrq', dash(c.rsrq, ' dB'));
        txt('nbr', dash(c.neighbours));
        drawLegend(RSRP_SCALE);
      } else if (d.wifi) {
        txt('rsrp', dash(d.wifi.rssi)); txt('rsrpUnit', 'dBm RSSI');
        txt('rat', 'Wi-Fi'); txt('op', dash(d.wifi.ssid));
        drawLegend(RSSI_SCALE);
      }
      // Stated on the live view for the same reason it is stated in the report: SINR and RSRQ
      // refresh far more slowly than RSRP, so watching them for a reaction as you walk will
      // mislead you.
      document.getElementById('cadenceNote').textContent =
        c ? 'SINR and RSRQ refresh more slowly than RSRP — expect them to lag as you move.' : '';

      var t = d.throughput;
      txt('dl', t && t.downMbps !== null ? t.downMbps.toFixed(1) : '—');
      txt('ul', t && t.upMbps !== null ? t.upMbps.toFixed(1) : '—');
      txt('tpServer', t ? dash(t.server) : '—');
      document.getElementById('tpBusy').textContent =
        d.throughputBusy ? 'transferring — radio is loaded, RF values are under load' : '';
      document.getElementById('tpNote').textContent = t && t.error ? t.error :
        (t ? 'Measures the whole path, not the radio alone. Use a LAN endpoint on site where one exists.' : '');

      txt('rows', d.rows);
      txt('elapsed', hhmmss(d.elapsedMs));
      txt('dist', d.distanceM.toFixed(0) + ' m');
      txt('acc', d.fix && d.fix.accuracyM !== null ? d.fix.accuracyM.toFixed(1) + ' m' : '—');
      txt('spd', d.fix && d.fix.speedMps !== null ? d.fix.speedMps.toFixed(1) + ' m/s' : '—');

      txt('pts', d.track.length);
      draw(d.track);
    })
    .catch(function () {
      misses++;
      // Two misses, not one: a single dropped poll during a USB hiccup is not a disconnection,
      // and flashing "lost" at the operator mid-walk would send them back to check the cable.
      if (misses >= 2) {
        var st = document.getElementById('status');
        st.textContent = 'lost connection — check the USB cable and adb forward';
        st.className = 'stale';
      }
    });
}
poll();
setInterval(poll, 1000);

// Tiles arrive asynchronously, so redraw when one lands rather than waiting for the next poll --
// otherwise the imagery appears a second late and looks like a stall.
setInterval(function () {
  if (needsRedraw && lastTrack.length) { needsRedraw = false; draw(lastTrack); }
}, 250);

document.getElementById('imagery').addEventListener('change', function () {
  if (lastTrack.length) draw(lastTrack);
});
</script>
</body>
</html>
""".trimIndent()
}

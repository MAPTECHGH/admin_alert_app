"""Render 10 premium admin-app design variants (phone mockups) for a site.

Usage:  python3 gen.py site.json      -> writes v01.html … v10.html + variants.json
        python3 shot.py               -> v01_top.png … (Playwright, 390x844 @2x)
        python3 make_sheet.py         -> variants-1-5.png, variants-6-10.png

site.json: {"site_name": "MAPTECH GLOBAL", "handle": "maptechgh", "app_name": "Maptech Admin",
            "logo": "logo.png", "brand": {"accent": "#F2B233", "blue": "#2F7BF6", "bg": "#0B1220"}}
Variant 10 ("<Site> Brand") uses the brand colours; 1-9 keep their own moods.
"""
import base64, json, sys

CFG = json.load(open(sys.argv[1] if len(sys.argv) > 1 else 'site.json'))
SITE = CFG.get('site_name', 'MY SITE')
HANDLE = CFG.get('handle', 'admin')
APP = CFG.get('app_name', 'Site Admin')
BRAND = CFG.get('brand', {})
LOGO = "data:image/png;base64," + base64.b64encode(open(CFG.get('logo', 'logo.png'), 'rb').read()).decode()

# lucide-style stroke icons
I = {
 'bell': '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>',
 'gear': '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68 1.65 1.65 0 0 0 10 3.17V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
 'menu': '<path d="M4 6h16M4 12h10M4 18h16"/>',
 'alert': '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
 'box': '<path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/>',
 'chat': '<path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/>',
 'store': '<path d="m2 7 4.41-4.41A2 2 0 0 1 7.83 2h8.34a2 2 0 0 1 1.42.59L22 7"/><path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8"/><path d="M2 7h20v3a2 2 0 0 1-2 2 2.7 2.7 0 0 1-2-1 2.7 2.7 0 0 1-2 1 2.7 2.7 0 0 1-2-1 2.7 2.7 0 0 1-2 1 2.7 2.7 0 0 1-2-1 2.7 2.7 0 0 1-2 1 2 2 0 0 1-2-2z"/>',
 'id': '<rect width="20" height="14" x="2" y="5" rx="2"/><circle cx="8" cy="12" r="2"/><path d="M14 10h4M14 14h4"/>',
 'x': '<circle cx="12" cy="12" r="10"/><path d="m15 9-6 6M9 9l6 6"/>',
 'clock': '<circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>',
 'wallet': '<path d="M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1"/><path d="M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4"/>',
 'globe': '<circle cx="12" cy="12" r="10"/><path d="M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20M2 12h20"/>',
 'wa': '<path d="M3 21l1.65-3.8a9 9 0 1 1 3.4 2.9z"/><path d="M9 10a.5.5 0 0 0 1 0V9a.5.5 0 0 0-1 0za5 5 0 0 0 5 5h1a.5.5 0 0 0 0-1h-1a.5.5 0 0 0 0 1"/>',
 'home': '<path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M9 22V12h6v10"/>',
 'list': '<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>',
 'refresh': '<path d="M21 12a9 9 0 1 1-3-6.7L21 8"/><path d="M21 3v5h-5"/>',
 'shield': '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>',
}
def ic(name, size=20, sw=2, color='currentColor'):
    return f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" stroke="{color}" stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round">{I[name]}</svg>'

TILES = [  # key, label, value, tone, icon
 ('Open complaints', 3, 'red', 'alert'),
 ('Needs review', 2, 'red', 'shield'),
 ('Processing', 11, 'amber', 'clock'),
 ('Failed today', 1, 'red', 'x'),
 ('AFA processing', 42, 'amber', 'id'),
 ('Unclaimed MoMo', 2, 'gold', 'wallet'),
 ('Shops waiting', 1, 'blue', 'store'),
 ('Unread chats', 4, 'blue', 'chat'),
]
ALERTS = [
 ('Order complaint', 'critical', 'New complaint — Hydro', 'ORD_9F912C54BDF6 • 0246862699 • "Not received yet"', '1 min'),
 ('Needs review', 'high', 'Order needs review — MTN → 0241896575', 'ADH Group did not accept this number (not verified)', '4 min'),
 ('Live Support', 'normal', 'Support: WizzDataHub', 'Good morning, my order is still not received…', '9 min'),
]
QUICK = [('Orders','box'),('AFA','id'),('Complaints','alert'),('Support','chat'),('Shops','store')]

STATUS_BAR = '''<div class="sb"><span>6:49</span><span class="sbr">▂▄▆ ᯤ ▮61</span></div>'''

def credits(v):
    return f'''<div class="credits">
      <div class="cr-top"><img src="{LOGO}" class="cr-logo"/><div><div class="cr-by">Developed by</div><div class="cr-name">M-AVETECH IT SERVICES</div></div></div>
      <div class="cr-btns"><a class="cr-btn">{ic('globe',16)} mavetechservices.com</a><a class="cr-btn wa">{ic('wa',16)} WhatsApp</a></div>
      <div class="cr-ver">{APP} • © 2026</div></div>'''

BASE_CSS = '''
*{box-sizing:border-box;margin:0;padding:0}
body{width:390px;font-family:Inter,sans-serif;-webkit-font-smoothing:antialiased;background:var(--bg);color:var(--text)}
.sb{display:flex;justify-content:space-between;padding:12px 22px 6px;font-size:13px;font-weight:600;color:var(--text)}
.sbr{letter-spacing:1px;font-size:11px;opacity:.85}
.wrap{padding:0 16px 24px}
.top{display:flex;align-items:center;gap:10px;padding:10px 16px 14px}
.logo{width:42px;height:42px;border-radius:12px;object-fit:contain;padding:4px;background:var(--logobg)}
.tt{flex:1}.tt h1{font-size:17px;font-weight:800;letter-spacing:.2px}.tt p{font-size:12px;color:var(--muted);margin-top:2px}
.ib{width:38px;height:38px;border-radius:12px;display:flex;align-items:center;justify-content:center;color:var(--text);background:var(--chip)}
.sec{font-size:11px;font-weight:700;letter-spacing:1.4px;text-transform:uppercase;color:var(--muted);margin:18px 4px 10px}
.credits{margin-top:22px;padding:16px;border-radius:var(--r);background:var(--card);border:1px solid var(--stroke)}
.cr-top{display:flex;gap:12px;align-items:center}.cr-logo{width:40px;height:40px;object-fit:contain;border-radius:10px;background:var(--logobg);padding:4px}
.cr-by{font-size:11px;color:var(--muted)}.cr-name{font-size:14px;font-weight:800;letter-spacing:.3px}
.cr-btns{display:flex;gap:8px;margin-top:12px}.cr-btn{flex:1;display:flex;gap:6px;align-items:center;justify-content:center;padding:10px;border-radius:12px;font-size:12.5px;font-weight:700;background:var(--chip);color:var(--text)}
.cr-btn.wa{background:#1FAF5A;color:#fff}
.cr-ver{text-align:center;font-size:11px;color:var(--muted);margin-top:10px}
.tone-red{--t:var(--red)}.tone-amber{--t:var(--amber)}.tone-gold{--t:var(--gold)}.tone-blue{--t:var(--blue)}
.pill{display:inline-flex;align-items:center;gap:5px;font-size:10.5px;font-weight:800;letter-spacing:.8px;text-transform:uppercase;padding:4px 9px;border-radius:999px;color:var(--t);background:color-mix(in srgb,var(--t) 16%,transparent)}
.dot{width:8px;height:8px;border-radius:50%;background:var(--green);box-shadow:0 0 0 4px color-mix(in srgb,var(--green) 25%,transparent)}
.sw{width:46px;height:26px;border-radius:13px;background:color-mix(in srgb,var(--accent) 55%,transparent);position:relative}
.sw:after{content:"";position:absolute;right:3px;top:3px;width:20px;height:20px;border-radius:50%;background:var(--accent)}
'''

def alert_rows(cls='al'):
    h = ''
    for kind, pr, title, body, t in ALERTS:
        tone = {'critical':'red','high':'amber','normal':'blue'}[pr]
        h += f'''<div class="{cls} tone-{tone}"><div class="al-h"><span class="pill">{kind}</span><span class="al-t">{t} ago</span></div>
          <div class="al-title">{title}</div><div class="al-body">{body}</div></div>'''
    return h

def topbar(sub=None):
    sub = sub or f'{HANDLE} • Owner'
    return f'''<div class="top"><img class="logo" src="{LOGO}"/><div class="tt"><h1>{SITE}</h1><p>{sub}</p></div>
      <div class="ib">{ic('gear',19)}</div><div class="ib">{ic('menu',19)}</div></div>'''

# ───────────────────────── Layouts ─────────────────────────
def layout_classic(v):
    tiles = ''.join(f'''<div class="tile tone-{t}"><div class="ti-ic">{ic(icn,18)}</div><div class="ti-v">{val}</div><div class="ti-l">{lab}</div></div>'''
                    for lab, val, t, icn in TILES)
    return f'''{STATUS_BAR}{topbar()}<div class="wrap">
      <div class="hero"><div class="hero-row"><span class="dot"></span><div style="flex:1"><div class="h-t">Live alerts are ON</div><div class="h-s">Checks every 15s • last 12s ago</div></div><div class="sw"></div></div>
      <div class="hero-btns"><div class="hb">{ic('refresh',15)} Check now</div><div class="hb">{ic('bell',15)} Alert sounds</div></div></div>
      <div class="sec">Right now</div><div class="grid">{tiles}</div>
      <div class="sec">Recent alerts</div>{alert_rows()}{credits(v)}</div>'''

def layout_banner(v):
    tiles = ''.join(f'''<div class="mini tone-{t}"><div class="mi-ic">{ic(icn,17)}</div><div><div class="mi-v">{val}</div><div class="mi-l">{lab}</div></div></div>'''
                    for lab, val, t, icn in TILES)
    quick = ''.join(f'<div class="q"><div class="q-ic">{ic(icn,20)}</div><span>{n}</span></div>' for n, icn in QUICK)
    return f'''<div class="banner">{STATUS_BAR}{topbar(f'Good evening, {HANDLE}')}
      <div class="bn-body"><div class="bn-k">Needs attention now</div><div class="bn-v">8<span> items</span></div>
      <div class="bn-row"><span class="bn-live"><span class="dot"></span> Live • checked 12s ago</span><span class="bn-chip">Check now</span></div></div></div>
      <div class="wrap"><div class="quick">{quick}</div>
      <div class="sec">Overview</div><div class="minis">{tiles}</div>
      <div class="sec">Recent alerts</div>{alert_rows()}{credits(v)}</div>
      <div class="nav"><div class="nv on">{ic('home',20)}<span>Home</span></div><div class="nv">{ic('box',20)}<span>Orders</span></div><div class="nv">{ic('bell',20)}<span>Alerts</span></div><div class="nv">{ic('store',20)}<span>Shops</span></div><div class="nv">{ic('gear',20)}<span>Settings</span></div></div>'''

def layout_queue(v):
    strip = ''.join(f'<div class="st tone-{t}"><b>{val}</b><span>{lab}</span></div>' for lab, val, t, icn in TILES)
    q = ''
    for kind, pr, title, body, t in ALERTS + [('Failed order','high','Order failed — MTN 2GB → 0245398929','bluesparksent • GH₵8.80 • Provider timeout','12 min')]:
        tone = {'critical':'red','high':'amber','normal':'blue'}[pr]
        q += f'''<div class="qi tone-{tone}"><div class="qi-bar"></div><div class="qi-b"><div class="al-h"><span class="pill">{kind}</span><span class="al-t">{t}</span></div>
          <div class="al-title">{title}</div><div class="al-body">{body}</div>
          <div class="qi-a"><span class="qa main">Handle now</span><span class="qa">Silence</span></div></div></div>'''
    return f'''{STATUS_BAR}{topbar()}<div class="wrap">
      <div class="livebar"><span class="dot"></span><b>LIVE</b><span>checked 12s ago • 15s interval</span><div class="sw"></div></div>
      <div class="strip">{strip}</div>
      <div class="sec">Priority queue • 4 waiting</div>{q}{credits(v)}</div>'''

def layout_command(v):
    cells = ''
    for i, (lab, val, t, icn) in enumerate(TILES):
        bars = ''.join(f'<i style="height:{h}%"></i>' for h in [(i*37+k*23)%70+20 for k in range(9)])
        cells += f'''<div class="cell tone-{t}"><div class="c-h"><span>{lab}</span>{ic(icn,14)}</div><div class="c-v">{val:02d}</div><div class="bars">{bars}</div></div>'''
    log = ''.join(f'<div class="ln"><span class="lt">18:{40+i}</span><span class="lk tone-{["red","amber","blue"][i%3]}">{k.upper()}</span><span class="lm">{title}</span></div>'
                  for i,(k,pr,title,b,t) in enumerate(ALERTS*2))
    return f'''{STATUS_BAR}{topbar('OPS CONSOLE • owner')}<div class="wrap">
      <div class="sys"><div><div class="sys-k">SYSTEM</div><div class="sys-v"><span class="dot"></span> MONITORING</div></div><div class="sys-m">POLL 15s<br>LAST 12s</div><div class="sw"></div></div>
      <div class="cells">{cells}</div>
      <div class="sec">Event log</div><div class="log">{log}</div>{credits(v)}</div>'''

def layout_editorial(v):
    rows = ''.join(f'''<div class="er tone-{t}"><span class="er-l">{lab}</span><span class="er-v">{val}</span></div>''' for lab, val, t, icn in TILES)
    return f'''{STATUS_BAR}{topbar()}<div class="wrap">
      <div class="ed-h"><div class="ed-k">Thursday, 15 October</div><div class="ed-t">8 things need you.</div>
      <div class="ed-s"><span class="dot"></span> Live alerts on — checked 12 seconds ago</div></div>
      <div class="ed-card">{rows}</div>
      <div class="sec">Latest</div>{alert_rows()}{credits(v)}</div>'''

LAYOUTS = {'classic': layout_classic, 'banner': layout_banner, 'queue': layout_queue, 'command': layout_command, 'editorial': layout_editorial}

LAYOUT_CSS = {
'classic': '''
.hero{padding:16px;border-radius:var(--r);background:var(--herobg);border:1px solid var(--stroke);box-shadow:var(--shadow)}
.hero-row{display:flex;gap:12px;align-items:center}.h-t{font-size:16px;font-weight:800}.h-s{font-size:12.5px;color:var(--muted);margin-top:2px}
.hero-btns{display:flex;gap:8px;margin-top:14px}.hb{flex:1;display:flex;gap:6px;align-items:center;justify-content:center;padding:11px;border-radius:12px;background:var(--chip);font-size:13px;font-weight:700}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.tile{padding:14px;border-radius:var(--r);background:var(--card);border:1px solid var(--stroke);box-shadow:var(--shadow);position:relative;overflow:hidden}
.tile:before{content:"";position:absolute;inset:0 auto 0 0;width:3px;background:var(--t)}
.ti-ic{width:32px;height:32px;border-radius:10px;display:flex;align-items:center;justify-content:center;color:var(--t);background:color-mix(in srgb,var(--t) 15%,transparent)}
.ti-v{font-size:28px;font-weight:800;margin-top:10px;letter-spacing:-.5px}.ti-l{font-size:12.5px;color:var(--muted)}
.al{padding:14px;border-radius:var(--r);background:var(--card);border:1px solid var(--stroke);margin-bottom:10px;box-shadow:var(--shadow)}
.al-h{display:flex;justify-content:space-between;align-items:center}.al-t{font-size:11.5px;color:var(--muted)}
.al-title{font-size:15px;font-weight:700;margin-top:9px}.al-body{font-size:12.5px;color:var(--muted);margin-top:3px;line-height:1.4}
''',
'banner': '''
body{padding-bottom:70px}
.banner{background:var(--banner);border-radius:0 0 28px 28px;padding-bottom:22px;color:var(--bantext)}
.banner .sb,.banner .tt h1,.banner .ib{color:var(--bantext)}.banner .tt p{color:color-mix(in srgb,var(--bantext) 70%,transparent)}
.banner .ib{background:rgba(255,255,255,.14)}.banner .logo{background:rgba(255,255,255,.95)}
.bn-body{padding:4px 20px 0}.bn-k{font-size:13px;opacity:.8}.bn-v{font-size:46px;font-weight:800;letter-spacing:-1.5px;line-height:1.05}.bn-v span{font-size:18px;font-weight:600;letter-spacing:0;opacity:.8}
.bn-row{display:flex;justify-content:space-between;align-items:center;margin-top:12px}.bn-live{display:flex;gap:8px;align-items:center;font-size:12.5px;opacity:.9}
.bn-chip{background:rgba(255,255,255,.18);padding:8px 14px;border-radius:999px;font-size:12.5px;font-weight:700}
.quick{display:flex;justify-content:space-between;margin-top:-18px;padding:14px 10px;border-radius:var(--r);background:var(--card);box-shadow:var(--shadow);border:1px solid var(--stroke)}
.q{display:flex;flex-direction:column;align-items:center;gap:6px;font-size:11.5px;font-weight:600;color:var(--muted)}
.q-ic{width:44px;height:44px;border-radius:14px;display:flex;align-items:center;justify-content:center;background:var(--chip);color:var(--accent)}
.minis{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.mini{display:flex;gap:10px;align-items:center;padding:12px;border-radius:16px;background:var(--card);border:1px solid var(--stroke)}
.mi-ic{width:36px;height:36px;border-radius:12px;display:flex;align-items:center;justify-content:center;color:var(--t);background:color-mix(in srgb,var(--t) 15%,transparent)}
.mi-v{font-size:19px;font-weight:800}.mi-l{font-size:11.5px;color:var(--muted)}
.al{padding:14px;border-radius:var(--r);background:var(--card);border:1px solid var(--stroke);margin-bottom:10px}
.al-h{display:flex;justify-content:space-between;align-items:center}.al-t{font-size:11.5px;color:var(--muted)}
.al-title{font-size:15px;font-weight:700;margin-top:9px}.al-body{font-size:12.5px;color:var(--muted);margin-top:3px;line-height:1.4}
.nav{position:fixed;bottom:0;left:0;width:390px;display:flex;justify-content:space-around;padding:10px 0 14px;background:var(--navbg);border-top:1px solid var(--stroke)}
.nv{display:flex;flex-direction:column;align-items:center;gap:3px;font-size:10.5px;color:var(--muted)}.nv.on{color:var(--accent);font-weight:700}
''',
'queue': '''
.livebar{display:flex;align-items:center;gap:10px;padding:12px 14px;border-radius:14px;background:var(--card);border:1px solid var(--stroke);font-size:12.5px;color:var(--muted)}
.livebar b{color:var(--green);letter-spacing:1.5px}.livebar span:nth-child(3){flex:1}
.strip{display:flex;gap:8px;overflow:hidden;margin-top:12px;flex-wrap:wrap}
.st{flex:1 1 22%;padding:10px 8px;border-radius:12px;background:var(--card);border:1px solid var(--stroke);text-align:center}
.st b{display:block;font-size:20px;font-weight:800;color:var(--t)}.st span{font-size:10px;color:var(--muted);line-height:1.2;display:block;margin-top:2px}
.qi{display:flex;border-radius:var(--r);background:var(--card);border:1px solid var(--stroke);margin-bottom:10px;overflow:hidden;box-shadow:var(--shadow)}
.qi-bar{width:5px;background:var(--t)}.qi-b{padding:14px;flex:1}
.al-h{display:flex;justify-content:space-between;align-items:center}.al-t{font-size:11.5px;color:var(--muted)}
.al-title{font-size:15px;font-weight:700;margin-top:9px}.al-body{font-size:12.5px;color:var(--muted);margin-top:3px;line-height:1.4}
.qi-a{display:flex;gap:8px;margin-top:12px}.qa{padding:9px 14px;border-radius:10px;font-size:12.5px;font-weight:700;background:var(--chip)}
.qa.main{background:var(--t);color:var(--ontone)}
''',
'command': '''
.sys{display:flex;align-items:center;gap:12px;padding:14px;border-radius:var(--r);background:var(--card);border:1px solid var(--stroke)}
.sys>div:first-child{flex:1}.sys-k{font:600 10px/1 "DejaVu Sans Mono",monospace;letter-spacing:2px;color:var(--muted)}
.sys-v{display:flex;gap:8px;align-items:center;font:700 15px/1.6 "DejaVu Sans Mono",monospace;color:var(--green);margin-top:4px}
.sys-m{font:600 10.5px/1.5 "DejaVu Sans Mono",monospace;color:var(--muted);text-align:right}
.cells{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:12px}
.cell{padding:12px;border-radius:12px;background:var(--card);border:1px solid var(--stroke)}
.c-h{display:flex;justify-content:space-between;font:600 10px/1.2 "DejaVu Sans Mono",monospace;letter-spacing:.8px;text-transform:uppercase;color:var(--muted)}
.c-h svg{color:var(--t)}.c-v{font:700 28px/1.2 "DejaVu Sans Mono",monospace;color:var(--t);margin-top:6px}
.bars{display:flex;gap:3px;align-items:flex-end;height:22px;margin-top:6px}.bars i{flex:1;background:color-mix(in srgb,var(--t) 45%,transparent);border-radius:2px}
.log{border-radius:var(--r);background:var(--card);border:1px solid var(--stroke);padding:10px 12px}
.ln{display:flex;gap:8px;font:500 11px/2 "DejaVu Sans Mono",monospace;white-space:nowrap;overflow:hidden}
.lt{color:var(--muted)}.lk{color:var(--t);font-weight:700;min-width:96px}.lm{overflow:hidden;text-overflow:ellipsis}
''',
'editorial': '''
.ed-h{padding:12px 4px 18px}.ed-k{font-size:13px;color:var(--muted);font-weight:600}
.ed-t{font-size:36px;font-weight:800;letter-spacing:-1.2px;line-height:1.1;margin-top:6px}
.ed-s{display:flex;gap:8px;align-items:center;font-size:13px;color:var(--muted);margin-top:12px}
.ed-card{border-radius:var(--r);background:var(--card);border:1px solid var(--stroke);box-shadow:var(--shadow);overflow:hidden}
.er{display:flex;justify-content:space-between;align-items:center;padding:15px 16px;border-bottom:1px solid var(--stroke)}
.er:last-child{border-bottom:0}.er-l{font-size:14.5px;font-weight:600}.er-v{font-size:17px;font-weight:800;color:var(--t);min-width:40px;text-align:center;padding:3px 10px;border-radius:10px;background:color-mix(in srgb,var(--t) 13%,transparent)}
.al{padding:14px 2px;border-bottom:1px solid var(--stroke)}
.al-h{display:flex;justify-content:space-between;align-items:center}.al-t{font-size:11.5px;color:var(--muted)}
.al-title{font-size:15.5px;font-weight:700;margin-top:8px}.al-body{font-size:13px;color:var(--muted);margin-top:3px;line-height:1.45}
''',
}

VARIANTS = [
 dict(n=1, name='Midnight Gold', layout='classic', desc='Refined version of today’s look: deep navy, gold accents, soft-lit cards with coloured edges.',
      vars=dict(bg='#0A0F1C', card='#121A2C', chip='#1A2440', stroke='#24314D', text='#ECF0F7', muted='#8D99B0', accent='#F5B942', red='#F05252', amber='#F59E0B', gold='#F5B942', blue='#5B93FF', green='#22C55E', logobg='#fff', r='18px',
                herobg='linear-gradient(135deg,#1B2440,#121A2C 60%)', shadow='0 6px 18px rgba(0,0,0,.35)', ontone='#111')),
 dict(n=2, name='Royal Banking', layout='banner', desc='Bank-app structure: rich blue hero with a big “needs attention” number, quick actions and a bottom tab bar.',
      vars=dict(bg='#F3F6FC', card='#FFFFFF', chip='#EEF2FB', stroke='#E3E9F5', text='#0F1B33', muted='#6A7894', accent='#1E5EFF', red='#E5484D', amber='#E58A00', gold='#C99A1E', blue='#1E5EFF', green='#22C55E', logobg='#fff', r='18px',
                banner='linear-gradient(140deg,#0B2E8A 0%,#1E5EFF 70%,#3C8BFF)', bantext='#FFFFFF', navbg='#FFFFFF', shadow='0 10px 24px rgba(19,45,110,.10)', ontone='#fff')),
 dict(n=3, name='Carbon Alert', layout='queue', desc='Alert-first: compact counters on top, a priority queue with “Handle now” buttons. Pure black with red and amber.',
      vars=dict(bg='#050505', card='#111111', chip='#1C1C1C', stroke='#232323', text='#F5F5F5', muted='#8A8A8A', accent='#FF3B30', red='#FF453A', amber='#FF9F0A', gold='#FFD60A', blue='#64D2FF', green='#32D74B', logobg='#fff', r='16px',
                shadow='none', ontone='#000')),
 dict(n=4, name='Emerald Ops Console', layout='command', desc='Command-centre look: monospace numbers, mini activity bars and a live event log. Green-black terminal mood.',
      vars=dict(bg='#06110D', card='#0C1A15', chip='#13261F', stroke='#1B3329', text='#DDF5EA', muted='#6F9585', accent='#2EE59D', red='#FF5C6C', amber='#FFB547', gold='#E8D06A', blue='#4FD1FF', green='#2EE59D', logobg='#E9FFF5', r='14px',
                shadow='none', ontone='#000')),
 dict(n=5, name='Pearl Executive', layout='editorial', desc='Light, calm and premium. Big editorial headline (“8 things need you.”), one clean list of counts, then the latest alerts.',
      vars=dict(bg='#FAFAF7', card='#FFFFFF', chip='#F1F0EA', stroke='#E9E7DF', text='#151515', muted='#77756C', accent='#B8862B', red='#C9372C', amber='#C27803', gold='#B8862B', blue='#2F5BD3', green='#1F9D55', logobg='#fff', r='20px',
                shadow='0 8px 24px rgba(30,25,10,.06)', ontone='#fff')),
 dict(n=6, name='Aurora Glass', layout='classic', desc='Same layout as #1 on a violet-to-teal aurora background with frosted see-through cards. The most eye-catching option.',
      vars=dict(bg='radial-gradient(circle at 15% 0%,#5B2BD9 0,transparent 45%),radial-gradient(circle at 100% 30%,#0EA5A4 0,transparent 40%),#0B0820', card='rgba(255,255,255,.07)', chip='rgba(255,255,255,.10)', stroke='rgba(255,255,255,.12)', text='#F3F0FF', muted='#B3AAD6', accent='#8B5CF6', red='#FF6B8B', amber='#FFC266', gold='#FFD27A', blue='#6EE7F9', green='#4ADE80', logobg='#fff', r='20px',
                herobg='linear-gradient(135deg,rgba(139,92,246,.35),rgba(14,165,164,.25))', shadow='0 8px 30px rgba(0,0,0,.25)', ontone='#111')),
 dict(n=7, name='Graphite Pro', layout='editorial', desc='Dark editorial: graphite tones, one electric-blue accent, oversized headline. Minimal and serious.',
      vars=dict(bg='#131416', card='#1B1C1F', chip='#24262A', stroke='#2A2C31', text='#F2F3F5', muted='#8E939C', accent='#3D8BFF', red='#FF5A5F', amber='#FFB020', gold='#E2C044', blue='#3D8BFF', green='#30D158', logobg='#fff', r='16px',
                shadow='none', ontone='#fff')),
 dict(n=8, name='Sunset Pulse', layout='banner', desc='Banking structure (#2) in a dark warm theme: magenta-to-orange hero, bottom tabs, bold rounded cards.',
      vars=dict(bg='#120C14', card='#1C1420', chip='#271C2D', stroke='#33253A', text='#FFF4F0', muted='#B49BA8', accent='#FF7A45', red='#FF4D6D', amber='#FFA94D', gold='#FFC857', blue='#7AA2FF', green='#43D17A', logobg='#fff', r='20px',
                banner='linear-gradient(135deg,#C2185B 0%,#FF5E3A 65%,#FF9A3C)', bantext='#FFFFFF', navbg='#1C1420', shadow='0 10px 26px rgba(0,0,0,.35)', ontone='#111')),
 dict(n=9, name='Ocean Queue', layout='queue', desc='Priority-queue structure (#3) in a deep ocean-blue theme with cyan accents. Softer than Carbon Alert.',
      vars=dict(bg='#06142A', card='#0C1F3D', chip='#12294F', stroke='#173363', text='#EAF4FF', muted='#86A3C9', accent='#22D3EE', red='#FF6B6B', amber='#FFB84D', gold='#FFD166', blue='#22D3EE', green='#34D399', logobg='#fff', r='18px',
                shadow='0 8px 22px rgba(0,0,0,.3)', ontone='#06142A')),
 dict(n=10, name=SITE.title().split()[0] + ' Brand', layout='classic', desc='Built from the site\'s own logo/brand colours so the app matches the website.',
      vars=dict(bg=BRAND.get('bg', '#07101F'), card=BRAND.get('card', '#0E1B33'), chip='#152647', stroke='#1E3463', text='#EEF4FF', muted='#8AA0C6',
                accent=BRAND.get('accent', '#F2B630'), red='#FF5D5D', amber='#F2B630', gold=BRAND.get('accent', '#F2B630'), blue=BRAND.get('blue', '#2F8BFF'), green='#2ED47A', logobg='#fff', r='18px',
                herobg=f"linear-gradient(120deg,{BRAND.get('accent', '#F2B630')} 0%,{BRAND.get('blue', '#1F6FE5')} 100%)", shadow='0 8px 22px rgba(0,0,0,.35)', ontone='#111')),
]

def page(v):
    vs = ';'.join(f'--{k}:{val}' for k, val in v['vars'].items())
    extra = ''
    if v['n'] == 10:  # brand hero text on gold/blue gradient
        extra = '.hero{border:0}.hero .h-t,.hero .h-s{color:#0B1630}.hero .hb{background:rgba(255,255,255,.85);color:#0B1630}.hero .sw{background:rgba(11,22,48,.35)}.hero .sw:after{background:#0B1630}.hero .dot{background:#0B8A4A}'
    if v['n'] == 6:
        extra = 'body{background-attachment:fixed}'
    return f'''<!doctype html><html><head><meta charset="utf-8"><style>:root{{{vs}}}{BASE_CSS}{LAYOUT_CSS[v["layout"]]}{extra}</style></head>
<body>{LAYOUTS[v["layout"]](v)}</body></html>'''

for v in VARIANTS:
    open(f'v{v["n"]:02d}.html', 'w').write(page(v))
json.dump([{k: v[k] for k in ('n', 'name', 'layout', 'desc')} for v in VARIANTS], open('variants.json', 'w'), indent=1)
print('ok')

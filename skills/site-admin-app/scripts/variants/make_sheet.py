"""Combine v01_top.png … v10_top.png into two labelled contact sheets (variants-1-5.png, variants-6-10.png)."""
import json
from PIL import Image, ImageDraw, ImageFont

meta = {v['n']: v for v in json.load(open('variants.json'))}
def font(sz, bold=False):
    for f in (['/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'] if bold else ['/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf']):
        try: return ImageFont.truetype(f, sz)
        except Exception: pass
    return ImageFont.load_default()

for start in (1, 6):
    shots = [Image.open(f'v{n:02d}_top.png').convert('RGB') for n in range(start, start + 5)]
    w, h = shots[0].size; pad = 40; label = 150
    sheet = Image.new('RGB', (pad + 5 * (w + pad), h + label + pad * 2), '#0d0f14')
    d = ImageDraw.Draw(sheet)
    for i, im in enumerate(shots):
        x = pad + i * (w + pad); n = start + i
        sheet.paste(im, (x, pad))
        d.text((x, pad + h + 24), f"{n}. {meta[n]['name']}", fill='#ffffff', font=font(44, True))
        d.text((x, pad + h + 84), meta[n]['layout'].upper() + ' LAYOUT', fill='#9aa4b8', font=font(30))
    sheet.save(f'variants-{start}-{start + 4}.png')
print('sheets ok')

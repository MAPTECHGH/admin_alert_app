import asyncio, json, os
HERE = os.path.dirname(os.path.abspath(__file__))
from playwright.async_api import async_playwright
async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        pg = await b.new_page(viewport={'width':390,'height':844}, device_scale_factor=2)
        for v in json.load(open('variants.json')):
            n=v['n']
            await pg.goto(f'file://{os.getcwd()}/v{n:02d}.html'); await pg.wait_for_timeout(150)
            await pg.screenshot(path=f'v{n:02d}_top.png')                 # first screen
            await pg.screenshot(path=f'v{n:02d}_full.png', full_page=True)
        await b.close()
asyncio.run(main())

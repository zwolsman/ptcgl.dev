#!/usr/bin/env python3
"""
Explore badge-related CDN bundles:
  - board_ace_spec    (ACE SPEC badge)
  - cards_*           (other card property bundles)
  - propertybadge_*   (extract individual sprites from atlas via Sprite objects)
"""

import sys
import json
import urllib.request
from pathlib import Path

CDN_BASE  = "https://cdn.studio-prod.pokemon.com"
APP_VERSION = "1.39.0"
PLATFORM  = "osxplayer"
BUCKET    = "10101_0000"
OUT_DIR   = Path("/tmp/badge_explore")

def fetch_bytes(url):
    print(f"  GET {url}", file=sys.stderr)
    with urllib.request.urlopen(url) as r:
        return r.read()

def get_content_path():
    url = f"{CDN_BASE}/rainier/GameSettings/{APP_VERSION}/GameSettings.json"
    data = json.loads(fetch_bytes(url))
    return data["data"][f"{PLATFORM}_contentpath"]

def inspect_bundle(name, bundle_bytes, out_dir):
    import UnityPy
    out_dir.mkdir(parents=True, exist_ok=True)
    env = UnityPy.load(bundle_bytes)
    print(f"\n  Objects in {name}:")
    for obj in env.objects:
        type_name = obj.type.name
        if type_name in ('Texture2D', 'Sprite', 'SpriteAtlas', 'MonoBehaviour'):
            try:
                item = obj.read()
                item_name = (getattr(item, 'm_Name', None) or
                             getattr(item, 'name', None) or
                             f"{type_name}_{obj.path_id}")
                print(f"    [{type_name}] {item_name}")

                if type_name == 'Texture2D':
                    img = getattr(item, 'image', None)
                    if img:
                        path = out_dir / f"{item_name}.png"
                        img.save(str(path))

                elif type_name == 'Sprite':
                    # Sprites carry their own cropped image when read via UnityPy
                    img = getattr(item, 'image', None)
                    if img:
                        path = out_dir / f"sprite_{item_name}.png"
                        img.save(str(path))
                        print(f"      → sprite image saved")

                elif type_name == 'SpriteAtlas':
                    try:
                        tree = obj.read_typetree()
                        packed = tree.get('m_PackedSprites', [])
                        print(f"      → {len(packed)} packed sprites")
                    except Exception as e:
                        print(f"      → typetree error: {e}")
            except Exception as e:
                print(f"    [{type_name}] ERROR: {e}")

def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    content_path = get_content_path()

    targets = [
        "board_ace_spec",
        "cards_block_move",
        "cards_fizzle",
        "cards_future",
    ]

    for name in targets:
        print(f"\n{'='*60}\nBundle: {name}")
        url = f"{content_path}{BUCKET}/{name}"
        try:
            data = fetch_bytes(url)
            print(f"  Size: {len(data):,} bytes")
            inspect_bundle(name, data, OUT_DIR / name)
        except Exception as e:
            print(f"  FAILED: {e}")

    # Also try extracting individual sprites from propertybadge_en
    print(f"\n{'='*60}\nSprite extraction from propertybadge_en")
    pb_url = f"{content_path}{BUCKET}/propertybadge_en"
    pb_data = fetch_bytes(pb_url)
    inspect_bundle("propertybadge_en_sprites", pb_data, OUT_DIR / "propertybadge_en_sprites")

if __name__ == '__main__':
    main()

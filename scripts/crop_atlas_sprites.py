#!/usr/bin/env python3
"""
Extract individual sprites from packed SpriteAtlas by reading sprite rects.
Targets: tag_ex_Lower and tag_VStar from the propertybadge atlas.
"""

import sys
import json
import urllib.request
import tempfile
import os
from pathlib import Path

CDN_BASE    = "https://cdn.studio-prod.pokemon.com"
APP_VERSION = "1.39.0"
PLATFORM    = "osxplayer"
BUCKET      = "10101_0000"
OUT_DIR     = Path("/tmp/atlas_crops")

def fetch_bytes(url):
    print(f"  GET {url}", file=sys.stderr)
    with urllib.request.urlopen(url) as r:
        return r.read()

def get_content_path():
    url = f"{CDN_BASE}/rainier/GameSettings/{APP_VERSION}/GameSettings.json"
    return json.loads(fetch_bytes(url))["data"][f"{PLATFORM}_contentpath"]

def bundle_url(cp, name):
    return f"{cp}{BUCKET}/{name}"

def inspect_sprite_rects(bundle_bytes_list):
    """Print all Sprite rect info so we can identify positions in the atlas."""
    import UnityPy
    with tempfile.TemporaryDirectory() as tmpdir:
        for i, data in enumerate(bundle_bytes_list):
            with open(os.path.join(tmpdir, f"b{i}"), 'wb') as f:
                f.write(data)
        env = UnityPy.load(tmpdir)

        for obj in env.objects:
            if obj.type.name == 'Sprite':
                try:
                    tree = obj.read_typetree()
                    name = tree.get('m_Name', '?')
                    rect = tree.get('m_Rect', {})
                    rd   = tree.get('m_RD', {})
                    tex_rect = rd.get('textureRect', {})
                    print(f"  Sprite '{name}': m_Rect={rect}  textureRect={tex_rect}")
                except Exception as e:
                    pass

            if obj.type.name == 'SpriteAtlas':
                try:
                    tree = obj.read_typetree()
                    atlas_name = tree.get('m_Name', '?')
                    packed = tree.get('m_PackedSprites', [])
                    print(f"\n  SpriteAtlas '{atlas_name}': {len(packed)} packed sprites")
                    render_data = tree.get('m_RenderDataMap', {})
                    for key, val in list(render_data.items())[:5]:
                        print(f"    key={key}  val_keys={list(val.keys()) if isinstance(val, dict) else type(val)}")
                except Exception as e:
                    print(f"  SpriteAtlas error: {e}")

def crop_atlas(atlas_png, x, y, w, h, out_path, flip_y=True):
    """Crop a sprite rect from an atlas PNG. Unity uses bottom-left origin."""
    from PIL import Image
    img = Image.open(atlas_png).convert('RGBA')
    aw, ah = img.size
    if flip_y:
        # Unity: y=0 is bottom; PIL: y=0 is top
        py = ah - y - h
    else:
        py = y
    cropped = img.crop((int(x), int(py), int(x + w), int(py + h)))
    cropped.save(str(out_path))
    print(f"  Cropped {w}x{h} from ({x},{y}) → {out_path}")
    return cropped

def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    cp = get_content_path()

    pb_en     = fetch_bytes(bundle_url(cp, "propertybadge_en"))
    pb_shared = fetch_bytes(bundle_url(cp, "propertybadge_shared"))

    print("\n=== Sprite rect inspection ===")
    inspect_sprite_rects([pb_en, pb_shared])

if __name__ == '__main__':
    main()

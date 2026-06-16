#!/usr/bin/env python3
"""
Download and extract specific sprite atlas bundles from the CDN.

Downloads energyicons_t and propertybadge_en/shared, extracts textures to /tmp/sprites/.
"""

import sys
import json
import urllib.request
import os
from pathlib import Path

CDN_BASE = "https://cdn.studio-prod.pokemon.com"
APP_VERSION = "1.39.0"
PLATFORM = "osxplayer"
BUCKET = "10101_0000"
LOCALE = "en"
OUT_DIR = Path("/tmp/sprites")

TARGETS = [
    "energyicons_t",
    "propertybadge_en",
    "propertybadge_shared",
    "attack_fire",
    "attack_grass",
    "attack_water",
]


def fetch_bytes(url: str) -> bytes:
    print(f"  GET {url}", file=sys.stderr)
    with urllib.request.urlopen(url) as r:
        return r.read()


def get_content_path() -> str:
    url = f"{CDN_BASE}/rainier/GameSettings/{APP_VERSION}/GameSettings.json"
    data = json.loads(fetch_bytes(url))
    return data["data"][f"{PLATFORM}_contentpath"]


def extract_bundle(name: str, bundle_bytes: bytes, out_dir: Path) -> list[str]:
    import UnityPy
    env = UnityPy.load(bundle_bytes)
    extracted = []
    for obj in env.objects:
        type_name = obj.type.name
        if type_name == 'Texture2D':
            tex = obj.read()
            tex_name = getattr(tex, 'm_Name', None) or getattr(tex, 'name', None) or f"texture_{obj.path_id}"
            out_path = out_dir / f"{tex_name}.png"
            img = getattr(tex, 'image', None)
            if img:
                img.save(str(out_path))
                extracted.append(str(out_path))
                print(f"  Saved Texture2D '{tex_name}' → {out_path}")
            else:
                print(f"  Texture2D '{tex_name}' has no image data")
        elif type_name == 'Sprite':
            sprite = obj.read()
            sprite_name = getattr(sprite, 'm_Name', None) or getattr(sprite, 'name', None) or f"sprite_{obj.path_id}"
            print(f"  Sprite: '{sprite_name}'")
        elif type_name == 'SpriteAtlas':
            print(f"  SpriteAtlas found in '{name}'")
        elif type_name == 'MonoBehaviour':
            try:
                tree = obj.read_typetree()
                print(f"  MonoBehaviour keys: {list(tree.keys())[:10]}")
            except Exception:
                pass
    return extracted


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    content_path = get_content_path()
    print(f"Content path: {content_path}", file=sys.stderr)

    for asset_name in TARGETS:
        url = f"{content_path}{BUCKET}/{asset_name}"
        out_sub = OUT_DIR / asset_name
        out_sub.mkdir(exist_ok=True)

        print(f"\n{'='*60}")
        print(f"Asset: {asset_name}")
        try:
            bundle_bytes = fetch_bytes(url)
            print(f"  Size: {len(bundle_bytes):,} bytes")
        except Exception as e:
            print(f"  FAILED: {e}")
            continue

        extracted = extract_bundle(asset_name, bundle_bytes, out_sub)
        print(f"  Extracted {len(extracted)} texture(s)")

    print(f"\nAll done. Output in {OUT_DIR}/")


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""
Find transparent-background badge images for {ex}, {VSTAR}, {ACE SPEC}.

Strategy:
1. Load propertybadge_en + propertybadge_shared together so cross-bundle
   sprite references resolve, then extract all Sprite objects.
2. Inspect sv9/sv9announce and tcgl bundles for ACE SPEC badge variants.
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
OUT_DIR     = Path("/tmp/transparent_badges")

def fetch_bytes(url):
    print(f"  GET {url}", file=sys.stderr)
    with urllib.request.urlopen(url) as r:
        return r.read()

def get_content_path():
    url = f"{CDN_BASE}/rainier/GameSettings/{APP_VERSION}/GameSettings.json"
    data = json.loads(fetch_bytes(url))
    return data["data"][f"{PLATFORM}_contentpath"]

def bundle_url(content_path, name, bucket=BUCKET):
    return f"{content_path}{bucket}/{name}"

def extract_sprites_multi(bundle_bytes_list: list, out_dir: Path, label: str):
    """Load multiple bundles together so cross-bundle PPtr refs resolve."""
    import UnityPy
    import tempfile, os

    out_dir.mkdir(parents=True, exist_ok=True)

    # Write all bundles to temp files and load from the directory
    with tempfile.TemporaryDirectory() as tmpdir:
        for i, data in enumerate(bundle_bytes_list):
            path = os.path.join(tmpdir, f"bundle_{i}")
            with open(path, 'wb') as f:
                f.write(data)

        env = UnityPy.load(tmpdir)

        print(f"\n  [{label}] Sprites found:")
        for obj in env.objects:
            if obj.type.name != 'Sprite':
                continue
            try:
                sprite = obj.read()
                name = getattr(sprite, 'm_Name', None) or ''
                img  = getattr(sprite, 'image', None)
                if not img:
                    continue

                # Check if image has transparency
                has_alpha = img.mode in ('RGBA', 'LA') and any(
                    px[3] < 255 for px in list(img.getdata())[:500]
                )
                out = out_dir / f"{name}.png"
                img.save(str(out))
                print(f"    {name}  {img.size}  {'TRANSPARENT' if has_alpha else 'opaque'}")
            except Exception as e:
                pass  # skip broken refs

def extract_textures_multi(bundle_bytes_list: list, wanted: set, out_dir: Path, label: str):
    import UnityPy
    import tempfile, os

    out_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmpdir:
        for i, data in enumerate(bundle_bytes_list):
            path = os.path.join(tmpdir, f"bundle_{i}")
            with open(path, 'wb') as f:
                f.write(data)

        env = UnityPy.load(tmpdir)
        print(f"\n  [{label}] Textures:")
        for obj in env.objects:
            if obj.type.name != 'Texture2D':
                continue
            try:
                tex = obj.read()
                name = getattr(tex, 'm_Name', None) or ''
                if wanted and name not in wanted:
                    continue
                img = getattr(tex, 'image', None)
                if not img:
                    continue
                has_alpha = img.mode in ('RGBA', 'LA') and any(
                    px[3] < 255 for px in list(img.getdata())[:500]
                )
                out = out_dir / f"{name}.png"
                img.save(str(out))
                print(f"    {name}  {img.size}  {'TRANSPARENT' if has_alpha else 'opaque'}")
            except Exception as e:
                pass

def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    content_path = get_content_path()

    # 1. propertybadge_en + propertybadge_shared together → extract all sprites
    print("\n[1] Loading propertybadge_en + propertybadge_shared together ...")
    pb_en     = fetch_bytes(bundle_url(content_path, "propertybadge_en"))
    pb_shared = fetch_bytes(bundle_url(content_path, "propertybadge_shared"))
    extract_sprites_multi([pb_en, pb_shared], OUT_DIR / "propertybadge_sprites", "propertybadge")

    # 2. Check sv9announce and sv9 for ACE SPEC badges
    for set_name in ["sv9", "sv9announce", "sv8-5", "sv8-5announce"]:
        print(f"\n[2] Checking {set_name} ...")
        try:
            data = fetch_bytes(bundle_url(content_path, set_name))
            extract_sprites_multi([data], OUT_DIR / set_name, set_name)
            extract_textures_multi([data], set(), OUT_DIR / set_name, set_name)
        except Exception as e:
            print(f"  SKIP: {e}")

    # 3. Check tcgl bundle (UI sprites)
    print("\n[3] Checking tcgl ...")
    try:
        tcgl_data = fetch_bytes(bundle_url(content_path, "tcgl"))
        extract_sprites_multi([tcgl_data], OUT_DIR / "tcgl", "tcgl")
    except Exception as e:
        print(f"  SKIP: {e}")

if __name__ == '__main__':
    main()

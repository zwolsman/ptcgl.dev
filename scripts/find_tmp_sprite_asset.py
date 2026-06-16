#!/usr/bin/env python3
"""
Find TMP Sprite Asset MonoBehaviour objects that define inline badge sprites.
The game maps:
  {ex}       → <sprite name="ex_lower_atk" tint=1>
  {VSTAR}    → <sprite name="vstar_atk" tint=1>
  {ACE SPEC} → <sprite name="acespec" tint=1>

A TMP Sprite Asset stores these in m_SpriteInfoList (or similar).
We need to find which bundle contains this asset, then crop the atlas texture.
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
OUT_DIR     = Path("/tmp/tmp_sprite_asset")

TARGET_SPRITES = {"ex_lower_atk", "vstar_atk", "acespec", "ex_lower", "vStar"}

def fetch_bytes(url):
    print(f"  GET {url}", file=sys.stderr)
    with urllib.request.urlopen(url) as r:
        return r.read()

def get_content_path():
    url = f"{CDN_BASE}/rainier/GameSettings/{APP_VERSION}/GameSettings.json"
    return json.loads(fetch_bytes(url))["data"][f"{PLATFORM}_contentpath"]

def bundle_url(cp, name):
    return f"{cp}{BUCKET}/{name}"

def scan_monobehaviours(bundle_bytes_list, label):
    """Scan MonoBehaviour objects for TMP Sprite Asset structure."""
    import UnityPy
    with tempfile.TemporaryDirectory() as tmpdir:
        for i, data in enumerate(bundle_bytes_list):
            with open(os.path.join(tmpdir, f"b{i}"), 'wb') as f:
                f.write(data)
        env = UnityPy.load(tmpdir)

        print(f"\n[{label}] Scanning MonoBehaviours ...")
        for obj in env.objects:
            if obj.type.name != 'MonoBehaviour':
                continue
            try:
                tree = obj.read_typetree()
                keys = list(tree.keys())

                # Check if any known TMP Sprite Asset field names are present
                tmp_keys = {'m_SpriteInfoList', 'spriteInfoList', 'spriteGlyphTable',
                            'spriteCharacterTable', 'spriteSheet', 'spriteList',
                            'm_sprites', 'sprites'}
                hits = tmp_keys & set(keys)
                if hits:
                    name = tree.get('m_Name', f"MB_{obj.path_id}")
                    print(f"  FOUND TMP-like: '{name}'  keys={keys[:15]}")

                    # Print sprite info entries
                    for field in hits:
                        entries = tree.get(field, [])
                        if isinstance(entries, list):
                            print(f"    {field}: {len(entries)} entries")
                            for e in entries[:20]:
                                if isinstance(e, dict):
                                    ename = e.get('name', e.get('m_Name', '?'))
                                    print(f"      sprite: '{ename}'  data={list(e.keys())}")
                                    if ename in TARGET_SPRITES:
                                        print(f"        *** TARGET FOUND: {ename} ***")
                                        print(f"        full entry: {json.dumps(e, default=str)[:500]}")
                else:
                    # Show MB name and keys anyway if it has a name field
                    name = tree.get('m_Name', '')
                    if name:
                        print(f"  MonoBehaviour '{name}'  keys={keys[:10]}")
            except Exception as e:
                pass

def dump_all_objects(bundle_bytes, label):
    """Print every object type/name in a bundle."""
    import UnityPy
    env = UnityPy.load(bundle_bytes)
    print(f"\n[{label}] All objects:")
    for obj in env.objects:
        try:
            item = obj.read()
            name = (getattr(item, 'm_Name', None) or
                    getattr(item, 'name', None) or '')
            print(f"  [{obj.type.name}] {name}")
        except Exception as e:
            print(f"  [{obj.type.name}] (error: {e})")

def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    cp = get_content_path()

    # Bundles most likely to contain the TMP Sprite Asset
    candidates = [
        "energyicons_t",
        "propertybadge_en",
        "propertybadge_shared",
    ]

    for name in candidates:
        print(f"\n{'='*60}\nBundle: {name}")
        data = fetch_bytes(bundle_url(cp, name))
        print(f"  {len(data):,} bytes")
        dump_all_objects(data, name)
        scan_monobehaviours([data], name)

if __name__ == '__main__':
    main()

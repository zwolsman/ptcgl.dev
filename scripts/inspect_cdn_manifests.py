#!/usr/bin/env python3
"""
Fetch CDN bucket manifests and list ALL asset names (no filtering).

Usage:
  python3 scripts/inspect_cdn_manifests.py [--bucket 10101_0000]

CDN is public — no auth required.
GameSettings.json supplies the content path per platform.
"""

import sys
import json
import struct
import io
import urllib.request
import argparse
import re
from collections import defaultdict

CDN_BASE = "https://cdn.studio-prod.pokemon.com"
APP_VERSION = "1.39.0"
PLATFORM = "osxplayer"
LOCALE = "en"

CARD_RE = re.compile(r'^[a-z0-9]+-?[a-z0-9]*_[a-z]{2}_\d+(_t)?$')
EXPANSION_RE = re.compile(r'^expansion_[a-z0-9]+_[a-z]{2}$')

def fetch_bytes(url: str) -> bytes:
    print(f"  GET {url}", file=sys.stderr)
    with urllib.request.urlopen(url) as r:
        return r.read()


def get_content_path() -> str:
    url = f"{CDN_BASE}/rainier/GameSettings/{APP_VERSION}/GameSettings.json"
    data = json.loads(fetch_bytes(url))
    key = f"{PLATFORM}_contentpath"
    path = data["data"][key]
    print(f"Content path: {path}", file=sys.stderr)
    return path


def get_buckets(content_path: str) -> list[str]:
    """Fetch the asset-bundle-manifest to get bucket list (requires studio auth).
    Fallback: use known buckets from DB."""
    # Known buckets from asset_object table in DB
    return [
        "10101_0000",
        "20260604_1700",
        "20260521_1700",
        "20260430_1700",
        "20260423_1700",
        "20260326_1700",
        "20260319_1700",
    ]


def read_uint32_be(data: bytes, offset: int) -> int:
    return struct.unpack_from('>I', data, offset)[0]


def read_uint32_le(data: bytes, offset: int) -> int:
    return struct.unpack_from('<I', data, offset)[0]


def read_cstring(data: bytes, offset: int) -> tuple[str, int]:
    end = data.index(b'\x00', offset)
    return data[offset:end].decode('utf-8', errors='replace'), end + 1


def parse_unity_bundle_manifest(data: bytes) -> list[dict]:
    """
    Parse a Unity AssetBundle manifest using UnityPy.
    Returns list of dicts with assetName, crc, hash, s3Folder, dependencies.
    """
    import UnityPy
    env = UnityPy.load(data)

    results = []
    for obj in env.objects:
        if obj.type.name == 'MonoBehaviour':
            try:
                tree = obj.read_typetree()
                asset_list = tree.get('assetList') or tree.get('m_assetList')
                if asset_list is None:
                    # Try to find it anywhere in the tree
                    for key, val in tree.items():
                        if isinstance(val, list) and len(val) > 0 and isinstance(val[0], dict):
                            if 'assetName' in val[0]:
                                asset_list = val
                                break
                if asset_list:
                    for entry in asset_list:
                        results.append({
                            'assetName': entry.get('assetName', ''),
                            'crc': entry.get('crc', 0),
                            'hash': entry.get('hash', ''),
                            's3Folder': entry.get('s3Folder', ''),
                            'dependencies': entry.get('dependencies', []),
                        })
            except Exception as e:
                print(f"    Warning: could not read typetree: {e}", file=sys.stderr)

    return results


def classify_asset(name: str) -> str:
    if CARD_RE.match(name):
        return 'card'
    if EXPANSION_RE.match(name):
        return 'expansion'
    return 'other'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bucket', help='Only inspect this bucket (default: all known buckets)')
    parser.add_argument('--other-only', action='store_true', help='Only print non-card/non-expansion assets')
    parser.add_argument('--deps', action='store_true', help='Also print dependencies for each asset')
    args = parser.parse_args()

    content_path = get_content_path()
    buckets = [args.bucket] if args.bucket else get_buckets(content_path)

    all_entries: list[dict] = []
    counts_by_bucket: dict[str, dict] = {}

    for bucket in buckets:
        url = f"{content_path}{bucket}/manifest_{LOCALE}_{bucket}"
        print(f"\nBucket: {bucket}", file=sys.stderr)
        try:
            bundle_bytes = fetch_bytes(url)
        except Exception as e:
            print(f"  SKIP: {e}", file=sys.stderr)
            continue

        entries = parse_unity_bundle_manifest(bundle_bytes)
        print(f"  {len(entries)} entries", file=sys.stderr)

        classified = defaultdict(int)
        for e in entries:
            e['bucket'] = bucket
            all_entries.append(e)
            classified[classify_asset(e['assetName'])] += 1

        counts_by_bucket[bucket] = dict(classified)

    print("\n" + "="*70)
    print("SUMMARY BY BUCKET")
    print("="*70)
    for bucket, counts in counts_by_bucket.items():
        total = sum(counts.values())
        print(f"  {bucket}: {total} total — card={counts.get('card',0)}, "
              f"expansion={counts.get('expansion',0)}, other={counts.get('other',0)}")

    other = [e for e in all_entries if classify_asset(e['assetName']) == 'other']
    print(f"\n{'='*70}")
    print(f"NON-CARD/NON-EXPANSION ASSETS ({len(other)} total)")
    print("="*70)

    # Group by prefix (first segment before _)
    by_prefix: dict[str, list] = defaultdict(list)
    for e in other:
        prefix = e['assetName'].split('_')[0] if '_' in e['assetName'] else e['assetName'][:20]
        by_prefix[prefix].append(e)

    for prefix in sorted(by_prefix.keys()):
        entries = by_prefix[prefix]
        print(f"\n[{prefix}] — {len(entries)} assets")
        for e in sorted(entries, key=lambda x: x['assetName'])[:20]:
            deps = f"  deps={e['dependencies']}" if args.deps and e['dependencies'] else ""
            print(f"  {e['assetName']}  (bucket={e['bucket']}){deps}")
        if len(entries) > 20:
            print(f"  ... and {len(entries)-20} more")

    print(f"\n{'='*70}")
    print(f"ALL UNIQUE OTHER ASSET NAMES (sorted)")
    print("="*70)
    for e in sorted(other, key=lambda x: x['assetName']):
        if not args.other_only or classify_asset(e['assetName']) == 'other':
            deps = f"  → deps: {', '.join(e['dependencies'][:5])}" if args.deps and e['dependencies'] else ""
            print(f"  {e['assetName']}{deps}")


if __name__ == '__main__':
    main()

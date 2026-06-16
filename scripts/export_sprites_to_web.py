#!/usr/bin/env python3
"""
One-time extraction of energy icons and property badges from the PTCGL CDN.
Outputs to web/public/sprites/{energy,badges}/.

Run once: python3 scripts/export_sprites_to_web.py
"""

import sys
import json
import urllib.request
from pathlib import Path

CDN_BASE = "https://cdn.studio-prod.pokemon.com"
APP_VERSION = "1.39.0"
PLATFORM = "osxplayer"
BUCKET = "10101_0000"
LOCALE = "en"

SCRIPT_DIR = Path(__file__).parent
REPO_ROOT = SCRIPT_DIR.parent
OUT_ENERGY         = REPO_ROOT / "web/public/sprites/energy"
OUT_ENERGY_OUTLINE = REPO_ROOT / "web/public/sprites/energy-outline"
OUT_BADGES         = REPO_ROOT / "web/public/sprites/badges"

# Energy type names from TMP sprite tags → texture name in energyicons_t
ENERGY_MAP = {
    "colorless": "Colorless_sm",
    "fire":      "Fire_sm",
    "grass":     "Grass_sm",
    "water":     "Water_sm",
    "lightning": "Lightning_sm",
    "psychic":   "Psychic_sm",
    "fighting":  "Fighting_sm",
    "darkness":  "Dark_sm",
    "metal":     "Metal_sm",
    "dragon":    "Dragon_sm",
    "fairy":     "Fairy_sm",
}

# Uncolored (white silhouette) variants of the energy icons.
# Note: these are white-on-transparent — only visible on dark backgrounds.
ENERGY_OUTLINE_MAP = {
    "colorless": "Colorless_sm_Empty",
    "fire":      "Fire_sm_Empty",
    "grass":     "Grass_sm_Empty",
    "water":     "Water_sm_Empty",
    "lightning": "Lightning_sm_Empty",
    "psychic":   "Psychic_sm_Empty",
    "fighting":  "Fighting_sm_Empty",
    "darkness":  "Dark_sm_Empty",
    "metal":     "Metal_sm_Empty",
    "dragon":    "Dragon_sm_Empty",
    "fairy":     "Fairy_sm_Empty",
}

# Property badge textures to extract (Texture2D) → output filename
BADGE_TEXTURE_MAP = {
    "tag_ex_Lower":          "ex.png",
    "tag_VStar":             "vstar.png",
    "tag_Vmax":              "vmax.png",
    "tag_V_standard":        "v.png",
    "tag_EX":                "EX.png",
    "tag_Mega":              "mega.png",
    "tag_prism_star":        "prism-star.png",
    "Tag_Ability_EN":        "ability.png",
    "Tag_Ability_SV_EN":     "ability-sv.png",
    "Tag_Ability_SWSH_EN":   "ability-swsh.png",
    "atkOV_AbilityTag_On_EN":  "ability-on.png",
    "atkOV_AbilityTag_Off_EN": "ability-off.png",
    "tag_Break_EN":           "break.png",
    "Tag_TagTeam_EN":         "tag-team.png",
    "tag_GX_tagteam":         "gx-tag-team.png",
    "Tag_UltraBeast_EN":      "ultra-beast.png",
    "Tag_FusionStrike_EN":    "fusion-strike.png",
    "tag_single_strike_EN":   "single-strike.png",
    "tag_rapid_strike_EN":    "rapid-strike.png",
    "tag_VUnion":             "v-union.png",
    "teraFrameTexture_EN":    "tera.png",
}

# Sprites extracted individually from atlases in propertybadge_en
BADGE_SPRITE_MAP = {
    "atkOV_AbilityTag_On_EN":  "ability-pill.png",
    "atkOV_AbilityTag_Off_EN": "ability-pill-off.png",
    "tag_trainer_full_EN":     "trainer-full.png",
    "tag_trainer_standard_EN": "trainer-standard.png",
    "tag_stageB_EN":           "stage-basic.png",
    "tag_stage1_EN":           "stage-1.png",
    "tag_stage2_EN":           "stage-2.png",
    "bio_VStar_On_EN":         "vstar-bio.png",
}

def fetch_bytes(url: str) -> bytes:
    print(f"  GET {url}", file=sys.stderr)
    with urllib.request.urlopen(url) as r:
        return r.read()

def get_content_path() -> str:
    url = f"{CDN_BASE}/rainier/GameSettings/{APP_VERSION}/GameSettings.json"
    data = json.loads(fetch_bytes(url))
    return data["data"][f"{PLATFORM}_contentpath"]

def extract_named_textures(bundle_bytes: bytes, wanted: set) -> dict:
    """Returns {m_Name: PIL.Image} for each Texture2D whose name is in `wanted`."""
    import UnityPy
    env = UnityPy.load(bundle_bytes)
    found = {}
    for obj in env.objects:
        if obj.type.name == 'Texture2D':
            tex = obj.read()
            name = getattr(tex, 'm_Name', None) or getattr(tex, 'name', None) or ''
            if name in wanted:
                img = getattr(tex, 'image', None)
                if img:
                    found[name] = img
    return found

def extract_named_sprites(bundle_bytes: bytes, wanted: set) -> dict:
    """Returns {m_Name: PIL.Image} for each Sprite whose name is in `wanted`."""
    import UnityPy
    env = UnityPy.load(bundle_bytes)
    found = {}
    for obj in env.objects:
        if obj.type.name == 'Sprite':
            try:
                sprite = obj.read()
                name = getattr(sprite, 'm_Name', None) or getattr(sprite, 'name', None) or ''
                if name in wanted:
                    img = getattr(sprite, 'image', None)
                    if img:
                        found[name] = img
            except Exception:
                pass
    return found

def main():
    for d in (OUT_ENERGY, OUT_ENERGY_OUTLINE, OUT_BADGES):
        d.mkdir(parents=True, exist_ok=True)

    content_path = get_content_path()
    print(f"Content path: {content_path}")

    # --- Energy icons (colored) ---
    print("\n[1/3] Downloading energyicons_t ...")
    energy_bytes = fetch_bytes(f"{content_path}{BUCKET}/energyicons_t")
    print(f"  {len(energy_bytes):,} bytes")

    all_energy_names = set(ENERGY_MAP.values()) | set(ENERGY_OUTLINE_MAP.values())
    energy_textures = extract_named_textures(energy_bytes, all_energy_names)

    for sprite_name, tex_name in ENERGY_MAP.items():
        img = energy_textures.get(tex_name)
        if img:
            out = OUT_ENERGY / f"{sprite_name}.png"
            img.save(str(out))
            print(f"  energy/{sprite_name}.png  ({tex_name})")
        else:
            print(f"  MISSING: {tex_name}", file=sys.stderr)

    for sprite_name, tex_name in ENERGY_OUTLINE_MAP.items():
        img = energy_textures.get(tex_name)
        if img:
            out = OUT_ENERGY_OUTLINE / f"{sprite_name}.png"
            img.save(str(out))
            print(f"  energy-outline/{sprite_name}.png  ({tex_name})")
        else:
            print(f"  MISSING outline: {tex_name}", file=sys.stderr)

    # --- Inline placeholder badges ---
    # Only 3 placeholders appear in card text: {ex}, {VSTAR}, {ACE SPEC}.
    # tag_ex_Lower and tag_VStar live in propertybadge_en.
    # Process shared first so EN overwrites it (shared has a yellow tint layer for tag_ex_Lower).
    print("\n[2/3] Downloading propertybadge badges ...")
    INLINE_BADGE_MAP = {
        "tag_ex_Lower": "ex.png",
        "tag_VStar":    "vstar.png",
    }
    all_badge_textures = {}
    for bundle_name in ["propertybadge_shared", "propertybadge_en"]:
        badge_bytes = fetch_bytes(f"{content_path}{BUCKET}/{bundle_name}")
        print(f"  {bundle_name}: {len(badge_bytes):,} bytes")
        all_badge_textures.update(extract_named_textures(badge_bytes, set(INLINE_BADGE_MAP)))

    for tex_name, out_name in INLINE_BADGE_MAP.items():
        img = all_badge_textures.get(tex_name)
        if img:
            img.save(str(OUT_BADGES / out_name))
            print(f"  badges/{out_name}  ({tex_name})")
        else:
            print(f"  MISSING texture: {tex_name}", file=sys.stderr)

    # --- ACE SPEC ---
    print("\n[3/3] Downloading board_ace_spec ...")
    ace_bytes = fetch_bytes(f"{content_path}{BUCKET}/board_ace_spec")
    print(f"  {len(ace_bytes):,} bytes")
    ace_textures = extract_named_textures(ace_bytes, {"T_VFX_AceSpec_Title"})
    img = ace_textures.get("T_VFX_AceSpec_Title")
    if img:
        img.save(str(OUT_BADGES / "ace-spec.png"))
        print(f"  badges/ace-spec.png  (T_VFX_AceSpec_Title)")
    else:
        print(f"  MISSING: T_VFX_AceSpec_Title", file=sys.stderr)

    print(f"\nDone.")
    print(f"  Energy icons (colored)  → {OUT_ENERGY}/")
    print(f"  Energy icons (outline)  → {OUT_ENERGY_OUTLINE}/")
    print(f"  Property badges         → {OUT_BADGES}/")

if __name__ == '__main__':
    main()

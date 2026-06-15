CREATE TABLE material_manifest (
    bundle_asset_name text NOT NULL,
    variant_suffix    text NOT NULL DEFAULT '',
    whiteplate_name   text,
    etch_name         text,
    foil_type         text,
    shader_path       text,
    PRIMARY KEY (bundle_asset_name, variant_suffix)
);

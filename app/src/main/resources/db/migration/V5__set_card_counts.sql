-- Remove the set_compendium table introduced in V4; card counts come from set-manifest instead.
DROP TABLE IF EXISTS set_compendium;

-- Remove stale config_revision entries for the per-set compendium docs.
DELETE FROM config_revision WHERE doc_id LIKE '%-compendium_%';

-- Add card count columns directly to set, sourced from setDetails in set-manifest_0.0.
-- main_set_count  = MainSetCount  (numbered cards in the main expansion)
-- master_set_count = MasterSetCount (full collectible set including secret rares)
ALTER TABLE "set"
    ADD COLUMN main_set_count   int,
    ADD COLUMN master_set_count int;

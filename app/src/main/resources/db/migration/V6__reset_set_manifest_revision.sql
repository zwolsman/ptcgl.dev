-- Force set-manifest_0.0 to be re-processed on next plan run so the
-- newly-added main_set_count and master_set_count columns get populated.
DELETE FROM config_revision WHERE doc_id = 'set-manifest_0.0';

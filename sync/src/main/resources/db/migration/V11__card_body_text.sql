-- Add body text column to card_localization for trainer/energy/ability card effects.
ALTER TABLE card_localization ADD COLUMN text TEXT;

-- HP of 0 in the DataTable means "no HP" (trainers/energy). Normalise to NULL.
UPDATE card SET hp = NULL WHERE hp = 0;

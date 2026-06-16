-- Combat stats on card (all locale-invariant: single-letter type codes, numeric values)
ALTER TABLE card
    ADD COLUMN retreat            int,
    ADD COLUMN weakness_type      text,
    ADD COLUMN weakness_amount    text,
    ADD COLUMN resistance_type    text,
    ADD COLUMN resistance_amount  text,
    ADD COLUMN category           smallint;   -- 1=Pokémon, 2=Trainer, 3=Energy (System.Byte in DataTable)

-- Locale-invariant per-attack data: cost and damage are energy codes / numbers, stable across locales.
CREATE TABLE card_attack (
    card_id      text  NOT NULL REFERENCES card (id),
    slot         int   NOT NULL,   -- 1-based position on card (1..4)
    cost         text,             -- energy cost string e.g. "CC", "GGG"; null when slot unused
    damage       text,             -- damage value e.g. "10", "120+"; null for effect-only attacks
    attack_type  int,              -- attackType enum value from DataTable (nullable)
    attack_id    int,              -- attackID hash from DataTable (signed Int32, nullable)
    PRIMARY KEY (card_id, slot)
);

-- Locale-specific attack name and rules text.
-- Upsert card_attack rows before card_attack_localization rows (FK constraint).
CREATE TABLE card_attack_localization (
    card_id  text  NOT NULL,
    slot     int   NOT NULL,
    locale   text  NOT NULL,
    name     text  NOT NULL,
    text     text,                 -- rules text; null for attacks with no effect description
    PRIMARY KEY (card_id, slot, locale),
    FOREIGN KEY (card_id, slot) REFERENCES card_attack (card_id, slot)
);

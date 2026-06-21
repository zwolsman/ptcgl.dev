CREATE TABLE series_localization (
    series_id    text NOT NULL,
    locale       text NOT NULL,
    name         text NOT NULL,
    PRIMARY KEY (series_id, locale)
);

CREATE TABLE rarity_localization (
    code         text NOT NULL,
    locale       text NOT NULL,
    display_name text NOT NULL,
    PRIMARY KEY (code, locale)
);

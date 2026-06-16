ALTER TABLE auth_state
    ADD COLUMN access_token             text,
    ADD COLUMN access_token_expires_at  timestamptz;

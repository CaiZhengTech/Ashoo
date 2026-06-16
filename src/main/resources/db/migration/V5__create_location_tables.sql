-- Pre-registered named locations the engine polls hourly (Home, Work, etc.)
-- The engine fetches environmental data for every active saved location every hour.
CREATE TABLE saved_location (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',
    label       VARCHAR(128) NOT NULL,          -- User-given name: "Home", "Work", etc.
    city_name   VARCHAR(255) NOT NULL,
    county      VARCHAR(255),
    country     VARCHAR(128) NOT NULL DEFAULT 'US',
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    is_primary  BOOLEAN      NOT NULL DEFAULT FALSE,  -- Primary location drives the daily briefing
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Ad-hoc location queries are stored here for quick re-access (last 10, rolling).
-- These are NOT permanent saves — just a convenience history.
-- The rolling limit (last 10) is enforced at the application layer on insert.
CREATE TABLE recent_search (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',
    city_name   VARCHAR(255) NOT NULL,
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    searched_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

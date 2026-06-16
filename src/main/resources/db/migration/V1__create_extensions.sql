-- Enable the TimescaleDB extension.
-- Must run before any hypertable is created.
-- CASCADE ensures any objects that depend on TimescaleDB are also enabled.
CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;

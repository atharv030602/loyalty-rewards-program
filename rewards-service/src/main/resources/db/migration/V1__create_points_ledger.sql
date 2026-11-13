-- The points ledger: one row per points movement, never updated or deleted.
-- A member's balance is simply SUM(points) over their rows - like a bank statement.
--
-- Flyway runs this file once, records it in the table "flyway_schema_history",
-- and never runs it again. To change the schema later, add V2__..., never edit V1.

CREATE TABLE points_ledger (
    id          BIGSERIAL    PRIMARY KEY,

    -- Id of the Kafka event that caused this row. NULL for rows that do not come
    -- from an event (redemptions, added in a later phase).
    event_id    UUID,

    member_id   VARCHAR(64)  NOT NULL,
    order_id    VARCHAR(64),

    -- EARN (points added) or REDEEM (points spent)
    entry_type  VARCHAR(16)  NOT NULL,

    -- Positive for EARN, negative for REDEEM
    points      BIGINT       NOT NULL,

    description VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL,

    -- THE idempotency guarantee: the database itself refuses a second row for the same
    -- event, even if two consumer threads try at the same moment.
    -- (PostgreSQL allows many NULLs in a UNIQUE column, so redemption rows are fine.)
    CONSTRAINT uq_points_ledger_event_id UNIQUE (event_id)
);

-- Balance and history are always looked up by member.
CREATE INDEX idx_points_ledger_member_id ON points_ledger (member_id);

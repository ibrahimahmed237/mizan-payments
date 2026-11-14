-- Settlement files from gateways, their lines, and the discrepancies matching finds.

CREATE TABLE settlement_files (
  id           uuid        PRIMARY KEY,
  seq          bigint      GENERATED ALWAYS AS IDENTITY,
  gateway      text        NOT NULL CHECK (gateway IN ('CARD_GATEWAY', 'PAY_LATER')),
  payout_id    text        NOT NULL,
  payout_date  date        NOT NULL,
  total_net    bigint      NOT NULL,
  line_count   integer     NOT NULL CHECK (line_count >= 0),
  sha256       text        NOT NULL,   -- the same payout again is answered as the first time only with the same content
  created_at   timestamptz NOT NULL,
  CONSTRAINT settlement_files_once UNIQUE (gateway, payout_id)
);

CREATE TABLE settlement_lines (
  id                  uuid        PRIMARY KEY,
  seq                 bigint      GENERATED ALWAYS AS IDENTITY,   -- file order, across files
  file_id             uuid        NOT NULL REFERENCES settlement_files (id),
  line_number         integer     NOT NULL CHECK (line_number >= 1),
  gateway             text        NOT NULL,
  transaction_id      text        NOT NULL,
  gateway_payment_id  text        NOT NULL,
  type                text        NOT NULL CHECK (type IN ('CAPTURE', 'REFUND', 'OTHER')),
  type_text           text        NOT NULL,
  gross               bigint      NOT NULL,
  fee                 bigint      NOT NULL,
  net                 bigint      NOT NULL,
  occurred_at         timestamptz NOT NULL,
  status              text        NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'NEEDS_SYNC', 'MATCHED', 'SUSPENSE', 'ACCEPTED')),
  payment_id          uuid        REFERENCES payments (id),
  synced              boolean     NOT NULL DEFAULT false,   -- its payment was synced after the line first did not fit
  next_attempt_at     timestamptz,                          -- when the matching worker takes it; null once it is handled
  handled_at          timestamptz,
  CONSTRAINT settlement_lines_net CHECK (net = gross - fee),
  CONSTRAINT settlement_lines_place UNIQUE (file_id, line_number),
  CONSTRAINT settlement_lines_waiting CHECK ((status IN ('NEW', 'NEEDS_SYNC')) = (next_attempt_at IS NOT NULL))
);

CREATE INDEX settlement_lines_transaction ON settlement_lines (gateway, transaction_id, seq);
CREATE INDEX settlement_lines_due ON settlement_lines (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE INDEX settlement_lines_of_payment ON settlement_lines (payment_id);
CREATE INDEX settlement_lines_of_file ON settlement_lines (file_id, line_number);

-- The order in which discrepancies were opened: many can open in the same instant.
ALTER TABLE discrepancies ADD COLUMN seq bigint GENERATED ALWAYS AS IDENTITY;

-- A discrepancy may be about a line. Then it holds the line's net amount in suspense until it is closed.
ALTER TABLE discrepancies ADD COLUMN line_id uuid REFERENCES settlement_lines (id);
ALTER TABLE discrepancies ADD COLUMN suspense_amount bigint NOT NULL DEFAULT 0;

-- One open discrepancy per line.
CREATE UNIQUE INDEX discrepancies_open_per_line ON discrepancies (line_id) WHERE status = 'OPEN' AND line_id IS NOT NULL;

-- One open discrepancy of a type per payment, or per unknown gateway payment: now only for those without a line.
-- Two lines of one payment can each be wrong in the same way.
DROP INDEX discrepancies_open_per_payment;
CREATE UNIQUE INDEX discrepancies_open_per_payment ON discrepancies (payment_id, type) WHERE status = 'OPEN' AND line_id IS NULL;
DROP INDEX discrepancies_open_per_gateway_payment;
CREATE UNIQUE INDEX discrepancies_open_per_gateway_payment ON discrepancies (gateway, gateway_payment_id, type)
  WHERE status = 'OPEN' AND payment_id IS NULL AND line_id IS NULL;
CREATE INDEX discrepancies_of_payment ON discrepancies (payment_id);

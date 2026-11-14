-- Payments, operations, idempotency keys, the ledger, discrepancies and the outbox.
-- Money is bigint halalas. Times are timestamptz (UTC). Ids are uuid, made by Mizan.
-- The checks below are the last line: the code checks the same rules first and gives a clear error.

CREATE TABLE api_keys (
  id          uuid        PRIMARY KEY,
  name        text        NOT NULL,
  key_hash    text        NOT NULL UNIQUE,   -- hex SHA-256 of the key; the key itself is never stored
  scopes      text        NOT NULL,          -- comma list: payments, backoffice
  created_at  timestamptz NOT NULL,
  revoked_at  timestamptz
);

CREATE TABLE payments (
  id                  uuid        PRIMARY KEY,
  reference           text        NOT NULL,   -- the store's order
  amount              bigint      NOT NULL CHECK (amount > 0),
  currency            text        NOT NULL,
  method              text        NOT NULL CHECK (method IN ('CARD', 'APPLE_PAY', 'PAY_LATER')),
  gateway             text        NOT NULL CHECK (gateway IN ('CARD_GATEWAY', 'PAY_LATER')),
  capture_mode        text        NOT NULL CHECK (capture_mode IN ('AUTO', 'MANUAL')),
  status              text        NOT NULL CHECK (status IN ('PENDING', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'CANCELED', 'EXPIRED')),
  captured_amount     bigint      NOT NULL DEFAULT 0,
  refunded_amount     bigint      NOT NULL DEFAULT 0,
  settled_captured    bigint      NOT NULL DEFAULT 0,
  settled_refunded    bigint      NOT NULL DEFAULT 0,
  gateway_payment_id  text,
  gateway_status      text,
  action_url          text,
  return_url          text,
  description         text,
  failure_code        text,
  failure_message     text,
  scheme              text,
  last4               text,
  late                boolean     NOT NULL DEFAULT false,
  follow_up           text        CHECK (follow_up IN ('AUTO_CAPTURE', 'AUTO_VOID', 'AUTO_REFUND')),
  follow_ups_started  text        NOT NULL DEFAULT '',   -- comma list of follow-ups already started
  expires_at          timestamptz,
  hold_ends_at        timestamptz,
  captured_at         timestamptz,
  last_refund_at      timestamptz,
  next_sync_at        timestamptz,            -- when the sync worker looks next; null: never
  last_synced_at      timestamptz,
  sync_failures       integer     NOT NULL DEFAULT 0,
  version             integer     NOT NULL DEFAULT 1,
  created_at          timestamptz NOT NULL,
  updated_at          timestamptz NOT NULL,
  CONSTRAINT payments_limits CHECK (0 <= refunded_amount AND refunded_amount <= captured_amount AND captured_amount <= amount),
  CONSTRAINT payments_settled CHECK (
    0 <= settled_captured AND settled_captured <= captured_amount
    AND 0 <= settled_refunded AND settled_refunded <= refunded_amount),
  CONSTRAINT payments_captured_has_money CHECK ((status = 'CAPTURED') = (captured_amount > 0))
);

CREATE UNIQUE INDEX payments_gateway_payment ON payments (gateway, gateway_payment_id);
CREATE INDEX payments_reference ON payments (reference);
CREATE INDEX payments_newest ON payments (created_at DESC, id DESC);
CREATE INDEX payments_due ON payments (next_sync_at) WHERE next_sync_at IS NOT NULL;

CREATE TABLE operations (
  id               uuid        PRIMARY KEY,
  seq              bigint      GENERATED ALWAYS AS IDENTITY,   -- the order in which operations were written
  payment_id       uuid        NOT NULL REFERENCES payments (id),
  type             text        NOT NULL CHECK (type IN ('CREATE', 'CAPTURE', 'CANCEL', 'REFUND')),
  status           text        NOT NULL CHECK (status IN ('IN_FLIGHT', 'SUCCEEDED', 'FAILED')),
  origin           text        NOT NULL CHECK (origin IN ('API', 'SYSTEM', 'GATEWAY')),
  amount           bigint      NOT NULL DEFAULT 0 CHECK (amount >= 0),
  attempts         integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),   -- calls sent; written before each call
  last_attempt_at  timestamptz,
  result_code      text,
  reason           text,
  created_at       timestamptz NOT NULL,
  finished_at      timestamptz,
  CONSTRAINT operations_in_flight_was_sent CHECK (status <> 'IN_FLIGHT' OR (attempts >= 1 AND last_attempt_at IS NOT NULL)),
  CONSTRAINT operations_finished CHECK ((status = 'IN_FLIGHT') = (finished_at IS NULL))
);

-- Rule R4: one operation in flight per payment.
CREATE UNIQUE INDEX operations_one_in_flight ON operations (payment_id) WHERE status = 'IN_FLIGHT';
CREATE INDEX operations_of_payment ON operations (payment_id, seq);

CREATE TABLE idempotency_keys (
  key           text        PRIMARY KEY,
  request_hash  text        NOT NULL,
  -- The key is saved first, to stop a second request with the same key; what it points to is written right after,
  -- in the same transaction. So these two are checked at commit.
  payment_id    uuid        REFERENCES payments (id) DEFERRABLE INITIALLY DEFERRED,
  operation_id  uuid        REFERENCES operations (id) DEFERRABLE INITIALLY DEFERRED,
  created_at    timestamptz NOT NULL,
  expires_at    timestamptz NOT NULL,
  CONSTRAINT idempotency_keys_one_target CHECK ((payment_id IS NULL) <> (operation_id IS NULL))
);

CREATE INDEX idempotency_keys_expiry ON idempotency_keys (expires_at);

-- The ledger. Double entry, append-only.

CREATE TABLE ledger_entries (
  id          uuid        PRIMARY KEY,
  seq         bigint      GENERATED ALWAYS AS IDENTITY,   -- the order in which entries were written
  kind        text        NOT NULL,
  dedupe_key  text        NOT NULL UNIQUE,    -- a money fact is written once
  payment_id  uuid        REFERENCES payments (id),
  created_at  timestamptz NOT NULL,
  txid        bigint      NOT NULL DEFAULT txid_current()
);

CREATE INDEX ledger_entries_of_payment ON ledger_entries (payment_id, seq);

CREATE TABLE ledger_postings (
  id        bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  entry_id  uuid    NOT NULL REFERENCES ledger_entries (id),
  account   text    NOT NULL CHECK (account IN ('gateway_receivable', 'bank', 'gateway_fees', 'sales', 'suspense', 'adjustments')),
  side      text    NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
  amount    bigint  NOT NULL CHECK (amount > 0)
);

CREATE INDEX ledger_postings_of_entry ON ledger_postings (entry_id);
CREATE INDEX ledger_postings_of_account ON ledger_postings (account);

-- L3: entries and postings are never changed or deleted.
CREATE FUNCTION ledger_is_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'the ledger is append-only: % on % is refused', TG_OP, TG_TABLE_NAME USING ERRCODE = '23514';
END $$;

CREATE TRIGGER ledger_entries_append_only BEFORE UPDATE OR DELETE ON ledger_entries
  FOR EACH ROW EXECUTE FUNCTION ledger_is_append_only();
CREATE TRIGGER ledger_postings_append_only BEFORE UPDATE OR DELETE ON ledger_postings
  FOR EACH ROW EXECUTE FUNCTION ledger_is_append_only();
-- Emptying ledger_entries means emptying ledger_postings too (its foreign key), and that is refused here.
CREATE TRIGGER ledger_postings_no_truncate BEFORE TRUNCATE ON ledger_postings
  FOR EACH STATEMENT EXECUTE FUNCTION ledger_is_append_only();

-- A posting joins its entry only in the transaction that wrote the entry. Later, an entry is closed.
CREATE FUNCTION ledger_posting_joins_new_entry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (SELECT txid FROM ledger_entries WHERE id = NEW.entry_id) IS DISTINCT FROM txid_current() THEN
    RAISE EXCEPTION 'ledger entry % is closed: a posting is written with its entry', NEW.entry_id USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER ledger_postings_join_new_entry BEFORE INSERT ON ledger_postings
  FOR EACH ROW EXECUTE FUNCTION ledger_posting_joins_new_entry();

-- L1: an entry's debits equal its credits. Checked at commit, when all its postings are written.
CREATE FUNCTION ledger_check_balance(entry uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  debit  bigint;
  credit bigint;
BEGIN
  SELECT coalesce(sum(amount) FILTER (WHERE side = 'DEBIT'), 0), coalesce(sum(amount) FILTER (WHERE side = 'CREDIT'), 0)
    INTO debit, credit
    FROM ledger_postings WHERE entry_id = entry;
  IF debit = 0 OR debit <> credit THEN
    RAISE EXCEPTION 'ledger entry % does not balance: debit %, credit %', entry, debit, credit USING ERRCODE = '23514';
  END IF;
END $$;

CREATE FUNCTION ledger_entry_balances() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM ledger_check_balance(NEW.id);
  RETURN NULL;
END $$;

CREATE FUNCTION ledger_posting_balances() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM ledger_check_balance(NEW.entry_id);
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER ledger_entries_balance AFTER INSERT ON ledger_entries
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_entry_balances();
CREATE CONSTRAINT TRIGGER ledger_postings_balance AFTER INSERT ON ledger_postings
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_posting_balances();

-- Differences a person must look at. Reconciliation adds its own columns later.

CREATE TABLE discrepancies (
  id           uuid        PRIMARY KEY,
  type         text        NOT NULL,
  status       text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
  payment_id   uuid        REFERENCES payments (id),
  expected     bigint,
  actual       bigint,
  resolution   text        CHECK (resolution IN ('REMATCHED', 'SETTLED_LATER', 'ACCEPTED')),
  note         text,
  created_at   timestamptz NOT NULL,
  resolved_at  timestamptz,
  CONSTRAINT discrepancies_resolved CHECK ((status = 'RESOLVED') = (resolution IS NOT NULL))
);

-- One open discrepancy of a type per payment.
CREATE UNIQUE INDEX discrepancies_open_per_payment ON discrepancies (payment_id, type) WHERE status = 'OPEN';
CREATE INDEX discrepancies_by_status ON discrepancies (status, created_at);

-- Events for the store, written in the same transaction as the change they tell (the outbox).

CREATE TABLE outbox_events (
  id               uuid        PRIMARY KEY,
  seq              bigint      GENERATED ALWAYS AS IDENTITY,   -- the order in which events were written
  type             text        NOT NULL,
  payment_id       uuid        REFERENCES payments (id),
  payload          text        NOT NULL,     -- the JSON body, exactly as it will be sent
  status           text        NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING', 'DELIVERED', 'FAILED', 'SKIPPED')),
  attempts         integer     NOT NULL DEFAULT 0,
  next_attempt_at  timestamptz,
  last_error       text,
  created_at       timestamptz NOT NULL,
  delivered_at     timestamptz
);

CREATE INDEX outbox_events_due ON outbox_events (next_attempt_at) WHERE status = 'WAITING';
CREATE INDEX outbox_events_of_payment ON outbox_events (payment_id, seq);

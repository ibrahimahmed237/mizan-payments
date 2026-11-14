-- Webhooks from gateways, and discrepancies about a payment that Mizan does not have.

-- A webhook is only a hint to look at the gateway (rule R2). So only its id, its type and the payment it is about
-- are kept: enough to skip a repeat and to see later what came in. The body is not kept.
CREATE TABLE gateway_events (
  id                  bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  gateway             text        NOT NULL CHECK (gateway IN ('CARD_GATEWAY', 'PAY_LATER')),
  event_id            text        NOT NULL,
  type                text        NOT NULL,
  gateway_payment_id  text        NOT NULL,
  payment_id          uuid        REFERENCES payments (id),   -- null: no payment of Mizan's matched
  received_at         timestamptz NOT NULL,
  CONSTRAINT gateway_events_once UNIQUE (gateway, event_id)
);

CREATE INDEX gateway_events_of_payment ON gateway_events (payment_id);
CREATE INDEX gateway_events_received ON gateway_events (received_at);

-- A request to look at a payment, from a webhook for example. It stays up until a worker takes the payment for a
-- look, so a request that comes while another look is on its way to the gateway is not lost.
ALTER TABLE payments ADD COLUMN sync_requested boolean NOT NULL DEFAULT false;

-- A discrepancy may be about a payment at a gateway that Mizan has no payment for.
ALTER TABLE discrepancies ADD COLUMN gateway text;
ALTER TABLE discrepancies ADD COLUMN gateway_payment_id text;

-- One open discrepancy of a type per unknown gateway payment.
CREATE UNIQUE INDEX discrepancies_open_per_gateway_payment ON discrepancies (gateway, gateway_payment_id, type)
  WHERE status = 'OPEN' AND payment_id IS NULL;

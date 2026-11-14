-- Payments with money that no settlement file has settled yet. The daily check and the reconciliation report ask
-- for them; without this index both would read every payment the store ever took.
CREATE INDEX payments_unsettled ON payments (captured_at)
  WHERE captured_amount > settled_captured OR refunded_amount > settled_refunded;

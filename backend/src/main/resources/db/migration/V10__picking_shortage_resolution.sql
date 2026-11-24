CREATE TABLE picking_shortage_resolution (
  order_item_id bigint PRIMARY KEY REFERENCES picking_shortage,
  balance_id bigint NOT NULL REFERENCES inventory_balance,
  quantity integer NOT NULL CHECK(quantity > 0),
  outcome text NOT NULL CHECK(outcome IN ('FOUND','LOSS')),
  normal_delta integer NOT NULL,
  held_delta integer NOT NULL,
  actor_id bigint NOT NULL REFERENCES app_user,
  reason text NOT NULL CHECK(char_length(btrim(reason)) BETWEEN 1 AND 500),
  evidence_reference text NOT NULL CHECK(char_length(btrim(evidence_reference)) BETWEEN 1 AND 200),
  resolved_at timestamptz NOT NULL DEFAULT now(),
  CHECK(held_delta=-quantity),
  CHECK(normal_delta=CASE outcome WHEN 'FOUND' THEN quantity ELSE 0 END)
);

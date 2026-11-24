ALTER TABLE outbound_order ADD COLUMN cancellation_status text NOT NULL DEFAULT 'NONE'
  CHECK(cancellation_status IN ('NONE','REQUESTED','CONFIRMED','REJECTED'));
CREATE TABLE wms_cancel_outbox (
  order_id bigint PRIMARY KEY REFERENCES outbound_order,
  command_id uuid NOT NULL UNIQUE,
  actor_id bigint NOT NULL REFERENCES app_user,
  reason text NOT NULL,
  sent boolean NOT NULL DEFAULT false,
  next_attempt_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE dispatch_allocation ADD COLUMN released_qty integer NOT NULL DEFAULT 0
  CHECK(released_qty >= 0 AND released_qty + consumed_qty <= qty);

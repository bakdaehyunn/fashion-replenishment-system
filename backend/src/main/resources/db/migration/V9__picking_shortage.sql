ALTER TABLE outbound_order ADD COLUMN picking_revision integer CHECK (picking_revision > 0);
ALTER TABLE outbound_order_item ADD COLUMN picked_qty integer CHECK (picked_qty BETWEEN 0 AND qty);
ALTER TABLE inventory_movement ADD COLUMN held_delta integer NOT NULL DEFAULT 0;
CREATE TABLE picking_shortage (
  order_item_id bigint PRIMARY KEY REFERENCES outbound_order_item,
  picking_revision integer NOT NULL CHECK (picking_revision > 0),
  work_id text NOT NULL,
  held_qty integer NOT NULL CHECK (held_qty > 0),
  detected_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE wms_release_outbox (
  order_id bigint PRIMARY KEY REFERENCES outbound_order,
  payload jsonb NOT NULL,
  sent boolean NOT NULL DEFAULT false,
  next_attempt_at timestamptz NOT NULL DEFAULT now()
);
-- Earlier versions supported fully picked shipments only.
UPDATE outbound_order SET picking_revision=1 WHERE last_sequence >= 3 AND status <> 'CANCELLED';
UPDATE outbound_order_item i SET picked_qty=i.qty
FROM outbound_order o WHERE o.id=i.order_id AND o.picking_revision=1;

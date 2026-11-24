ALTER TABLE user_role DROP CONSTRAINT user_role_role_code_check;
ALTER TABLE user_role ADD CHECK (role_code IN ('STORE','APPROVER','LOGISTICS_OPERATOR'));
ALTER TABLE location ADD COLUMN code text UNIQUE;
UPDATE location SET code = CASE type WHEN 'STORE' THEN 'STORE-' ELSE 'WH-' END || id;
ALTER TABLE inventory_reservation ADD COLUMN consumed_qty integer NOT NULL DEFAULT 0;
ALTER TABLE inventory_reservation ADD CHECK (consumed_qty >= 0 AND consumed_qty + released_qty <= initial_qty);
ALTER TABLE inventory_movement ADD COLUMN normal_delta integer NOT NULL DEFAULT 0;

CREATE TABLE outbound_order (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  request_id bigint NOT NULL UNIQUE REFERENCES replenishment_request,
  external_id text NOT NULL UNIQUE,
  run_id text NOT NULL,
  status text NOT NULL DEFAULT 'DISPATCH_PENDING',
  last_sequence integer NOT NULL DEFAULT 0,
  packed jsonb
);
CREATE TABLE outbound_order_item (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_id bigint NOT NULL REFERENCES outbound_order,
  request_item_id bigint NOT NULL REFERENCES replenishment_request_item,
  qty integer NOT NULL CHECK(qty > 0),
  UNIQUE(order_id, request_item_id)
);
CREATE TABLE dispatch_allocation (
  order_item_id bigint REFERENCES outbound_order_item,
  reservation_id bigint REFERENCES inventory_reservation,
  qty integer NOT NULL CHECK(qty > 0),
  consumed_qty integer NOT NULL DEFAULT 0 CHECK(consumed_qty BETWEEN 0 AND qty),
  PRIMARY KEY(order_item_id,reservation_id)
);
CREATE TABLE outbound_carton (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_id bigint NOT NULL REFERENCES outbound_order,
  carton_no text NOT NULL,
  UNIQUE(order_id,carton_no)
);
CREATE TABLE outbound_carton_item (
  carton_id bigint REFERENCES outbound_carton,
  order_item_id bigint REFERENCES outbound_order_item,
  qty integer NOT NULL CHECK(qty > 0),
  PRIMARY KEY(carton_id,order_item_id)
);
CREATE TABLE outbound_status_history (
  order_id bigint REFERENCES outbound_order,
  sequence integer,
  status text NOT NULL,
  PRIMARY KEY(order_id,sequence)
);
CREATE TABLE wms_outbox (
  order_id bigint PRIMARY KEY REFERENCES outbound_order,
  payload jsonb NOT NULL,
  sent boolean NOT NULL DEFAULT false,
  next_attempt_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE wms_inbox (
  event_id text PRIMARY KEY,
  order_id bigint NOT NULL REFERENCES outbound_order,
  sequence integer NOT NULL,
  payload jsonb NOT NULL,
  applied boolean NOT NULL DEFAULT false,
  UNIQUE(order_id,sequence)
);

ALTER TABLE inventory_movement ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE request_status_history ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE inventory_movement ADD COLUMN outbound_order_id bigint REFERENCES outbound_order;
ALTER TABLE request_status_history ADD COLUMN outbound_order_id bigint REFERENCES outbound_order;

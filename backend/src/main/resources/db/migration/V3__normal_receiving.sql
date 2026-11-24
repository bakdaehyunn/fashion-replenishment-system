ALTER TABLE replenishment_request DROP CONSTRAINT replenishment_request_status_check;
ALTER TABLE replenishment_request ADD CHECK (status IN ('SUBMITTED','FULFILLING','CANCELLED','COMPLETED'));

CREATE TABLE receiving_inspection (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  carton_id bigint NOT NULL UNIQUE REFERENCES outbound_carton,
  confirmed_by bigint NOT NULL REFERENCES app_user,
  confirmed_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(id,carton_id)
);
CREATE TABLE receiving_inspection_item (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  inspection_id bigint NOT NULL,
  carton_id bigint NOT NULL,
  outbound_order_item_id bigint NOT NULL,
  received_qty integer NOT NULL CHECK(received_qty > 0),
  UNIQUE(inspection_id,outbound_order_item_id),
  FOREIGN KEY(inspection_id,carton_id) REFERENCES receiving_inspection(id,carton_id),
  FOREIGN KEY(carton_id,outbound_order_item_id) REFERENCES outbound_carton_item
);
CREATE INDEX ON receiving_inspection_item(outbound_order_item_id);
CREATE TABLE receiving_stock_movement (
  inspection_item_id bigint PRIMARY KEY REFERENCES receiving_inspection_item,
  balance_id bigint NOT NULL REFERENCES inventory_balance,
  normal_delta integer NOT NULL CHECK(normal_delta > 0),
  occurred_at timestamptz NOT NULL DEFAULT now()
);

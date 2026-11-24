ALTER TABLE receiving_inspection_item DROP CONSTRAINT receiving_inspection_item_received_qty_check;
ALTER TABLE receiving_inspection_item ADD CHECK (received_qty >= 0);
CREATE TABLE receiving_shortage (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  inspection_item_id bigint NOT NULL UNIQUE REFERENCES receiving_inspection_item,
  initial_qty integer NOT NULL CHECK (initial_qty > 0),
  remaining_qty integer NOT NULL CHECK (remaining_qty BETWEEN 0 AND initial_qty)
);
CREATE TABLE additional_receipt (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  shortage_id bigint NOT NULL REFERENCES receiving_shortage,
  received_qty integer NOT NULL CHECK (received_qty > 0),
  confirmed_by bigint NOT NULL REFERENCES app_user,
  confirmed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON additional_receipt(shortage_id);
CREATE TABLE additional_receipt_stock_movement (
  additional_receipt_id bigint PRIMARY KEY REFERENCES additional_receipt,
  balance_id bigint NOT NULL REFERENCES inventory_balance,
  normal_delta integer NOT NULL CHECK (normal_delta > 0),
  occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE VIEW receiving_line_totals AS
SELECT i.carton_id,i.outbound_order_item_id,
       i.received_qty + coalesce(a.qty,0) AS received_qty,
       s.id AS shortage_id,s.initial_qty,s.remaining_qty
FROM receiving_inspection_item i
LEFT JOIN receiving_shortage s ON s.inspection_item_id=i.id
LEFT JOIN LATERAL (SELECT sum(received_qty) AS qty FROM additional_receipt WHERE shortage_id=s.id) a ON true;

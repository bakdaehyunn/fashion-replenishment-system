CREATE TABLE transit_loss (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  shortage_id bigint NOT NULL REFERENCES receiving_shortage,
  lost_qty integer NOT NULL CHECK(lost_qty > 0),
  confirmed_by bigint NOT NULL REFERENCES app_user,
  confirmed_at timestamptz NOT NULL DEFAULT now(),
  reason text NOT NULL CHECK(char_length(btrim(reason)) BETWEEN 1 AND 500),
  evidence_reference text NOT NULL CHECK(char_length(btrim(evidence_reference)) BETWEEN 1 AND 200)
);
CREATE INDEX ON transit_loss(shortage_id);

CREATE OR REPLACE VIEW receiving_line_totals AS
SELECT i.carton_id,i.outbound_order_item_id,
       i.received_qty + coalesce(a.qty,0) AS received_qty,
       s.id AS shortage_id,s.initial_qty,s.remaining_qty,
       coalesce(l.qty,0) AS lost_qty
FROM receiving_inspection_item i
LEFT JOIN receiving_shortage s ON s.inspection_item_id=i.id
LEFT JOIN LATERAL (SELECT sum(received_qty) AS qty FROM additional_receipt WHERE shortage_id=s.id) a ON true
LEFT JOIN LATERAL (SELECT sum(lost_qty) AS qty FROM transit_loss WHERE shortage_id=s.id) l ON true;

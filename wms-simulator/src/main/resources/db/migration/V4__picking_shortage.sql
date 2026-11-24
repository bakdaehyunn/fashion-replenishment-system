CREATE TABLE sim_pick (
  run_id text,
  external_id text,
  warehouse_code text NOT NULL,
  sku_code text,
  picked_qty integer NOT NULL CHECK(picked_qty > 0),
  PRIMARY KEY(run_id,external_id,sku_code),
  FOREIGN KEY(run_id,external_id) REFERENCES sim_order
);
CREATE INDEX ON sim_pick(run_id,warehouse_code,sku_code);
ALTER TABLE sim_order ADD COLUMN release_payload jsonb;

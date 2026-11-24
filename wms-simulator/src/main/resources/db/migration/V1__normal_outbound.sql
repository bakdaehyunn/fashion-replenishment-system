CREATE TABLE sim_stock (
  run_id text,
  warehouse_code text,
  sku_code text,
  normal_qty integer NOT NULL CHECK(normal_qty >= 0),
  PRIMARY KEY(run_id,warehouse_code,sku_code)
);
CREATE TABLE sim_order (
  run_id text,
  external_id text,
  payload jsonb NOT NULL,
  status text NOT NULL DEFAULT 'ACCEPTED',
  last_sequence integer NOT NULL DEFAULT 1,
  PRIMARY KEY(run_id,external_id)
);
CREATE TABLE sim_event (
  run_id text,
  external_id text,
  sequence integer,
  payload jsonb NOT NULL,
  sent boolean NOT NULL DEFAULT false,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(run_id,external_id,sequence),
  FOREIGN KEY(run_id,external_id) REFERENCES sim_order
);

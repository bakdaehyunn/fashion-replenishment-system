CREATE TABLE sim_cancel_command (
  run_id text NOT NULL,
  external_id text NOT NULL,
  command_id uuid NOT NULL,
  reason text NOT NULL,
  outcome text NOT NULL CHECK(outcome IN ('CANCELLED','SHIPPED')),
  PRIMARY KEY(run_id,external_id,command_id),
  FOREIGN KEY(run_id,external_id) REFERENCES sim_order
);

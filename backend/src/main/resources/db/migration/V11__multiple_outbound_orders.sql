ALTER TABLE outbound_order DROP CONSTRAINT outbound_order_request_id_key;
CREATE INDEX ON outbound_order(request_id,id);
CREATE UNIQUE INDEX outbound_order_one_active ON outbound_order(request_id)
WHERE status NOT IN ('SHIPPED','CANCELLED');

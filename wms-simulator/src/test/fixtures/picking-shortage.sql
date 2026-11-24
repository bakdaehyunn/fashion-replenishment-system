-- Load after the normal outbound fixture on a fresh database.
UPDATE sim_stock SET normal_qty=5
WHERE run_id='normal-local' AND warehouse_code='WH-1' AND sku_code='DOWN-095';

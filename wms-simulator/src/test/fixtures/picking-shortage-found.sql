-- Simulate the WMS-side cycle count after the first five units have shipped.
UPDATE sim_stock SET normal_qty=normal_qty+2
WHERE run_id='normal-local' AND warehouse_code='WH-1' AND sku_code='DOWN-095';

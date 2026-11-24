-- Load after the base demo fixture on a fresh database.
UPDATE inventory_balance SET normal_qty=7,held_qty=0 WHERE location_id=1 AND variant_id=1;

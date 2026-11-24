CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO location(type) VALUES ('WAREHOUSE'),('STORE'),('STORE');
INSERT INTO app_user(login_id,password_hash)
SELECT name,crypt('demo',gen_salt('bf')) FROM unnest(ARRAY['store','approver','other']) name;
INSERT INTO user_role VALUES (1,'STORE'),(2,'APPROVER'),(3,'STORE');
INSERT INTO user_location(user_id,location_id) VALUES (1,2),(2,1),(3,3);
INSERT INTO product(name) VALUES ('다운 재킷');
INSERT INTO product_variant(product_id,sku_code,color_code,size_code)
VALUES (1,'DOWN-095','BLACK','095'),(1,'DOWN-100','BLACK','100');
INSERT INTO inventory_balance(location_id,variant_id,normal_qty,held_qty)
VALUES (1,1,10,4),(1,2,1,0);
INSERT INTO user_role VALUES (2,'LOGISTICS_OPERATOR');

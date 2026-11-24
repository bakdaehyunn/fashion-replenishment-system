package com.example.replenishment;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReceivingService {
    private final JdbcTemplate db;
    private final ReplenishmentService requests;
    private final OutboundService outbound;

    ReceivingService(JdbcTemplate db, ReplenishmentService requests, OutboundService outbound) {
        this.db = db;
        this.requests = requests;
        this.outbound = outbound;
    }

    record Carton(long id, String number, Long receiptId, String confirmedAt) {}
    record Item(long id, long variantId, String sku, int shipped, int received, Long shortageId, int initial, int remaining, int lost, int damaged, Long damageId, int damageRemaining) {}

    private List<Carton> loadCartons(long orderId) {
        return db.query("""
            SELECT c.id,c.carton_no,r.id,r.confirmed_at FROM outbound_carton c
              LEFT JOIN receiving_inspection r ON r.carton_id=c.id
            WHERE c.order_id=? ORDER BY c.id
            """, (r,n) -> {
                var confirmed = r.getObject(4, OffsetDateTime.class);
                return new Carton(r.getLong(1),r.getString(2),r.getObject(3,Long.class),
                    confirmed == null ? null : confirmed.toInstant().toString());
            }, orderId);
    }

    private List<Item> items(long cartonId) {
        return db.query("""
            SELECT c.order_item_id,v.id,v.sku_code,c.qty,coalesce(receipt.received_qty,0),
              receipt.shortage_id,receipt.initial_qty,receipt.remaining_qty,receipt.lost_qty,receipt.damaged_qty,receipt.damage_id,receipt.damage_remaining_qty
            FROM outbound_carton_item c JOIN outbound_order_item o ON o.id=c.order_item_id
            JOIN replenishment_request_item i ON i.id=o.request_item_id
            JOIN product_variant v ON v.id=i.variant_id
            LEFT JOIN receiving_line_totals receipt ON receipt.carton_id=c.carton_id
                AND receipt.outbound_order_item_id=c.order_item_id
            WHERE c.carton_id=? ORDER BY v.id
            """,(r,n) -> new Item(r.getLong(1),r.getLong(2),r.getString(3),r.getInt(4),r.getInt(5),
                r.getObject(6,Long.class),r.getInt(7),r.getInt(8),r.getInt(9),r.getInt(10),r.getObject(11,Long.class),r.getInt(12)),cartonId);
    }

    private Map<String,Object> detail(Carton carton) {
        var result = new LinkedHashMap<String,Object>();
        result.put("id",Long.toString(carton.id()));
        result.put("cartonNo",carton.number());
        result.put("inspectionStatus",carton.receiptId() == null ? "PENDING" : "CONFIRMED");
        if (carton.receiptId() != null) result.put("receiptId",Long.toString(carton.receiptId()));
        result.put("lines",items(carton.id()).stream().map(i -> {
            var line = new LinkedHashMap<String,Object>(Map.of(
                "outboundOrderItemId",Long.toString(i.id()),"skuCode",i.sku(),"shippedQty",i.shipped(),
                "receivedQty",i.received(),"damagedQty",i.damaged(),"lostQty",i.lost(),"inTransitQty",i.shipped()-i.received()-i.damaged()-i.lost()));
            if (i.shortageId() != null) line.put("shortage",Map.of("id",Long.toString(i.shortageId()),
                "initialQty",i.initial(),"remainingQty",i.remaining(),"status",i.remaining() == 0 ? "RESOLVED" : "OPEN"));
            if (i.damageId() != null) line.put("damage",Map.of("id",Long.toString(i.damageId()),
                "initialQty",i.damaged(),"remainingQty",i.damageRemaining(),"status",i.damageRemaining() == 0 ? "RESOLVED" : "OPEN"));
            return line;
        }).toList());
        return result;
    }

    private OutboundService.Order order(long requestId, long orderId) {
        var order = outbound.order(requestId,orderId);
        if (order == null || order.id() != orderId) throw new ApiFailure(404,"OUTBOUND_ORDER_NOT_FOUND");
        return order;
    }

    private Carton carton(long orderId, long cartonId) {
        return loadCartons(orderId).stream().filter(c -> c.id() == cartonId)
            .findFirst().orElseThrow(() -> new ApiFailure(404,"OUTBOUND_CARTON_NOT_FOUND"));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    Object cartons(String username, String rawRequestId, String rawOrderId) {
        long actor = requests.actor(username);
        var request = requests.request(requests.number(rawRequestId,false),false);
        requests.require(requests.allowed(actor,"STORE",request.destination())
            || requests.allowed(actor,"APPROVER",request.source())
            || requests.allowed(actor,"LOGISTICS_OPERATOR",request.source()));
        var order = order(request.id(),requests.number(rawOrderId,false));
        return Map.of("requestVersion",Long.toString(request.version()),"cartons",
            loadCartons(order.id()).stream().map(this::detail).toList());
    }

    @Transactional
    Map<String,Object> confirm(String username,String rawRequestId,String rawOrderId,String rawCartonId,
                               String key,ReceivingApi.Confirmation input) {
        requests.key(key);
        long actor = requests.actor(username);
        var request = requests.request(requests.number(rawRequestId,false),true);
        requests.require(requests.allowed(actor,"STORE",request.destination()));
        var order = order(request.id(),requests.number(rawOrderId,false));
        var carton = carton(order.id(),requests.number(rawCartonId,false));
        long version = requests.number(input.expectedVersion(),true);
        var amounts = new TreeMap<Long,Integer>();
        var damaged = new TreeMap<Long,Integer>();
        if (input.lines() == null || input.lines().isEmpty()) throw ApiFailure.invalid();
        for (var item : input.lines()) {
            if (item == null || item.receivedQty() == null || item.receivedQty() < 0) throw ApiFailure.invalid();
            long id = requests.number(item.outboundOrderItemId(),false);
            int damagedQty = item.damagedQty() == null ? 0 : item.damagedQty();
            if (damagedQty < 0 || amounts.put(id,item.receivedQty()) != null) throw ApiFailure.invalid();
            if (damagedQty > 0) damaged.put(id,damagedQty);
        }
        String hash = requests.hash(damaged.isEmpty() ? List.of(carton.id(),version,amounts)
            : List.of(carton.id(),version,amounts,damaged));
        var previous = requests.previous("RECEIPT",request,actor,key,hash);
        if (previous != null) return previous;
        if (carton.receiptId() != null) throw new ApiFailure(409,"RECEIPT_ALREADY_CONFIRMED");
        requests.checkVersion(request,version);
        if (request.status() != RequestStatus.FULFILLING || order.status() != OutboundStatus.SHIPPED)
            throw new ApiFailure(409,"INVALID_STATE");
        var items = items(carton.id());
        if (items.size() != amounts.size() || items.stream().anyMatch(
            i -> amounts.get(i.id()) == null || (long) amounts.get(i.id()) + damaged.getOrDefault(i.id(),0) > i.shipped())) throw ApiFailure.invalid();

        long receipt = db.queryForObject("""
            INSERT INTO receiving_inspection(carton_id,confirmed_by) VALUES (?,?) RETURNING id
            """,Long.class,carton.id(),actor);
        for (Item item : items) recordReceipt(request.destination(),carton.id(),receipt,item,amounts.get(item.id()),damaged.getOrDefault(item.id(),0));
        var updated = requests.advanceFulfillment(request,actor,"매장 최초 검수 확정");
        var confirmed = carton(order.id(),carton.id());
        var result = Map.<String,Object>of("receiptId",Long.toString(receipt),"confirmedAt",confirmed.confirmedAt(),
            "requestVersion",Long.toString(updated.version()),"requestStatus",updated.status(),"carton",detail(confirmed));
        requests.save("RECEIPT",request,actor,key,hash,result);
        return result;
    }

    @Transactional
    Map<String,Object> add(String username,String rawRequestId,String rawOrderId,String rawCartonId,
                           String rawShortageId,String key,ReceivingApi.AdditionalConfirmation input) {
        requests.key(key);
        long actor = requests.actor(username);
        var request = requests.request(requests.number(rawRequestId,false),true);
        requests.require(requests.allowed(actor,"STORE",request.destination()));
        var order = order(request.id(),requests.number(rawOrderId,false));
        var carton = carton(order.id(),requests.number(rawCartonId,false));
        long shortageId = requests.number(rawShortageId,false);
        var item = shortageItem(carton.id(),shortageId);
        long version = requests.number(input.expectedVersion(),true);
        if (input.receivedQty() == null || input.receivedQty() <= 0) throw ApiFailure.invalid();
        int qty = input.receivedQty();
        String hash = requests.hash(List.of(shortageId,version,qty));
        var previous = requests.previous("ADDITIONAL_RECEIPT",request,actor,key,hash);
        if (previous != null) return previous;
        settle(request,order,item,version,qty,shortageId);
        var receipt = db.queryForObject("""
            INSERT INTO additional_receipt(shortage_id,received_qty,confirmed_by) VALUES (?,?,?)
            RETURNING id,confirmed_at
            """,(r,n) -> new Additional(r.getString(1),r.getObject(2,OffsetDateTime.class).toInstant().toString()),
            shortageId,qty,actor);
        long balance = increaseStock(request.destination(),item.variantId(),qty);
        db.update("INSERT INTO additional_receipt_stock_movement(additional_receipt_id,balance_id,normal_delta) VALUES (?,?,?)",
            Long.parseLong(receipt.id()),balance,qty);
        var updated = requests.advanceFulfillment(request,actor,"매장 추가 입고 확정");
        var result = Map.<String,Object>of("additionalReceiptId",receipt.id(),"confirmedAt",receipt.confirmedAt(),
            "requestVersion",Long.toString(updated.version()),"requestStatus",updated.status(),"carton",detail(carton));
        requests.save("ADDITIONAL_RECEIPT",request,actor,key,hash,result);
        return result;
    }

    @Transactional
    Map<String,Object> loss(String username,String rawRequestId,String rawOrderId,String rawCartonId,
                            String rawShortageId,String key,ReceivingApi.LossConfirmation input) {
        requests.key(key);
        long actor = requests.actor(username);
        var request = requests.request(requests.number(rawRequestId,false),true);
        requests.require(requests.allowed(actor,"LOGISTICS_OPERATOR",request.source()));
        var order = order(request.id(),requests.number(rawOrderId,false));
        var carton = carton(order.id(),requests.number(rawCartonId,false));
        long shortageId = requests.number(rawShortageId,false);
        var item = shortageItem(carton.id(),shortageId);
        long version = requests.number(input.expectedVersion(),true);
        if (input.lostQty() == null || input.lostQty() <= 0) throw ApiFailure.invalid();
        int qty = input.lostQty();
        String reason = requiredText(input.reason(),500);
        String evidence = requiredText(input.evidenceReference(),200);
        String hash = requests.hash(List.of(shortageId,version,qty,reason,evidence));
        var previous = requests.previous("TRANSIT_LOSS",request,actor,key,hash);
        if (previous != null) return previous;
        settle(request,order,item,version,qty,shortageId);
        var loss = db.queryForObject("""
            INSERT INTO transit_loss(shortage_id,lost_qty,confirmed_by,reason,evidence_reference)
            VALUES (?,?,?,?,?) RETURNING id,confirmed_at
            """,(r,n) -> new Additional(r.getString(1),r.getObject(2,OffsetDateTime.class).toInstant().toString()),
            shortageId,qty,actor,reason,evidence);
        var updated = requests.advanceFulfillment(request,actor,"운송 손실 확정");
        var result = Map.<String,Object>of("lossId",loss.id(),"confirmedAt",loss.confirmedAt(),
            "requestVersion",Long.toString(updated.version()),"requestStatus",updated.status(),"carton",detail(carton));
        requests.save("TRANSIT_LOSS",request,actor,key,hash,result);
        return result;
    }

    private Item shortageItem(long cartonId,long shortageId) {
        return items(cartonId).stream().filter(i -> Objects.equals(i.shortageId(),shortageId))
            .findFirst().orElseThrow(() -> new ApiFailure(404,"RECEIPT_SHORTAGE_NOT_FOUND"));
    }

    private String requiredText(String value,int max) {
        if (value == null) throw ApiFailure.invalid();
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.codePointCount(0,normalized.length()) > max) throw ApiFailure.invalid();
        return normalized;
    }

    private void settle(ReplenishmentService.Request request,OutboundService.Order order,Item item,
                        long version,int qty,long shortageId) {
        requests.checkVersion(request,version);
        if (qty > item.remaining()) throw new ApiFailure(409,"RECEIPT_SHORTAGE_CONFLICT");
        if (request.status() != RequestStatus.FULFILLING || order.status() != OutboundStatus.SHIPPED)
            throw new ApiFailure(409,"INVALID_STATE");
        if (db.update("UPDATE receiving_shortage SET remaining_qty=remaining_qty-? WHERE id=? AND remaining_qty>=?",
            qty,shortageId,qty) != 1) throw new ApiFailure(409,"RECEIPT_SHORTAGE_CONFLICT");
    }

    private record Additional(String id,String confirmedAt) {}

    private void recordReceipt(long destination,long cartonId,long receipt,Item item,int qty,int damaged) {
        long line = db.queryForObject("""
            INSERT INTO receiving_inspection_item(inspection_id,carton_id,outbound_order_item_id,received_qty,damaged_qty)
            VALUES (?,?,?,?,?) RETURNING id
            """,Long.class,receipt,cartonId,item.id(),qty,damaged);
        int missing = item.shipped()-qty-damaged;
        if (missing > 0) db.update("INSERT INTO receiving_shortage(inspection_item_id,initial_qty,remaining_qty) VALUES (?,?,?)",
            line,missing,missing);
        if (qty > 0) {
            long balance = increaseStock(destination,item.variantId(),qty);
            db.update("INSERT INTO receiving_stock_movement(inspection_item_id,balance_id,normal_delta) VALUES (?,?,?)",
                line,balance,qty);
        }
        if (damaged > 0) {
            long damage = db.queryForObject("""
                INSERT INTO receiving_damage(inspection_item_id,initial_qty,remaining_qty) VALUES (?,?,?) RETURNING id
                """,Long.class,line,damaged,damaged);
            long balance = db.queryForObject("""
                INSERT INTO inventory_balance(location_id,variant_id,normal_qty,held_qty) VALUES (?,?,0,?)
                ON CONFLICT(location_id,variant_id) DO UPDATE SET held_qty=inventory_balance.held_qty+excluded.held_qty
                RETURNING id
                """,Long.class,destination,item.variantId(),damaged);
            db.update("INSERT INTO held_stock_movement(balance_id,damage_id,held_delta) VALUES (?,?,?)",
                balance,damage,damaged);
        }
    }

    @Transactional
    Map<String,Object> dispose(String username,String rawRequestId,String rawOrderId,String rawCartonId,
                               String rawDamageId,String key,ReceivingApi.Disposal input) {
        requests.key(key);
        long actor = requests.actor(username);
        var request = requests.request(requests.number(rawRequestId,false),true);
        requests.require(requests.allowed(actor,"LOGISTICS_OPERATOR",request.source()));
        var order = order(request.id(),requests.number(rawOrderId,false));
        var carton = carton(order.id(),requests.number(rawCartonId,false));
        long damage = requests.number(rawDamageId,false);
        var item = items(carton.id()).stream().filter(i -> Objects.equals(i.damageId(),damage))
            .findFirst().orElseThrow(() -> new ApiFailure(404,"RECEIPT_DAMAGE_NOT_FOUND"));
        long version = requests.number(input.expectedVersion(),true);
        if (input.disposedQty() == null || input.disposedQty() <= 0) throw ApiFailure.invalid();
        int qty = input.disposedQty();
        String reason = requiredText(input.reason(),500);
        String evidence = requiredText(input.evidenceReference(),200);
        String hash = requests.hash(List.of(damage,version,qty,reason,evidence));
        var previous = requests.previous("DAMAGE_DISPOSAL",request,actor,key,hash);
        if (previous != null) return previous;
        requests.checkVersion(request,version);
        if (qty > item.damageRemaining()) throw new ApiFailure(409,"RECEIPT_DAMAGE_CONFLICT");
        if (request.status() != RequestStatus.FULFILLING || order.status() != OutboundStatus.SHIPPED)
            throw new ApiFailure(409,"INVALID_STATE");
        if (db.update("UPDATE receiving_damage SET remaining_qty=remaining_qty-? WHERE id=? AND remaining_qty>=?",
            qty,damage,qty) != 1) throw new ApiFailure(409,"RECEIPT_DAMAGE_CONFLICT");
        var balances = db.query("""
            UPDATE inventory_balance SET held_qty=held_qty-? WHERE location_id=? AND variant_id=? AND held_qty>=?
            RETURNING id
            """,(r,n) -> r.getLong(1),qty,request.destination(),item.variantId(),qty);
        if (balances.isEmpty()) throw new ApiFailure(409,"RECEIPT_DAMAGE_CONFLICT");
        var disposal = db.queryForObject("""
            INSERT INTO damage_disposal(damage_id,disposed_qty,confirmed_by,reason,evidence_reference)
            VALUES (?,?,?,?,?) RETURNING id,confirmed_at
            """,(r,n) -> new Additional(r.getString(1),r.getObject(2,OffsetDateTime.class).toInstant().toString()),
            damage,qty,actor,reason,evidence);
        db.update("INSERT INTO held_stock_movement(balance_id,disposal_id,held_delta) VALUES (?,?,?)",
            balances.getFirst(),Long.parseLong(disposal.id()),-qty);
        var updated = requests.advanceFulfillment(request,actor,"파손 재고 폐기 확정");
        var result = Map.<String,Object>of("disposalId",disposal.id(),"confirmedAt",disposal.confirmedAt(),
            "requestVersion",Long.toString(updated.version()),"requestStatus",updated.status(),"carton",detail(carton));
        requests.save("DAMAGE_DISPOSAL",request,actor,key,hash,result);
        return result;
    }

    private long increaseStock(long destination,long variant,int qty) {
        return db.queryForObject("""
            INSERT INTO inventory_balance(location_id,variant_id,normal_qty) VALUES (?,?,?)
            ON CONFLICT(location_id,variant_id) DO UPDATE SET normal_qty=inventory_balance.normal_qty+excluded.normal_qty
            RETURNING id
            """,Long.class,destination,variant,qty);
    }
}

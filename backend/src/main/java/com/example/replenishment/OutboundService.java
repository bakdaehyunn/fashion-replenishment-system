package com.example.replenishment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class OutboundService {
    private final JdbcTemplate db;
    private final ReplenishmentService requests;
    private final ObjectMapper json;
    private final String runId;
    private final String token;

    OutboundService(JdbcTemplate db, ReplenishmentService requests, ObjectMapper json,
                    @Value("${WMS_RUN_ID:normal-local}") String runId,
                    @Value("${WMS_TOKEN:}") String token) {
        this.db = db;
        this.requests = requests;
        this.json = json;
        this.runId = runId;
        this.token = token;
    }

    record Order(long id,long requestId,String externalId,OutboundStatus status,int sequence,String packed,OutboundCancellationStatus cancellation,Integer revision,boolean releaseRequested) {}
    record Item(long id,long requestItemId,String sku,int qty,int shipped,int lost,int damaged,int inTransit,Integer picked,Integer held,String resolution) {}
    record Allocation(long reservation,int qty,long balance) {}

    Order order(long requestId,long orderId) {
        return db.query("""
            SELECT id,request_id,external_id,status,last_sequence,packed,cancellation_status,picking_revision,
              EXISTS(SELECT 1 FROM wms_release_outbox r WHERE r.order_id=outbound_order.id)
            FROM outbound_order WHERE request_id=? AND id=?
            """,(r,n) -> new Order(r.getLong(1),r.getLong(2),r.getString(3),OutboundStatus.valueOf(r.getString(4)),r.getInt(5),r.getString(6),OutboundCancellationStatus.valueOf(r.getString(7)),r.getObject(8,Integer.class),r.getBoolean(9)),
            requestId,orderId).stream().findFirst().orElse(null);
    }

    private List<Item> items(long orderId) {
        return db.query("""
            SELECT o.id,o.request_item_id,v.sku_code,o.qty,
              (SELECT coalesce(sum(c.qty),0) FROM outbound_carton_item c WHERE c.order_item_id=o.id),
              (SELECT coalesce(sum(r.received_qty),0) FROM receiving_line_totals r WHERE r.outbound_order_item_id=o.id),
              (SELECT coalesce(sum(r.lost_qty),0) FROM receiving_line_totals r WHERE r.outbound_order_item_id=o.id),
              (SELECT coalesce(sum(r.damaged_qty),0) FROM receiving_line_totals r WHERE r.outbound_order_item_id=o.id),o.picked_qty,ps.held_qty,resolution.outcome
            FROM outbound_order_item o
            JOIN replenishment_request_item i ON i.id=o.request_item_id JOIN product_variant v ON v.id=i.variant_id
            LEFT JOIN picking_shortage ps ON ps.order_item_id=o.id
            LEFT JOIN picking_shortage_resolution resolution ON resolution.order_item_id=ps.order_item_id
            WHERE o.order_id=? ORDER BY i.variant_id
            """,(r,n) -> new Item(r.getLong(1),r.getLong(2),r.getString(3),r.getInt(4),r.getInt(5),r.getInt(7),r.getInt(8),r.getInt(5)-r.getInt(6)-r.getInt(7)-r.getInt(8),r.getObject(9,Integer.class),r.getObject(10,Integer.class),r.getString(11)),orderId);
    }

    private Map<String,Object> detail(Order order,long version) {
        var result = new LinkedHashMap<String,Object>(Map.of("id",Long.toString(order.id()),
            "requestId",Long.toString(order.requestId()),"requestVersion",Long.toString(version),
            "externalOrderId",order.externalId(),"status",order.status(),"cancellationStatus",order.cancellation()));
        if (order.revision() != null) result.put("pickingRevision",order.revision());
        if (order.status() == OutboundStatus.PICKING_EXCEPTION || order.releaseRequested())
            result.put("partialReleaseStatus",order.releaseRequested() ? "REQUESTED" : "PENDING");
        result.put("lines",items(order.id()).stream().map(i -> {
            var line = new LinkedHashMap<String,Object>(Map.of("id",Long.toString(i.id()),
                "requestItemId",Long.toString(i.requestItemId()),"skuCode",i.sku(),"instructedQty",i.qty(),
                "shippedQty",i.shipped(),"lostQty",i.lost(),"damagedQty",i.damaged(),"inTransitQty",i.inTransit()));
            if (i.picked() != null) {
                line.put("pickedQty",i.picked());
                line.put("shortageQty",i.qty()-i.picked());
            }
            if (i.held() != null) {
                var shortage = new LinkedHashMap<String,Object>(Map.of("quantity",i.held(),
                    "status",i.resolution() == null ? "OPEN" : "RESOLVED"));
                if (i.resolution() != null) shortage.put("outcome",i.resolution());
                line.put("pickingShortage",shortage);
            }
            return line;
        }).toList());
        return result;
    }

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    Object get(String username,String requestId,String orderId) {
        long actor = requests.actor(username);
        var request = requests.request(requests.number(requestId,false),false);
        requests.require(requests.allowed(actor,"STORE",request.destination())
            || requests.allowed(actor,"APPROVER",request.source()) || requests.allowed(actor,"LOGISTICS_OPERATOR",request.source()));
        Order order = order(request.id(),requests.number(orderId,false));
        if (order == null)
            throw new ApiFailure(404,"OUTBOUND_ORDER_NOT_FOUND");
        return detail(order,request.version());
    }

    @Transactional
    Map<String,Object> create(String username,String id,String key,String expectedVersion) {
        requests.key(key);
        long actor = requests.actor(username);
        var request = requests.request(requests.number(id,false),true);
        requests.require(requests.allowed(actor,"LOGISTICS_OPERATOR",request.source()));
        long version = requests.number(expectedVersion,true);
        String hash = requests.hash(version);
        var previous = requests.previous("DISPATCH",request,actor,key,hash);
        if (previous != null) return previous;
        requests.checkVersion(request,version);
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM outbound_order WHERE request_id=? AND status NOT IN ('SHIPPED','CANCELLED'))",Boolean.class,request.id()))) throw new ApiFailure(409,"DISPATCH_ALREADY_CREATED");
        var allLines = requests.lines(request.id());
        var lines = allLines.stream().filter(line -> line.reserved()>0).toList();
        if (request.status() != RequestStatus.FULFILLING || lines.isEmpty()
            || (!request.partial() && allLines.stream().anyMatch(l -> l.reserved() != l.requested())))
            throw new ApiFailure(409,"INVALID_STATE");
        String externalId = "DO-" + UUID.randomUUID();
        long orderId = db.queryForObject("""
            INSERT INTO outbound_order(request_id,external_id,run_id) VALUES (?,?,?) RETURNING id
            """,Long.class,request.id(),externalId,runId);
        for (var line : lines) {
            long item = db.queryForObject("""
                INSERT INTO outbound_order_item(order_id,request_item_id,qty) VALUES (?,?,?) RETURNING id
                """,Long.class,orderId,line.id(),line.reserved());
            db.update("""
                INSERT INTO dispatch_allocation(order_item_id,reservation_id,qty)
                SELECT ?,r.id,r.initial_qty-r.released_qty-r.consumed_qty
                FROM inventory_reservation r JOIN replenishment_approval_item a ON a.id=r.approval_item_id
                WHERE a.request_item_id=? AND r.initial_qty>r.released_qty+r.consumed_qty
                """,item,line.id());
            long allocated = db.queryForObject("""
                SELECT coalesce(sum(qty),0) FROM dispatch_allocation WHERE order_item_id=?
                """,Long.class,item);
            if (allocated != line.reserved()) throw new IllegalStateException("outbound allocation mismatch");
        }
        var payload = Map.of("requestReference","REQ-"+request.id(),
            "sourceWarehouseCode",locationCode(request.source()),"destinationStoreCode",locationCode(request.destination()),
            "allowPartial",request.partial(),"lines",items(orderId).stream().map(item -> Map.of(
                "lineId",Long.toString(item.id()),"skuCode",item.sku(),"quantity",item.qty())).toList());
        db.update("INSERT INTO wms_outbox(order_id,payload) VALUES (?,?::jsonb)",orderId,json.writeValueAsString(payload));
        db.update("INSERT INTO outbound_status_history VALUES (?,0,'DISPATCH_PENDING')",orderId);
        var updated = requests.transition(request,RequestStatus.FULFILLING,actor,"출고 지시 생성");
        var result = detail(order(request.id(),orderId),updated.version());
        requests.save("DISPATCH",request,actor,key,hash,result);
        return result;
    }

    @Transactional
    Map<String,Object> cancel(String username,String rawRequest,String rawOrder,String key,
                              ReplenishmentApi.Cancellation input,boolean outboundOnly) {
        requests.key(key);
        long actor = requests.actor(username);
        var request = requests.request(requests.number(rawRequest,false),true);
        requests.require(outboundOnly ? requests.allowed(actor,"LOGISTICS_OPERATOR",request.source())
            : requests.allowed(actor,"STORE",request.destination()));
        long orderId = requests.number(rawOrder,false);
        Order order = order(request.id(),orderId);
        if (order == null || order.id() != orderId) throw new ApiFailure(404,"OUTBOUND_ORDER_NOT_FOUND");
        long version = requests.number(input.expectedVersion(),true);
        String reason = input.reason();
        if (reason == null || reason.isBlank() || reason.length()>500) throw ApiFailure.invalid();
        String hash = requests.hash(outboundOnly ? List.of(orderId,version,reason,true) : List.of(orderId,version,reason));
        var previous = requests.previous("WMS_CANCEL",request,actor,key,hash);
        if (previous != null) return previous;
        requests.checkVersion(request,version);
        if (request.status() != RequestStatus.FULFILLING || order.cancellation() != OutboundCancellationStatus.NONE
            || (outboundOnly && order.status() != OutboundStatus.PICKING_EXCEPTION)
            || EnumSet.of(OutboundStatus.SHIPPED,OutboundStatus.CANCELLED).contains(order.status())) throw new ApiFailure(409,"INVALID_STATE");
        db.update("UPDATE outbound_order SET cancellation_status='REQUESTED' WHERE id=?",orderId);
        db.update("INSERT INTO wms_cancel_outbox(order_id,command_id,actor_id,reason,outbound_only) VALUES (?,?,?,?,?)",
            orderId,UUID.randomUUID(),actor,reason,outboundOnly);
        var updated = requests.transition(request,RequestStatus.FULFILLING,actor,"WMS 취소 요청: "+reason);
        var result = detail(order(request.id(),orderId),updated.version());
        requests.save("WMS_CANCEL",request,actor,key,hash,result);
        return result;
    }

    private record CancelCommand(long actor,String reason,String id,boolean outboundOnly) {}

    private OutboundStatus applyCancellation(ReplenishmentService.Request request,Order order,JsonNode payload,boolean confirmed) {
        var command = db.query("SELECT actor_id,reason,command_id,outbound_only FROM wms_cancel_outbox WHERE order_id=?",
            (r,n) -> new CancelCommand(r.getLong(1),r.getString(2),r.getString(3),r.getBoolean(4)),order.id())
            .stream().findFirst().orElseThrow(ApiFailure::invalid);
        if (!command.id().equals(payload.path("commandId").asString())) throw ApiFailure.invalid();
        if (confirmed) {
            if (order.cancellation() != OutboundCancellationStatus.REQUESTED || order.status() == OutboundStatus.SHIPPED)
                throw ApiFailure.invalid();
            boolean hasShipment = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM outbound_order WHERE request_id=? AND status='SHIPPED')
                """,Boolean.class,request.id()));
            if (hasShipment || command.outboundOnly()) {
                for (Item item : items(order.id())) {
                    int released = 0;
                    for (Allocation allocation : remainingAllocations(item.id())) {
                        int qty = allocation.qty();
                        db.update("UPDATE inventory_balance SET reserved_qty=reserved_qty-? WHERE id=?",qty,allocation.balance());
                        db.update("UPDATE inventory_reservation SET released_qty=released_qty+? WHERE id=?",qty,allocation.reservation());
                        db.update("UPDATE dispatch_allocation SET released_qty=released_qty+? WHERE order_item_id=? AND reservation_id=?",
                            qty,item.id(),allocation.reservation());
                        db.update("INSERT INTO inventory_movement(reservation_id,outbound_order_id,reserved_delta) VALUES (?,?,?)",
                            allocation.reservation(),order.id(),-qty);
                        released += qty;
                    }
                    db.update("UPDATE replenishment_request_item SET reserved_qty=reserved_qty-?,open_qty=open_qty+? WHERE id=?",
                        released,released,item.requestItemId());
                }
                requests.transition(request,RequestStatus.FULFILLING,command.actor(),command.reason());
            } else {
                db.update("""
                    UPDATE dispatch_allocation SET released_qty=qty-consumed_qty WHERE order_item_id IN
                        (SELECT id FROM outbound_order_item WHERE order_id=?)
                    """,order.id());
                requests.releaseForCancellation(request,command.actor(),command.reason());
                requests.transition(request,RequestStatus.CANCELLED,command.actor(),command.reason());
            }
            db.update("UPDATE outbound_order SET cancellation_status='CONFIRMED' WHERE id=?",order.id());
            return OutboundStatus.CANCELLED;
        }
        if (order.status() != OutboundStatus.SHIPPED || !EnumSet.of(OutboundCancellationStatus.REQUESTED,OutboundCancellationStatus.REJECTED).contains(order.cancellation()))
            throw ApiFailure.invalid();
        db.update("UPDATE outbound_order SET cancellation_status='REJECTED' WHERE id=?",order.id());
        return OutboundStatus.SHIPPED;
    }

    @Transactional
    Map<String,Object> release(String username,String rawRequest,String rawOrder,String key,
                               OutboundApi.Release input) {
        requests.key(key);
        long actor = requests.actor(username);
        var request = requests.request(requests.number(rawRequest,false),true);
        requests.require(requests.allowed(actor,"LOGISTICS_OPERATOR",request.source()));
        long orderId = requests.number(rawOrder,false);
        var order = order(request.id(),orderId);
        if (order == null || order.id() != orderId) throw new ApiFailure(404,"OUTBOUND_ORDER_NOT_FOUND");
        long version = requests.number(input.expectedVersion(),true);
        if (input.pickingRevision() == null || input.pickingRevision() < 1) throw ApiFailure.invalid();
        String hash = requests.hash(List.of(orderId,version,input.pickingRevision()));
        var previous = requests.previous("PARTIAL_RELEASE",request,actor,key,hash);
        if (previous != null) return previous;
        requests.checkVersion(request,version);
        if (order.status() != OutboundStatus.PICKING_EXCEPTION || order.releaseRequested()
            || order.cancellation() != OutboundCancellationStatus.NONE) throw new ApiFailure(409,"INVALID_STATE");
        if (!Objects.equals(order.revision(),input.pickingRevision()))
            throw new ApiFailure(409,"PICKING_REVISION_CONFLICT");
        var lines = items(orderId).stream().filter(i -> i.picked() != null && i.picked() > 0)
            .map(i -> Map.of("lineId",Long.toString(i.id()),"quantity",i.picked())).toList();
        if (!request.partial() || lines.isEmpty()) throw new ApiFailure(409,"PARTIAL_RELEASE_NOT_ALLOWED");
        var command = Map.of("commandId",UUID.randomUUID().toString(),"revision",order.revision(),"lines",lines);
        db.update("INSERT INTO wms_release_outbox(order_id,payload) VALUES (?,?::jsonb)",orderId,json.writeValueAsString(command));
        var updated = requests.transition(request,RequestStatus.FULFILLING,actor,"부분 출고 승인");
        var result = detail(order(request.id(),orderId),updated.version());
        requests.save("PARTIAL_RELEASE",request,actor,key,hash,result);
        return result;
    }

    private String locationCode(long id) {
        return db.queryForObject("SELECT coalesce(code, CASE type WHEN 'STORE' THEN 'STORE-' ELSE 'WH-' END || id) FROM location WHERE id=?",
            String.class,id);
    }

    @Transactional
    void receive(String supplied,JsonNode event) {
        if (token.isBlank() || supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
            supplied.getBytes(StandardCharsets.UTF_8))) throw new ApiFailure(403,"FORBIDDEN");
        if (!event.path("sourceSystem").asString().equals("WMS_SIMULATOR") || event.path("schemaVersion").asInt() != 1
            || event.path("sequence").asInt() <= 0 || event.path("eventId").asString().isBlank()
            || !event.path("payload").isObject()) throw ApiFailure.invalid();
        var orderIds = db.query("SELECT id FROM outbound_order WHERE external_id=? AND run_id=?",
            (r,n) -> r.getLong(1),event.path("externalOrderId").asString(),event.path("runId").asString());
        if (orderIds.isEmpty()) throw new ApiFailure(404,"OUTBOUND_ORDER_NOT_FOUND");
        String id = event.path("eventId").asString();
        String payload = json.writeValueAsString(event);
        if (db.update("INSERT INTO wms_inbox(event_id,order_id,sequence,payload) VALUES (?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
            id,orderIds.getFirst(),event.path("sequence").asInt(),payload) == 0
            && !Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM wms_inbox WHERE event_id=? AND payload=?::jsonb)",
                Boolean.class,id,payload))) throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT");
    }

    @Transactional
    public void applyNext(long requestId,long orderId) {
        var request = requests.request(requestId,true);
        db.queryForObject("SELECT id FROM outbound_order WHERE request_id=? AND id=? FOR UPDATE",Long.class,requestId,orderId);
        Order order = order(requestId,orderId);
        var events = db.query("SELECT payload FROM wms_inbox WHERE order_id=? AND sequence=? AND NOT applied",
            (r,n) -> json.readTree(r.getString(1)),order.id(),order.sequence()+1);
        if (events.isEmpty()) return;
        JsonNode event = events.getFirst();
        JsonNode payload = event.path("payload");
        String type = event.path("eventType").asString();
        int sequence = order.sequence()+1;
        OutboundStatus next;
        if (type.equals("CANCELLATION_CONFIRMED") || type.equals("CANCELLATION_REJECTED")) {
            next = applyCancellation(request,order,payload,type.equals("CANCELLATION_CONFIRMED"));
        } else {
            if (order.status() == OutboundStatus.CANCELLED) throw ApiFailure.invalid();
            next = switch(sequence) {
            case 1 -> expected(type,"ORDER_ACCEPTED",OutboundStatus.ACCEPTED);
            case 2 -> expected(type,"WORK_STARTED",OutboundStatus.WORKING);
            case 3 -> {
                expected(type,"PICKING_FINALIZED",OutboundStatus.WORKING);
                yield applyPicking(request,order,payload) ? OutboundStatus.PICKING_EXCEPTION : OutboundStatus.WORKING;
            }
            case 4 -> {
                expected(type,"PACKING_COMPLETED",OutboundStatus.READY_TO_SHIP);
                if (order.status() == OutboundStatus.PICKING_EXCEPTION && !order.releaseRequested()) throw ApiFailure.invalid();
                validateCartons(order,payload.path("cartons"));
                db.update("UPDATE outbound_order SET packed=?::jsonb WHERE id=?",json.writeValueAsString(payload.path("cartons")),order.id());
                yield OutboundStatus.READY_TO_SHIP;
            }
            case 5 -> {
                expected(type,"SHIPMENT_CONFIRMED",OutboundStatus.SHIPPED);
                validateUnshipped(order,payload.path("unshippedLines"));
                if (!json.readTree(order.packed()).equals(payload.path("cartons"))) throw ApiFailure.invalid();
                ship(request,order,payload.path("cartons"));
                db.update("""
                    UPDATE outbound_order SET cancellation_status='REJECTED'
                    WHERE id=? AND cancellation_status='REQUESTED'
                    """, order.id());
                yield OutboundStatus.SHIPPED;
            }
            default -> throw ApiFailure.invalid();
        };
        }
        db.update("UPDATE outbound_order SET status=?,last_sequence=? WHERE id=?",next.name(),sequence,order.id());
        db.update("INSERT INTO outbound_status_history VALUES (?,?,?)",order.id(),sequence,next.name());
        db.update("UPDATE wms_inbox SET applied=true WHERE order_id=? AND sequence=?",order.id(),sequence);
    }

    private OutboundStatus expected(String actual,String expected,OutboundStatus status) {
        if (!actual.equals(expected)) throw ApiFailure.invalid();
        return status;
    }

    private Item line(Map<String,Item> items,JsonNode node) {
        Item item = items.get(node.path("lineId").asString());
        if (item == null || !item.sku().equals(node.path("skuCode").asString())) throw ApiFailure.invalid();
        return item;
    }

    private Map<String,Item> itemMap(Order order) {
        var map = new HashMap<String,Item>();
        items(order.id()).forEach(item -> map.put(Long.toString(item.id()),item));
        return map;
    }

    private boolean applyPicking(ReplenishmentService.Request request,Order order,JsonNode payload) {
        var items = itemMap(order);
        var lines = payload.path("lines");
        var revision = payload.path("revision");
        String work = payload.path("workId").asString();
        if (!lines.isArray() || lines.size() != items.size() || !revision.isInt() || revision.asInt() < 1
            || work.isBlank()) throw ApiFailure.invalid();
        var picked = new HashMap<Long,Integer>();
        for (JsonNode node : lines) {
            Item i = line(items,node);
            var good = node.path("pickedQty");
            var missing = node.path("shortageQty");
            if (!good.isInt() || !missing.isInt() || good.asInt() < 0 || missing.asInt() < 0
                || (long)good.asInt()+missing.asInt()!=i.qty() || picked.put(i.id(),good.asInt()) != null)
                throw ApiFailure.invalid();
        }
        boolean shortage = false;
        for (Item i : items(order.id())) {
            int quantity = picked.get(i.id());
            db.update("UPDATE outbound_order_item SET picked_qty=? WHERE id=?",quantity,i.id());
            int missing = i.qty()-quantity;
            if (missing == 0) continue;
            holdShortage(order,i,missing);
            db.update("INSERT INTO picking_shortage(order_item_id,picking_revision,work_id,held_qty) VALUES (?,?,?,?)",
                i.id(),revision.asInt(),work,missing);
            shortage = true;
        }
        db.update("UPDATE outbound_order SET picking_revision=? WHERE id=?",revision.asInt(),order.id());
        if (shortage) {
            db.update("UPDATE replenishment_request SET version=version+1 WHERE id=?",request.id());
            db.update("""
                INSERT INTO request_status_history(request_id,resulting_version,from_status,to_status,reason,outbound_order_id)
                VALUES (?,?,?,?,?,?)
                """,request.id(),request.version()+1,request.status().name(),request.status().name(),"WMS 피킹 부족 확정",order.id());
        }
        return shortage;
    }

    private List<Allocation> remainingAllocations(long orderItemId) {
        return db.query("""
            SELECT a.reservation_id,a.qty-a.released_qty-a.consumed_qty,r.balance_id FROM dispatch_allocation a
            JOIN inventory_reservation r ON r.id=a.reservation_id WHERE a.order_item_id=?
            AND a.qty>a.released_qty+a.consumed_qty ORDER BY r.balance_id,r.id
            """,(r,n) -> new Allocation(r.getLong(1),r.getInt(2),r.getLong(3)),orderItemId);
    }

    private void holdShortage(Order order,Item item,int missing) {
        int remaining = missing;
        for (Allocation a : remainingAllocations(item.id())) {
            int qty = Math.min(remaining,a.qty());
            if (db.update("""
                UPDATE inventory_balance SET normal_qty=normal_qty-?,reserved_qty=reserved_qty-?,held_qty=held_qty+?
                WHERE id=? AND reserved_qty>=?
                """,qty,qty,qty,a.balance(),qty) != 1) throw ApiFailure.invalid();
            db.update("UPDATE inventory_reservation SET released_qty=released_qty+? WHERE id=?",qty,a.reservation());
            db.update("UPDATE dispatch_allocation SET released_qty=released_qty+? WHERE order_item_id=? AND reservation_id=?",
                qty,item.id(),a.reservation());
            db.update("""
                INSERT INTO inventory_movement(reservation_id,outbound_order_id,reserved_delta,normal_delta,held_delta)
                VALUES (?,?,?,?,?)
                """,a.reservation(),order.id(),-qty,-qty,qty);
            remaining -= qty;
            if (remaining == 0) break;
        }
        if (remaining != 0) throw ApiFailure.invalid();
        db.update("UPDATE replenishment_request_item SET reserved_qty=reserved_qty-?,open_qty=open_qty+? WHERE id=?",
            missing,missing,item.requestItemId());
    }

    private void validateUnshipped(Order order,JsonNode lines) {
        if (!lines.isArray()) throw ApiFailure.invalid();
        var expected = new HashMap<String,Integer>();
        for (Item i : items(order.id())) {
            if (i.picked() == null) throw ApiFailure.invalid();
            if (i.qty() > i.picked()) expected.put(Long.toString(i.id()),i.qty()-i.picked());
        }
        for (JsonNode line : lines) {
            var qty = line.path("quantity");
            if (!qty.isInt() || !Objects.equals(expected.remove(line.path("lineId").asString()),qty.asInt()))
                throw ApiFailure.invalid();
        }
        if (!expected.isEmpty()) throw ApiFailure.invalid();
    }

    private void validateCartons(Order order,JsonNode cartons) {
        if (!cartons.isArray() || cartons.isEmpty()) throw ApiFailure.invalid();
        var items = itemMap(order);
        var packed = new HashMap<Long,Long>();
        var names = new HashSet<String>();
        for (JsonNode carton : cartons) {
            String name = carton.path("cartonNo").asString();
            JsonNode lines = carton.path("lines");
            if (name.isBlank() || !names.add(name) || !lines.isArray() || lines.isEmpty()) throw ApiFailure.invalid();
            var seen = new HashSet<Long>();
            for (JsonNode node : lines) {
                Item item = line(items,node);
                int qty = node.path("quantity").asInt(-1);
                if (!seen.add(item.id()) || qty <= 0) throw ApiFailure.invalid();
                packed.merge(item.id(),(long)qty,Long::sum);
            }
        }
        if (items.values().stream().anyMatch(item -> item.picked() == null || packed.getOrDefault(item.id(),0L) != item.picked().longValue()))
            throw ApiFailure.invalid();
    }

    private void ship(ReplenishmentService.Request request,Order order,JsonNode cartons) {
        for (Item item : items(order.id())) {
            var allocations = remainingAllocations(item.id());
            if (item.picked() == null || allocations.stream().mapToLong(Allocation::qty).sum() != item.picked())
                throw ApiFailure.invalid();
            for (Allocation a : allocations) {
                db.update("UPDATE inventory_balance SET normal_qty=normal_qty-?,reserved_qty=reserved_qty-? WHERE id=?",
                    a.qty(),a.qty(),a.balance());
                db.update("UPDATE inventory_reservation SET consumed_qty=consumed_qty+? WHERE id=?",a.qty(),a.reservation());
                db.update("UPDATE dispatch_allocation SET consumed_qty=consumed_qty+? WHERE order_item_id=? AND reservation_id=?",a.qty(),item.id(),a.reservation());
                db.update("INSERT INTO inventory_movement(reservation_id,outbound_order_id,reserved_delta,normal_delta) VALUES (?,?,?,?)",
                    a.reservation(),order.id(),-a.qty(),-a.qty());
            }
            db.update("UPDATE replenishment_request_item SET reserved_qty=reserved_qty-?,shipped_qty=shipped_qty+? WHERE id=?",
                item.picked(),item.picked(),item.requestItemId());
        }
        for (JsonNode carton : cartons) {
            long id = db.queryForObject("INSERT INTO outbound_carton(order_id,carton_no) VALUES (?,?) RETURNING id",
                Long.class,order.id(),carton.path("cartonNo").asString());
            for (JsonNode node : carton.path("lines"))
                db.update("INSERT INTO outbound_carton_item VALUES (?,?,?)",id,
                    Long.parseLong(node.path("lineId").asString()),node.path("quantity").asInt());
        }
        db.update("UPDATE replenishment_request SET version=version+1 WHERE id=?",request.id());
        db.update("""
            INSERT INTO request_status_history(request_id,resulting_version,from_status,to_status,reason,outbound_order_id)
            VALUES (?,?,?,?,?,?)
            """,request.id(),request.version()+1,request.status().name(),request.status().name(),"WMS 출고 확정",order.id());
    }
}

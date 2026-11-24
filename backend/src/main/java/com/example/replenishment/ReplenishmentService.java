package com.example.replenishment;

import java.util.*;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import static com.example.replenishment.ReplenishmentApi.*;

@Service
class ReplenishmentService {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    ReplenishmentService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    record Request(long id, long source, long destination, long actor, RequestStatus status,
                   long version, boolean partial, String date, String submitted) {}
    record Line(long id, long variant, String sku, int requested, int open, int reserved, int shipped, int closed) {}
    record Saved(String hash, String response) {}

    private static final String REQUESTS = """
        SELECT id,source_location_id,destination_location_id,requested_by,status,version,
               allow_partial,desired_receipt_date,submitted_at FROM replenishment_request
        """;

    private Request mapRequest(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Request(row.getLong(1),row.getLong(2),row.getLong(3),row.getLong(4),
            RequestStatus.valueOf(row.getString(5)),row.getLong(6),row.getBoolean(7),row.getString(8),
            row.getObject(9, OffsetDateTime.class).toInstant().toString());
    }

    Request request(long id, boolean lock) {
        return db.query(REQUESTS + " WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::mapRequest, id)
            .stream().findFirst().orElseThrow(() -> new ApiFailure(404, "REQUEST_NOT_FOUND"));
    }

    List<Line> lines(long id) {
        return db.query("""
            SELECT i.id,i.variant_id,v.sku_code,i.requested_qty,i.open_qty,i.reserved_qty,i.shipped_qty,i.closed_qty
            FROM replenishment_request_item i JOIN product_variant v ON v.id=i.variant_id
            WHERE i.request_id=? ORDER BY i.variant_id
            """, (r,n) -> new Line(r.getLong(1),r.getLong(2),r.getString(3),r.getInt(4),
                r.getInt(5),r.getInt(6),r.getInt(7),r.getInt(8)), id);
    }

    private Map<String,Object> detail(Request r) {
        var result = new LinkedHashMap<String,Object>();
        result.put("id", Long.toString(r.id()));
        result.put("requestNo", "REQ-" + r.id());
        result.put("sourceLocationId", Long.toString(r.source()));
        result.put("destinationLocationId", Long.toString(r.destination()));
        result.put("requestedBy", Long.toString(r.actor()));
        result.put("status", r.status());
        result.put("version", Long.toString(r.version()));
        result.put("allowPartial", r.partial());
        result.put("desiredReceiptDate", r.date());
        result.put("submittedAt", r.submitted());
        var orders = db.query("SELECT id,status FROM outbound_order WHERE request_id=? ORDER BY id",
            (row,n) -> Map.of("id",row.getString(1),"status",OutboundStatus.valueOf(row.getString(2))),r.id());
        result.put("outboundOrders",orders);
        result.put("receivedQty",db.queryForObject("""
            SELECT coalesce(sum(r.received_qty),0) FROM receiving_line_totals r
            JOIN outbound_order_item i ON i.id=r.outbound_order_item_id
            JOIN outbound_order o ON o.id=i.order_id WHERE o.request_id=?
            """,Long.class,r.id()));
        if (!orders.isEmpty()) result.put("outboundOrderId",orders.getLast().get("id"));
        result.put("lines", lines(r.id()).stream().map(l -> Map.of(
            "id", Long.toString(l.id()), "variantId", Long.toString(l.variant()), "skuCode", l.sku(),
            "requestedQty", l.requested(), "openQty", l.open(), "reservedQty", l.reserved(),
            "shippedQty", l.shipped(), "closedQty", l.closed())).toList());
        return result;
    }

    long actor(String username) {
        return db.query("SELECT id FROM app_user WHERE login_id=? AND active", (r,n) -> r.getLong(1), username)
            .stream().findFirst().orElseThrow(() -> new ApiFailure(403, "FORBIDDEN"));
    }

    Object session(String username) {
        long userId = actor(username);
        var roles = db.query("SELECT role_code FROM user_role WHERE user_id=? ORDER BY role_code",
            (row, index) -> row.getString(1), userId);
        var stores = db.query("""
            SELECT l.id FROM location l JOIN user_location ul ON ul.location_id=l.id
            WHERE ul.user_id=? AND ul.active AND l.active AND l.type='STORE' ORDER BY l.id
            """, (row, index) -> row.getString(1), userId);
        var warehouses = db.query("SELECT id FROM location WHERE active AND type='WAREHOUSE' ORDER BY id",
            (row, index) -> row.getString(1));
        var approvalWarehouses = warehouses.stream()
            .filter(id -> allowed(userId, "APPROVER", Long.parseLong(id))).toList();
        return Map.of("username", username, "roles", roles, "storeIds", stores,
            "warehouseIds", warehouses, "approvalWarehouseIds", approvalWarehouses, "outboundWarehouseIds", warehouses.stream()
                .filter(id -> allowed(userId, "LOGISTICS_OPERATOR", Long.parseLong(id))).toList());
    }

    boolean allowed(long actor, String role, long location) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM user_role ur JOIN user_location ul USING(user_id)
              JOIN location l ON l.id=ul.location_id WHERE ur.user_id=? AND ur.role_code=?
              AND l.id=? AND ul.active AND l.active)
            """, Boolean.class, actor, role, location));
    }

    void require(boolean allowed) {
        if (!allowed) throw new ApiFailure(403, "FORBIDDEN");
    }

    private boolean location(long id, String type) {
        return Boolean.TRUE.equals(db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM location WHERE id=? AND type=? AND active)", Boolean.class,id,type));
    }

    long number(String input, boolean zero) {
        if (input == null || !input.matches(zero ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}"))
            throw ApiFailure.invalid();
        try {
            return Long.parseLong(input);
        } catch (NumberFormatException ex) {
            throw ApiFailure.invalid();
        }
    }

    void key(String key) {
        if (!key.matches("[A-Za-z0-9._-]{1,128}")) throw ApiFailure.invalid();
    }

    String hash(Object body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<String,Object> replay(Saved saved, String hash) {
        if (!saved.hash().equals(hash)) throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT");
        return json.readValue(saved.response(), new TypeReference<Map<String,Object>>() {});
    }

    private void page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw ApiFailure.invalid();
    }

    private Object pageResult(List<?> rows, int size) {
        return Map.of("items", rows.subList(0, Math.min(size, rows.size())), "hasNext", rows.size() > size);
    }

    Object variants(int page, int size, String q) {
        page(page,size);
        if (q.length() > 100) throw ApiFailure.invalid();
        var rows = db.query("""
            SELECT v.id,v.sku_code,p.name,v.color_code,v.size_code FROM product_variant v
            JOIN product p ON p.id=v.product_id WHERE p.active AND v.active
            AND (strpos(lower(v.sku_code), lower(?))>0 OR strpos(lower(p.name), lower(?))>0)
            ORDER BY v.sku_code,v.id LIMIT ? OFFSET ?
            """, (r,n) -> Map.of("variantId",r.getString(1),"skuCode",r.getString(2),
                "productName",r.getString(3),"colorCode",r.getString(4),"sizeCode",r.getString(5)),
            q,q,size+1,(long)page*size);
        return pageResult(rows,size);
    }

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    Object requests(String username, int page, int size, String status) {
        page(page,size);
        if (status != null) {
            try {
                RequestStatus.valueOf(status);
            } catch (IllegalArgumentException invalidStatus) {
                throw ApiFailure.invalid();
            }
        }
        long actor = actor(username);
        require(Boolean.TRUE.equals(db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM user_role WHERE user_id=?)",Boolean.class,actor)));
        var rows = db.query(REQUESTS + """
            r WHERE (?::text IS NULL OR r.status=?) AND EXISTS(
              SELECT 1 FROM user_role ur JOIN user_location ul USING(user_id)
              JOIN location l ON l.id=ul.location_id WHERE ur.user_id=? AND ul.active AND l.active
              AND ((ur.role_code='STORE' AND l.id=r.destination_location_id)
                OR (ur.role_code IN ('APPROVER','LOGISTICS_OPERATOR') AND l.id=r.source_location_id)))
            ORDER BY submitted_at DESC,id DESC LIMIT ? OFFSET ?
            """, this::mapRequest,status,status,actor,size+1,(long)page*size);
        return pageResult(rows.stream().map(this::detail).toList(),size);
    }

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    Object get(String username, String rawId) {
        long actor = actor(username);
        Request r = request(number(rawId,false),false);
        require(allowed(actor,"STORE",r.destination()) || allowed(actor,"APPROVER",r.source()) || allowed(actor,"LOGISTICS_OPERATOR",r.source()));
        return detail(r);
    }

    @Transactional
    Map<String,Object> submit(String username, String key, Submission body) {
        key(key);
        long actor = actor(username);
        long source = number(body.sourceLocationId(),false);
        long destination = number(body.destinationLocationId(),false);
        require(allowed(actor,"STORE",destination));
        if (body.lines() == null || body.lines().isEmpty() || body.allowPartial() == null
            || body.desiredReceiptDate() == null) throw ApiFailure.invalid();
        var items = new TreeMap<Long,Integer>();
        for (SubmitLine l : body.lines()) {
            if (l == null || l.requestedQty() <= 0 || items.put(number(l.variantId(),false),l.requestedQty()) != null)
                throw ApiFailure.invalid();
        }
        String hash = hash(List.of(source,destination,body.desiredReceiptDate(),body.allowPartial(),items));
        if (db.update("INSERT INTO submission_result(actor_id,idempotency_key,request_hash) VALUES (?,?,?) ON CONFLICT DO NOTHING",
            actor,key,hash) == 0) {
            return replay(db.queryForObject("SELECT request_hash,response FROM submission_result WHERE actor_id=? AND idempotency_key=?",
                (r,n) -> new Saved(r.getString(1),r.getString(2)),actor,key),hash);
        }
        if (!location(source,"WAREHOUSE") || !location(destination,"STORE")) throw ApiFailure.invalid();
        long id = db.queryForObject("""
            INSERT INTO replenishment_request(source_location_id,destination_location_id,requested_by,
              allow_partial,desired_receipt_date,status) VALUES (?,?,?,?,?,'SUBMITTED') RETURNING id
            """,Long.class,source,destination,actor,body.allowPartial(),body.desiredReceiptDate());
        for (var item : items.entrySet()) {
            int inserted = db.update("""
                INSERT INTO replenishment_request_item(request_id,variant_id,requested_qty,open_qty)
                SELECT ?,v.id,?,? FROM product_variant v JOIN product p ON p.id=v.product_id
                WHERE v.id=? AND v.active AND p.active
                """,id,item.getValue(),item.getValue(),item.getKey());
            if (inserted != 1) throw ApiFailure.invalid();
        }
        Request request = request(id,false);
        history(request, null, actor, null);
        var result = detail(request);
        db.update("UPDATE submission_result SET request_id=?,response=?::jsonb WHERE actor_id=? AND idempotency_key=?",
            id,json.writeValueAsString(result),actor,key);
        return result;
    }

    private void history(Request current,RequestStatus previous,long actor,String reason) {
        db.update("""
            INSERT INTO request_status_history(request_id,resulting_version,from_status,to_status,actor_id,reason)
            VALUES (?,?,?,?,?,?)
            """,current.id(),current.version(),previous == null ? null : previous.name(),current.status().name(),actor,reason);
    }

    Map<String,Object> previous(String type,Request r,long actor,String key,String hash) {
        return db.query("""
            SELECT request_hash,response FROM command_result
            WHERE command_type=? AND request_id=? AND actor_id=? AND idempotency_key=?
            """,(row,n) -> new Saved(row.getString(1),row.getString(2)),type,r.id(),actor,key)
            .stream().findFirst().map(saved -> replay(saved,hash)).orElse(null);
    }

    void save(String type,Request r,long actor,String key,String hash,Map<String,Object> result) {
        db.update("""
            INSERT INTO command_result(command_type,request_id,actor_id,idempotency_key,request_hash,response)
            VALUES (?,?,?,?,?,?::jsonb)
            """,type,r.id(),actor,key,hash,json.writeValueAsString(result));
    }

    void checkVersion(Request request,long expected) {
        if (request.version() != expected) throw new ApiFailure(409,"VERSION_CONFLICT")
            .with("currentVersion",Long.toString(request.version()));
    }

    Request advanceFulfillment(Request request,long actor,String reason) {
        boolean pending = Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM outbound_order WHERE request_id=? AND
                (status NOT IN ('SHIPPED','CANCELLED') OR cancellation_status='REQUESTED'))
            OR EXISTS(SELECT 1 FROM outbound_order o JOIN outbound_carton c ON c.order_id=o.id
                JOIN outbound_carton_item i ON i.carton_id=c.id
                LEFT JOIN receiving_line_totals r ON r.carton_id=c.id AND r.outbound_order_item_id=i.order_item_id
                WHERE o.request_id=? AND (i.qty<>coalesce(r.received_qty,0)+coalesce(r.lost_qty,0)+coalesce(r.damaged_qty,0)
                    OR coalesce(r.remaining_qty,0)>0 OR coalesce(r.damage_remaining_qty,0)>0))
            OR EXISTS(SELECT 1 FROM outbound_order o JOIN outbound_order_item i ON i.order_id=o.id
                JOIN picking_shortage ps ON ps.order_item_id=i.id
                LEFT JOIN picking_shortage_resolution resolution ON resolution.order_item_id=ps.order_item_id
                WHERE o.request_id=? AND resolution.order_item_id IS NULL)
            OR EXISTS(SELECT 1 FROM replenishment_request_item WHERE request_id=? AND (open_qty<>0 OR reserved_qty<>0))
            """,Boolean.class,request.id(),request.id(),request.id(),request.id()));
        RequestStatus status = request.status() == RequestStatus.FULFILLING && !pending ? RequestStatus.COMPLETED : request.status();
        return transition(request,status,actor,reason);
    }

    Request transition(Request request,RequestStatus status,long actor,String reason) {
        db.update("UPDATE replenishment_request SET status=?,version=version+1 WHERE id=?",status.name(),request.id());
        Request updated = request(request.id(),false);
        history(updated,request.status(),actor,reason);
        return updated;
    }

    private boolean validReason(String reason) {
        return reason != null && !reason.isBlank() && reason.length() <= 500;
    }

    @Transactional
    Map<String,Object> approve(String username,String id,String key,Approval body) {
        key(key);
        long actor = actor(username);
        Request r = request(number(id,false),true);
        require(allowed(actor,"APPROVER",r.source()));
        long version = number(body.expectedVersion(),true);
        if (body.lines() == null || (body.reason() != null && !validReason(body.reason()))) throw ApiFailure.invalid();
        var amounts = new TreeMap<Long,Integer>();
        for (ApprovalLine l : body.lines()) {
            if (l == null || l.approvedQty() == null || l.approvedQty() < 0
                || amounts.put(number(l.requestItemId(),false),l.approvedQty()) != null) throw ApiFailure.invalid();
        }
        String hash = hash(Arrays.asList(version,body.reason(),amounts));
        var prior = previous("APPROVE",r,actor,key,hash);
        if (prior != null) return prior;
        checkVersion(r,version);
        if (!(r.status() == RequestStatus.SUBMITTED || r.status() == RequestStatus.FULFILLING))
            throw new ApiFailure(409,"INVALID_STATE");
        if (Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM outbound_order WHERE request_id=? AND cancellation_status='REQUESTED')
            """,Boolean.class,r.id()))) throw new ApiFailure(409,"INVALID_STATE");
        var lines = lines(r.id());
        if (amounts.size() != lines.size() || amounts.values().stream().noneMatch(q -> q>0))
            throw ApiFailure.invalid();
        for (Line line : lines) {
            Integer qty = amounts.get(line.id());
            if (qty == null || qty > line.open() || (!r.partial() && qty != line.requested())
                || (qty < line.open() && !validReason(body.reason()))) throw ApiFailure.invalid();
        }
        long approval = db.queryForObject("""
            INSERT INTO replenishment_approval(request_id,decided_by,reason) VALUES (?,?,?) RETURNING id
            """,Long.class,r.id(),actor,body.reason());
        var reservations = new ArrayList<Map<String,Object>>();
        for (Line line : lines) {
            int qty = amounts.get(line.id());
            long approvalItem = db.queryForObject("""
                INSERT INTO replenishment_approval_item(approval_id,request_item_id,approved_qty)
                VALUES (?,?,?) RETURNING id
                """,Long.class,approval,line.id(),qty);
            if (qty == 0) continue;
            var balance = db.query("""
                UPDATE inventory_balance SET reserved_qty=reserved_qty+? WHERE location_id=? AND variant_id=?
                  AND normal_qty-reserved_qty>=? RETURNING id
                """,(row,n) -> row.getLong(1),qty,r.source(),line.variant(),qty);
            if (balance.isEmpty()) {
                int available = db.query("""
                    SELECT normal_qty-reserved_qty FROM inventory_balance WHERE location_id=? AND variant_id=?
                    """,(row,n) -> row.getInt(1),r.source(),line.variant()).stream().findFirst().orElse(0);
                throw new ApiFailure(409,"INSUFFICIENT_STOCK").with("shortages",List.of(Map.of(
                    "requestItemId",Long.toString(line.id()),"attemptedQty",qty,"availableQty",available)));
            }
            long reservation = db.queryForObject("""
                INSERT INTO inventory_reservation(approval_item_id,balance_id,initial_qty)
                VALUES (?,?,?) RETURNING id
                """,Long.class,approvalItem,balance.getFirst(),qty);
            movement(reservation,actor,qty);
            db.update("UPDATE replenishment_request_item SET open_qty=open_qty-?,reserved_qty=reserved_qty+? WHERE id=?",
                qty,qty,line.id());
            reservations.add(Map.of("reservationId",Long.toString(reservation),"requestItemId",Long.toString(line.id()),
                "reservedQty",qty));
        }
        var result = Map.<String,Object>of("approvalId",Long.toString(approval),"request",
            detail(transition(r,RequestStatus.FULFILLING,actor,body.reason())),"reservations",reservations);
        save("APPROVE",r,actor,key,hash,result);
        return result;
    }

    private void movement(long reservation,long actor,int delta) {
        db.update("INSERT INTO inventory_movement(reservation_id,actor_id,reserved_delta) VALUES (?,?,?)",
            reservation,actor,delta);
    }

    private record Release(long reservation,long balance,int qty) {}

    @Transactional
    Map<String,Object> cancel(String username,String id,String key,Cancellation body) {
        key(key);
        long actor = actor(username);
        Request r = request(number(id,false),true);
        require(allowed(actor,"STORE",r.destination()));
        long version = number(body.expectedVersion(),true);
        if (!validReason(body.reason())) throw ApiFailure.invalid();
        String hash = hash(List.of(version,body.reason()));
        var prior = previous("CANCEL",r,actor,key,hash);
        if (prior != null) return prior;
        checkVersion(r,version);
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM outbound_order WHERE request_id=?)",
            Boolean.class,r.id()))) throw new ApiFailure(409,"DISPATCH_ALREADY_CREATED");
        if (!EnumSet.of(RequestStatus.SUBMITTED,RequestStatus.FULFILLING).contains(r.status())) throw new ApiFailure(409,"INVALID_STATE");
        releaseForCancellation(r,actor,body.reason());
        var result = detail(transition(r,RequestStatus.CANCELLED,actor,body.reason()));
        save("CANCEL",r,actor,key,hash,result);
        return result;
    }
    void releaseForCancellation(Request r,long actor,String reason) {
        var releases = db.query("""
            SELECT rv.id,b.id,rv.initial_qty-rv.released_qty-rv.consumed_qty FROM inventory_reservation rv
            JOIN inventory_balance b ON b.id=rv.balance_id
            JOIN replenishment_approval_item ai ON ai.id=rv.approval_item_id
            JOIN replenishment_approval ap ON ap.id=ai.approval_id
            WHERE ap.request_id=? AND rv.initial_qty>rv.released_qty+rv.consumed_qty ORDER BY b.location_id,b.variant_id,rv.id
            """,(row,n) -> new Release(row.getLong(1),row.getLong(2),row.getInt(3)),r.id());
        for (Release release : releases) {
            db.update("UPDATE inventory_balance SET reserved_qty=reserved_qty-? WHERE id=?",release.qty(),release.balance());
            db.update("UPDATE inventory_reservation SET released_qty=initial_qty-consumed_qty WHERE id=?",release.reservation());
            movement(release.reservation(),actor,-release.qty());
        }
        db.update("""
            INSERT INTO request_line_closure(request_item_id,qty,actor_id,reason)
            SELECT id,open_qty+reserved_qty,?,? FROM replenishment_request_item
            WHERE request_id=? AND open_qty+reserved_qty>0
            """,actor,reason,r.id());
        db.update("""
            UPDATE replenishment_request_item SET closed_qty=closed_qty+open_qty+reserved_qty,open_qty=0,reserved_qty=0 WHERE request_id=?
            """,r.id());
    }
}

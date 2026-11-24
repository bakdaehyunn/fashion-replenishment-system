package com.example.replenishment;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShortageResolutionService {
    private final JdbcTemplate db;
    private final ReplenishmentService requests;
    private final OutboundService outbound;

    ShortageResolutionService(JdbcTemplate db,ReplenishmentService requests,OutboundService outbound) {
        this.db = db;
        this.requests = requests;
        this.outbound = outbound;
    }

    private record Shortage(long variantId,int qty,boolean resolved) {}

    @Transactional
    Map<String,Object> resolve(String username,String requestId,String orderId,String orderItemId,String key,
                               ShortageResolutionApi.Resolution input) {
        requests.key(key);
        long actor = requests.actor(username);
        var request = requests.request(requests.number(requestId,false),true);
        requests.require(requests.allowed(actor,"LOGISTICS_OPERATOR",request.source()));
        long id = requests.number(orderId,false);
        var order = outbound.order(request.id(),id);
        if (order == null || order.id()!=id) throw new ApiFailure(404,"OUTBOUND_ORDER_NOT_FOUND");
        long item = requests.number(orderItemId,false);
        var shortage = db.query("""
            SELECT r.variant_id,ps.held_qty,res.order_item_id IS NOT NULL
            FROM picking_shortage ps JOIN outbound_order_item oi ON oi.id=ps.order_item_id
            JOIN replenishment_request_item r ON r.id=oi.request_item_id
            LEFT JOIN picking_shortage_resolution res ON res.order_item_id=ps.order_item_id
            WHERE oi.order_id=? AND oi.id=?
            """,(row,n)->new Shortage(row.getLong(1),row.getInt(2),row.getBoolean(3)),id,item)
            .stream().findFirst().orElseThrow(()->new ApiFailure(404,"PICKING_SHORTAGE_NOT_FOUND"));
        long version = requests.number(input.expectedVersion(),true);
        String reason = requiredText(input.reason(),500);
        String evidence = requiredText(input.evidenceReference(),200);
        if (input.outcome()==null || !Set.of("FOUND","LOSS").contains(input.outcome())) throw ApiFailure.invalid();
        String hash = requests.hash(List.of(item,version,input.outcome(),reason,evidence));
        var replay = requests.previous("PICKING_SHORTAGE_RESOLVE",request,actor,key,hash);
        if (replay != null) return replay;
        requests.checkVersion(request,version);
        if (shortage.resolved()) throw new ApiFailure(409,"PICKING_SHORTAGE_ALREADY_RESOLVED");
        int normal = input.outcome().equals("FOUND") ? shortage.qty() : 0;
        var balances = db.query("""
            UPDATE inventory_balance SET normal_qty=normal_qty+?,held_qty=held_qty-?
            WHERE location_id=? AND variant_id=? AND held_qty>=? RETURNING id
            """,(row,n)->row.getLong(1),normal,shortage.qty(),request.source(),shortage.variantId(),shortage.qty());
        if (balances.isEmpty()) throw new ApiFailure(409,"INVALID_STATE");
        db.update("""
            INSERT INTO picking_shortage_resolution(order_item_id,balance_id,quantity,outcome,normal_delta,held_delta,actor_id,reason,evidence_reference)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,item,balances.getFirst(),shortage.qty(),input.outcome(),normal,-shortage.qty(),actor,reason,evidence);
        var updated = requests.advanceFulfillment(request,actor,"창고 피킹 차이 조사 종결");
        var result = Map.<String,Object>of("orderItemId",Long.toString(item),"quantity",shortage.qty(),
            "outcome",input.outcome(),"requestVersion",Long.toString(updated.version()),"requestStatus",updated.status());
        requests.save("PICKING_SHORTAGE_RESOLVE",request,actor,key,hash,result);
        return result;
    }

    @Transactional
    Map<String,Object> close(String username,String requestId,String itemId,String key,
                             ShortageResolutionApi.Closure input) {
        requests.key(key);
        long actor = requests.actor(username);
        var request = requests.request(requests.number(requestId,false),true);
        requests.require(requests.allowed(actor,"LOGISTICS_OPERATOR",request.source()));
        long item = requests.number(itemId,false);
        var line = requests.lines(request.id()).stream().filter(i->i.id()==item).findFirst()
            .orElseThrow(()->new ApiFailure(404,"REQUEST_ITEM_NOT_FOUND"));
        long version = requests.number(input.expectedVersion(),true);
        String reason = requiredText(input.reason(),500);
        String hash = requests.hash(List.of(item,version,reason));
        var replay = requests.previous("UNFULFILLED_CLOSE",request,actor,key,hash);
        if (replay != null) return replay;
        requests.checkVersion(request,version);
        boolean canClose = Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM outbound_order WHERE request_id=? AND status='SHIPPED')
                OR (EXISTS(SELECT 1 FROM outbound_order WHERE request_id=? AND status='CANCELLED')
                    AND NOT EXISTS(SELECT 1 FROM outbound_order WHERE request_id=? AND status<>'CANCELLED'))
            """,Boolean.class,request.id(),request.id(),request.id()));
        if (request.status() != RequestStatus.FULFILLING || !canClose
            || line.open()<=0) throw new ApiFailure(409,"INVALID_STATE");
        db.update("INSERT INTO request_line_closure(request_item_id,qty,actor_id,reason) VALUES (?,?,?,?)",
            item,line.open(),actor,reason);
        db.update("UPDATE replenishment_request_item SET closed_qty=closed_qty+open_qty,open_qty=0 WHERE id=?",item);
        var updated = requests.advanceFulfillment(request,actor,"미충족 잔량 종결: "+reason);
        var result = Map.<String,Object>of("requestItemId",Long.toString(item),"closedQty",line.open(),
            "requestVersion",Long.toString(updated.version()),"requestStatus",updated.status());
        requests.save("UNFULFILLED_CLOSE",request,actor,key,hash,result);
        return result;
    }

    private String requiredText(String value,int max) {
        if (value == null) throw ApiFailure.invalid();
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length()>max) throw ApiFailure.invalid();
        return normalized;
    }
}

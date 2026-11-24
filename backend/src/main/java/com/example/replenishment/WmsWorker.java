package com.example.replenishment;

import java.net.http.*;
import java.net.URI;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class WmsWorker {
    private final JdbcTemplate db;
    private final OutboundService outbound;
    private final String base;
    private final String token;
    private final tools.jackson.databind.ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    WmsWorker(JdbcTemplate db,OutboundService outbound,tools.jackson.databind.ObjectMapper json,@Value("${WMS_URL:http://localhost:8081}") String base,
              @Value("${WMS_TOKEN:}") String token) {
        this.db=db; this.outbound=outbound; this.base=base; this.token=token; this.json=json;
    }

    record Delivery(long id,String run,String external,String body) {}

    @Scheduled(fixedDelay=500)
    void deliver() {
        if (token.isBlank()) return;
        var claimed = db.query("""
            WITH candidate AS (
              SELECT order_id FROM wms_outbox WHERE NOT sent AND next_attempt_at<=now()
              ORDER BY next_attempt_at,order_id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE wms_outbox x SET next_attempt_at=now()+interval '12 seconds', attempt_count=x.attempt_count+1
            FROM candidate c,outbound_order o WHERE x.order_id=c.order_id AND o.id=x.order_id
            RETURNING o.id,o.run_id,o.external_id,x.payload
            """,(r,n) -> new Delivery(r.getLong(1),r.getString(2),r.getString(3),r.getString(4)));
        for (Delivery d : claimed) {
            try {
                var request=HttpRequest.newBuilder(URI.create(base+"/wms/v1/runs/"+d.run()+"/orders/"+d.external()))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("X-WMS-Token",token)
                    .PUT(HttpRequest.BodyPublishers.ofString(d.body())).build();
                int status=client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status==200 || status==201) db.update("UPDATE wms_outbox SET sent=true WHERE order_id=?",d.id());
                else {
                    if (status >= 500 || status == 408) markUnknown(d.id());
                    LoggerFactory.getLogger(getClass()).warn("WMS order {} returned {}",d.id(),status);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ex) {
                markUnknown(d.id());
                LoggerFactory.getLogger(getClass()).warn("WMS order {} delivery incomplete: {}",d.id(),ex.toString());
            }
        }
    }

    record Cancellation(long orderId,String run,String external,String commandId,String reason) {}

    @Scheduled(fixedDelay=500)
    void cancel() {
        if (token.isBlank()) return;
        var claimed = db.query("""
            WITH candidate AS (
              SELECT order_id FROM wms_cancel_outbox WHERE NOT sent AND next_attempt_at<=now()
              ORDER BY next_attempt_at,order_id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE wms_cancel_outbox x SET next_attempt_at=now()+interval '12 seconds'
            FROM candidate c,outbound_order o WHERE x.order_id=c.order_id AND o.id=x.order_id
            RETURNING o.id,o.run_id,o.external_id,x.command_id,x.reason
            """,(r,n) -> new Cancellation(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)));
        for (var item : claimed) {
            try {
                var payload = json.writeValueAsString(java.util.Map.of("commandId",item.commandId(),"reason",item.reason()));
                var request = HttpRequest.newBuilder(URI.create(base+"/wms/v1/runs/"+item.run()+"/orders/"+item.external()+"/cancel"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("X-WMS-Token",token)
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                int status = client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status == 200) db.update("UPDATE wms_cancel_outbox SET sent=true WHERE order_id=?",item.orderId());
                else LoggerFactory.getLogger(getClass()).warn("WMS cancellation {} returned {}",item.orderId(),status);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException error) {
                LoggerFactory.getLogger(getClass()).warn("WMS cancellation {} pending: {}",item.orderId(),error.toString());
            }
        }
    }

    @Scheduled(fixedDelay=500)
    void release() {
        if (token.isBlank()) return;
        var claimed = db.query("""
            WITH candidate AS (
              SELECT order_id FROM wms_release_outbox WHERE NOT sent AND next_attempt_at<=now()
              ORDER BY next_attempt_at,order_id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE wms_release_outbox x SET next_attempt_at=now()+interval '12 seconds'
            FROM candidate c,outbound_order o WHERE x.order_id=c.order_id AND o.id=x.order_id
            RETURNING o.id,o.run_id,o.external_id,x.payload
            """,(r,n) -> new Delivery(r.getLong(1),r.getString(2),r.getString(3),r.getString(4)));
        for (var item : claimed) {
            try {
                var request = HttpRequest.newBuilder(URI.create(base+"/wms/v1/runs/"+item.run()+"/orders/"+item.external()+"/release"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("X-WMS-Token",token)
                    .POST(HttpRequest.BodyPublishers.ofString(item.body())).build();
                int status = client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status == 200) db.update("UPDATE wms_release_outbox SET sent=true WHERE order_id=?",item.id());
                else if (status == 409) {
                    // If WMS cancellation won, this release can never be applied.
                    db.update("""
                        UPDATE wms_release_outbox SET sent=true WHERE order_id=?
                        AND EXISTS(SELECT 1 FROM outbound_order WHERE id=? AND status='CANCELLED')
                        """,item.id(),item.id());
                } else LoggerFactory.getLogger(getClass()).warn("WMS release {} returned {}",item.id(),status);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException error) {
                LoggerFactory.getLogger(getClass()).warn("WMS release {} pending: {}",item.id(),error.toString());
            }
        }
    }

    private void markUnknown(long id) {
        db.update("""
            UPDATE outbound_order SET status='ACCEPTANCE_UNKNOWN'
            WHERE id=? AND status='DISPATCH_PENDING' AND last_sequence=0
            """,id);
    }

    record PendingEvent(long requestId,long orderId) {}

    @Scheduled(fixedDelay=200)
    void apply() {
        for (PendingEvent event : db.query("""
            SELECT o.request_id,o.id FROM outbound_order o JOIN wms_inbox i ON i.order_id=o.id
            AND i.sequence=o.last_sequence+1 WHERE NOT i.applied ORDER BY o.id LIMIT 25
            """,(r,n)->new PendingEvent(r.getLong(1),r.getLong(2)))) {
            try {
                outbound.applyNext(event.requestId(),event.orderId());
            } catch (RuntimeException error) {
                LoggerFactory.getLogger(getClass()).warn("WMS event for request {} not applied: {}",event.requestId(),error.toString());
            }
        }
    }
}

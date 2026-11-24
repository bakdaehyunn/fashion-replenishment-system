package com.example.simulator;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class WarehouseWorker {
    private final JdbcTemplate db;
    private final NormalWarehouse warehouse;
    private final String callback;
    private final String token;
    private final String shipFirstRun;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    WarehouseWorker(JdbcTemplate db,NormalWarehouse warehouse,
                    @Value("${WMS_CALLBACK_URL:http://localhost:8080/internal/wms/events}") String callback,
                    @Value("${WMS_TOKEN:}") String token, @Value("${WMS_SHIP_FIRST_RUN_ID:}") String shipFirstRun) {
        this.db=db; this.warehouse=warehouse; this.callback=callback; this.token=token; this.shipFirstRun=shipFirstRun;
    }

    record Event(String run,String id,int sequence,String body) {}

    @Scheduled(fixedDelay=500)
    void process() {
        if (token.isBlank()) return;
        for (var order : db.query("SELECT run_id,external_id,payload FROM sim_order WHERE status='ACCEPTED' AND NOT awaiting_replay ORDER BY run_id,external_id LIMIT 25",
            (r,n)->new NormalWarehouse.Work(r.getString(1),r.getString(2),r.getString(3)))) {
            try {
                warehouse.ship(order.run(),order.id());
            } catch (RuntimeException error) {
                LoggerFactory.getLogger(getClass()).warn("Normal shipment {} pending: {}",order.id(),error.toString());
            }
        }
    }

    @Scheduled(fixedDelay=200)
    void deliver() {
        if (token.isBlank()) return;
        for (var event : db.query("""
            WITH candidate AS (
              SELECT run_id,external_id,sequence FROM sim_event e WHERE NOT sent AND next_attempt_at<=now()
                AND EXISTS (SELECT 1 FROM sim_order o WHERE o.run_id=e.run_id AND o.external_id=e.external_id AND NOT o.awaiting_replay)
                AND (e.run_id<>? OR e.sequence<>5 OR EXISTS (
                  SELECT 1 FROM sim_cancel_command c WHERE c.run_id=e.run_id AND c.external_id=e.external_id))
                AND NOT EXISTS (SELECT 1 FROM sim_event p WHERE p.run_id=e.run_id AND p.external_id=e.external_id
                  AND p.sequence<e.sequence AND NOT p.sent)
              ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE sim_event e SET next_attempt_at=now()+interval '12 seconds' FROM candidate c
            WHERE (e.run_id,e.external_id,e.sequence)=(c.run_id,c.external_id,c.sequence)
            RETURNING e.run_id,e.external_id,e.sequence,e.payload
            """,(r,n)->new Event(r.getString(1),r.getString(2),r.getInt(3),r.getString(4)),shipFirstRun)) {
            try {
                var request=HttpRequest.newBuilder(URI.create(callback)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type","application/json").header("X-WMS-Token",token)
                    .POST(HttpRequest.BodyPublishers.ofString(event.body())).build();
                int status=client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status>=200 && status<300)
                    db.update("UPDATE sim_event SET sent=true WHERE run_id=? AND external_id=? AND sequence=?",
                        event.run(),event.id(),event.sequence());
                else LoggerFactory.getLogger(getClass()).warn("Callback {} #{} returned {}",event.id(),event.sequence(),status);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                LoggerFactory.getLogger(getClass()).warn("Callback {} pending: {}",event.id(),error.toString());
            }
        }
    }
}

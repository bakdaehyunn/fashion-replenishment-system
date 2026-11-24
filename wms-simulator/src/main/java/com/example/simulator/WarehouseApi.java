package com.example.simulator;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/wms/v1/runs/{run}/orders/{id}")
class WarehouseApi {
    private final NormalWarehouse warehouse;

    private final String timeoutRun;

    WarehouseApi(NormalWarehouse warehouse,@Value("${WMS_TIMEOUT_RUN_ID:}") String timeoutRun) {
        this.warehouse = warehouse;
        this.timeoutRun = timeoutRun;
    }

    @PutMapping
    CompletableFuture<ResponseEntity<?>> accept(@PathVariable String run,@PathVariable String id,
        @RequestHeader(value="X-WMS-Token",required=false) String token,@RequestBody JsonNode payload) {
        warehouse.authorize(token);
        boolean simulateTimeout = run.equals(timeoutRun);
        boolean created = warehouse.accept(run,id,payload,simulateTimeout);
        ResponseEntity<?> response = ResponseEntity.status(created ? 201 : 200).body(warehouse.summary(run,id));
        if (created && simulateTimeout) {
            return CompletableFuture.supplyAsync(() -> response, CompletableFuture.delayedExecutor(8,TimeUnit.SECONDS));
        }
        return CompletableFuture.completedFuture(response);
    }

    record Cancellation(java.util.UUID commandId,String reason) {}

    @PostMapping("/cancel")
    Object cancel(@PathVariable String run,@PathVariable String id,
                  @RequestHeader(value="X-WMS-Token",required=false) String token,@RequestBody Cancellation input) {
        warehouse.authorize(token);
        return warehouse.cancel(run,id,input.commandId(),input.reason());
    }

    @PostMapping("/release")
    Object release(@PathVariable String run,@PathVariable String id,
                   @RequestHeader(value="X-WMS-Token",required=false) String token,@RequestBody JsonNode command) {
        warehouse.authorize(token);
        return warehouse.release(run,id,command);
    }

    @GetMapping
    Object get(@PathVariable String run,@PathVariable String id,
               @RequestHeader(value="X-WMS-Token",required=false) String token) {
        warehouse.authorize(token);
        return warehouse.summary(run,id);
    }
}

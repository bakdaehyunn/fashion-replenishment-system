package com.example.replenishment;

import java.net.URI;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
class OutboundApi {
    private static final String PATH = "/api/v1/replenishment-requests/{requestId}/outbound-orders";
    private final OutboundService service;

    OutboundApi(OutboundService service) { this.service = service; }

    record Create(String expectedVersion) {}

    @PostMapping(PATH)
    ResponseEntity<?> create(Principal principal, @PathVariable String requestId,
                             @RequestHeader("Idempotency-Key") String key, @RequestBody Create input) {
        var result = service.create(principal.getName(),requestId,key,input.expectedVersion());
        return ResponseEntity.created(URI.create(PATH.replace("{requestId}",requestId) + "/" + result.get("id"))).body(result);
    }

    @GetMapping(PATH + "/{orderId}")
    Object get(Principal principal, @PathVariable String requestId, @PathVariable String orderId) {
        return service.get(principal.getName(),requestId,orderId);
    }

    @PostMapping(PATH + "/{orderId}/cancellation")
    ResponseEntity<?> cancel(Principal principal, @PathVariable String requestId, @PathVariable String orderId,
                             @RequestHeader("Idempotency-Key") String key,
                             @RequestParam(defaultValue = "false") boolean outboundOnly,
                             @RequestBody ReplenishmentApi.Cancellation input) {
        return ResponseEntity.accepted().body(service.cancel(principal.getName(),requestId,orderId,key,input,outboundOnly));
    }

    record Release(String expectedVersion,Integer pickingRevision) {}

    @PostMapping(PATH + "/{orderId}/partial-release")
    ResponseEntity<?> release(Principal principal, @PathVariable String requestId, @PathVariable String orderId,
                              @RequestHeader("Idempotency-Key") String key, @RequestBody Release input) {
        return ResponseEntity.accepted().body(service.release(principal.getName(),requestId,orderId,key,input));
    }

    @PostMapping("/internal/wms/events")
    ResponseEntity<Void> event(@RequestHeader(value="X-WMS-Token", required=false) String token,
                               @RequestBody JsonNode event) {
        service.receive(token,event);
        return ResponseEntity.accepted().build();
    }

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<?> failure(ApiFailure error) {
        return ResponseEntity.status(error.status()).body(error.body());
    }

    @ExceptionHandler({org.springframework.web.bind.ServletRequestBindingException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<?> malformed(Exception error) { return failure(ApiFailure.invalid()); }

    @ExceptionHandler(org.springframework.dao.TransientDataAccessException.class)
    ResponseEntity<?> temporary(Exception error) { return failure(new ApiFailure(503,"TEMPORARY_CONFLICT")); }
}

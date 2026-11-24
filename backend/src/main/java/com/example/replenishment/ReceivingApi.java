package com.example.replenishment;

import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/replenishment-requests/{requestId}/outbound-orders/{orderId}/cartons")
class ReceivingApi {
    private final ReceivingService service;

    ReceivingApi(ReceivingService service) { this.service = service; }

    record Line(String outboundOrderItemId, Integer receivedQty, Integer damagedQty) {}
    record Confirmation(String expectedVersion, List<Line> lines) {}

    @GetMapping
    Object cartons(Principal principal, @PathVariable String requestId, @PathVariable String orderId) {
        return service.cartons(principal.getName(), requestId, orderId);
    }

    @PostMapping("/{cartonId}/receipt")
    @ResponseStatus(HttpStatus.CREATED)
    Object confirm(Principal principal, @PathVariable String requestId, @PathVariable String orderId,
                   @PathVariable String cartonId, @RequestHeader("Idempotency-Key") String key,
                   @RequestBody Confirmation confirmation) {
        return service.confirm(principal.getName(), requestId, orderId, cartonId, key, confirmation);
    }

    record AdditionalConfirmation(String expectedVersion, Integer receivedQty) {}

    @PostMapping("/{cartonId}/shortages/{shortageId}/additional-receipts")
    @ResponseStatus(HttpStatus.CREATED)
    Object add(Principal principal, @PathVariable String requestId, @PathVariable String orderId,
               @PathVariable String cartonId, @PathVariable String shortageId,
               @RequestHeader("Idempotency-Key") String key, @RequestBody AdditionalConfirmation input) {
        return service.add(principal.getName(),requestId,orderId,cartonId,shortageId,key,input);
    }

    record LossConfirmation(String expectedVersion, Integer lostQty, String reason, String evidenceReference) {}

    @PostMapping("/{cartonId}/shortages/{shortageId}/losses")
    @ResponseStatus(HttpStatus.CREATED)
    Object loss(Principal principal, @PathVariable String requestId, @PathVariable String orderId,
                @PathVariable String cartonId, @PathVariable String shortageId,
                @RequestHeader("Idempotency-Key") String key, @RequestBody LossConfirmation input) {
        return service.loss(principal.getName(),requestId,orderId,cartonId,shortageId,key,input);
    }

    record Disposal(String expectedVersion, Integer disposedQty, String reason, String evidenceReference) {}

    @PostMapping("/{cartonId}/damages/{damageId}/disposals")
    @ResponseStatus(HttpStatus.CREATED)
    Object dispose(Principal principal, @PathVariable String requestId, @PathVariable String orderId,
                   @PathVariable String cartonId, @PathVariable String damageId,
                   @RequestHeader("Idempotency-Key") String key, @RequestBody Disposal input) {
        return service.dispose(principal.getName(),requestId,orderId,cartonId,damageId,key,input);
    }

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<?> failure(ApiFailure error) {
        return ResponseEntity.status(error.status()).body(error.body());
    }

    @ExceptionHandler({org.springframework.web.bind.ServletRequestBindingException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<?> malformed() { return failure(ApiFailure.invalid()); }

    @ExceptionHandler(org.springframework.dao.TransientDataAccessException.class)
    ResponseEntity<?> temporary() { return failure(new ApiFailure(503, "TEMPORARY_CONFLICT")); }
}

package com.example.replenishment;

import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/replenishment-requests/{requestId}")
class ShortageResolutionApi {
    private final ShortageResolutionService service;
    ShortageResolutionApi(ShortageResolutionService service) { this.service = service; }

    record Resolution(String expectedVersion,String outcome,String reason,String evidenceReference) {}
    record Closure(String expectedVersion,String reason) {}

    @PostMapping("/outbound-orders/{orderId}/items/{orderItemId}/picking-shortage-resolution")
    Object resolve(Principal principal,@PathVariable String requestId,@PathVariable String orderId,
                   @PathVariable String orderItemId,@RequestHeader("Idempotency-Key") String key,
                   @RequestBody Resolution body) {
        return service.resolve(principal.getName(),requestId,orderId,orderItemId,key,body);
    }

    @PostMapping("/items/{itemId}/unfulfilled-closure")
    Object close(Principal principal,@PathVariable String requestId,@PathVariable String itemId,
                 @RequestHeader("Idempotency-Key") String key,@RequestBody Closure body) {
        return service.close(principal.getName(),requestId,itemId,key,body);
    }

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<?> failure(ApiFailure error) {
        return ResponseEntity.status(error.status()).body(error.body());
    }

    @ExceptionHandler({org.springframework.web.bind.ServletRequestBindingException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<?> malformed() { return failure(ApiFailure.invalid()); }

    @ExceptionHandler(org.springframework.dao.TransientDataAccessException.class)
    ResponseEntity<?> temporary() { return failure(new ApiFailure(503,"TEMPORARY_CONFLICT")); }
}

package com.example.replenishment;

import java.net.URI;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ReplenishmentApi {
    private final ReplenishmentService service;

    ReplenishmentApi(ReplenishmentService service) {
        this.service = service;
    }

    record SubmitLine(String variantId, int requestedQty) {}
    record Submission(String sourceLocationId, String destinationLocationId,
                      LocalDate desiredReceiptDate, Boolean allowPartial, List<SubmitLine> lines) {}
    record ApprovalLine(String requestItemId, Integer approvedQty) {}
    record Approval(String expectedVersion, String reason, List<ApprovalLine> lines) {}
    record Cancellation(String expectedVersion, String reason) {}

    @GetMapping("/session")
    Object session(Principal principal) {
        if (principal == null) throw new ApiFailure(401, "UNAUTHENTICATED");
        return service.session(principal.getName());
    }

    @GetMapping("/csrf")
    ResponseEntity<?> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("token", token.getToken()));
    }

    @GetMapping("/catalog/variants")
    Object variants(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                    @RequestParam(defaultValue = "") String q) {
        return service.variants(page, size, q);
    }

    @GetMapping("/replenishment-requests")
    Object requests(Principal principal, @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String status) {
        return service.requests(principal.getName(), page, size, status);
    }

    @PostMapping("/replenishment-requests")
    ResponseEntity<?> submit(Principal principal, @RequestHeader("Idempotency-Key") String key, @RequestBody Submission body) {
        var result = service.submit(principal.getName(), key, body);
        return ResponseEntity.created(URI.create("/api/v1/replenishment-requests/" + result.get("id"))).body(result);
    }

    @GetMapping("/replenishment-requests/{id}")
    Object detail(Principal principal, @PathVariable String id) {
        return service.get(principal.getName(), id);
    }

    @PostMapping("/replenishment-requests/{id}/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    Object approve(Principal principal, @PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                   @RequestBody Approval body) {
        return service.approve(principal.getName(), id, key, body);
    }

    @PostMapping("/replenishment-requests/{id}/cancellation")
    Object cancel(Principal principal, @PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                  @RequestBody Cancellation body) {
        return service.cancel(principal.getName(), id, key, body);
    }

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<?> failure(ApiFailure failure) {
        return ResponseEntity.status(failure.status()).body(failure.body());
    }

    @ExceptionHandler({org.springframework.web.bind.ServletRequestBindingException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed(Exception error) {
        return failure(ApiFailure.invalid());
    }

    @ExceptionHandler(TransientDataAccessException.class)
    ResponseEntity<?> temporary(Exception error) {
        return failure(new ApiFailure(503, "TEMPORARY_CONFLICT"));
    }
}

package com.joaopaulo.activation.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.joaopaulo.activation.application.ActivationRequestService;
import com.joaopaulo.activation.application.CreationResult;
import com.joaopaulo.activation.domain.RequestStatus;

@RestController
@RequestMapping("/api/v1/requests")
public class ActivationRequestController {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequest body,
                                                            @AuthenticationPrincipal Jwt jwt) {
        CreationResult result = service.create(body.toCommand(), ClientIdentity.of(jwt));
        ActivationRequestResponse response = ActivationRequestResponse.from(result.request());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(response.id()).toUri();

        if (result.created()) {
            return ResponseEntity.created(location).body(response);
        }
        return ResponseEntity.ok().location(location).header(REPLAYED_HEADER, "true").body(response);
    }

    @GetMapping("/{id}")
    public ActivationRequestResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ActivationRequestResponse.from(service.get(id, ClientIdentity.of(jwt)));
    }

    @GetMapping
    public PageResponse<ActivationRequestResponse> list(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) @Size(max = 64) String customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal Jwt jwt) {
        return PageResponse.from(service.list(status, customerId, ClientIdentity.of(jwt), page, size),
                ActivationRequestResponse::from);
    }
}

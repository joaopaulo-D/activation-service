package com.joaopaulo.activation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.joaopaulo.activation.domain.ActivationRequest;
import com.joaopaulo.activation.domain.ActivationRequestRepository;
import com.joaopaulo.activation.domain.RequestStatus;
import com.joaopaulo.activation.observability.CorrelationId;
import com.joaopaulo.activation.provider.ProviderClient;
import com.joaopaulo.activation.provider.ProviderResult;

@Component
public class ActivationProcessor {

    private static final Logger log = LoggerFactory.getLogger(ActivationProcessor.class);

    private final ActivationRequestRepository repository;
    private final ProviderClient providerClient;
    private final RetryPolicy retryPolicy;
    private final ProcessorProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ActivationProcessor(ActivationRequestRepository repository, ProviderClient providerClient,
                               RetryPolicy retryPolicy, ProcessorProperties properties,
                               TransactionTemplate transactionTemplate, Clock clock) {
        this.repository = repository;
        this.providerClient = providerClient;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public int processDueRequests() {
        List<ActivationRequest> claimed = transactionTemplate.execute(status -> claimDueRequests());
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            claimed.forEach(request -> executor.submit(() -> process(request)));
        }
        return claimed.size();
    }

    public int releaseStuckRequests() {
        Integer released = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            List<ActivationRequest> stuck = repository.lockStuckInProcessing(
                    now.minus(properties.processingLease()), properties.batchSize());
            stuck.forEach(request -> {
                log.warn("Solicitação {} presa em PROCESSING desde {}; devolvendo à fila",
                        request.getId(), request.getProcessingStartedAt());
                applyTransientFailure(request, new ProviderResult.TransientFailure("lease de processamento expirado"), now);
            });
            return stuck.size();
        });
        return released == null ? 0 : released;
    }

    private List<ActivationRequest> claimDueRequests() {
        Instant now = clock.instant();
        List<ActivationRequest> due = repository.lockDueForProcessing(now, properties.batchSize());
        due.forEach(request -> request.startProcessing(now));
        return due;
    }

    private void process(ActivationRequest request) {
        if (request.getCorrelationId() != null) {
            MDC.put(CorrelationId.MDC_KEY, request.getCorrelationId());
        }
        try {
            ProviderResult result = providerClient.activate(request);
            transactionTemplate.executeWithoutResult(status -> applyResult(request, result));
        } catch (OptimisticLockingFailureException e) {
            log.warn("Solicitação {} alterada concorrentemente; resultado descartado", request.getId());
        } catch (RuntimeException e) {
            // A solicitação continua em PROCESSING e será recuperada pela reconciliação.
            log.error("Erro inesperado ao processar a solicitação {}", request.getId(), e);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private void applyResult(ActivationRequest claimed, ProviderResult result) {
        ActivationRequest request = repository.findById(claimed.getId()).orElseThrow();
        if (request.getStatus() != RequestStatus.PROCESSING) {
            log.warn("Solicitação {} não está mais em PROCESSING ({}); resultado {} ignorado",
                    request.getId(), request.getStatus(), result);
            return;
        }
        Instant now = clock.instant();
        switch (result) {
            case ProviderResult.Success success -> request.complete(now);
            case ProviderResult.PermanentFailure failure -> request.fail(failure.reason(), now);
            case ProviderResult.TransientFailure failure -> applyTransientFailure(request, failure, now);
        }
        log.info("Solicitação {} agora está {} (tentativa {})", request.getId(), request.getStatus(), request.getAttempts());
    }

    private void applyTransientFailure(ActivationRequest request, ProviderResult.TransientFailure failure, Instant now) {
        if (retryPolicy.canRetry(request.getAttempts())) {
            request.scheduleRetry(retryPolicy.nextAttemptAt(request.getAttempts(), failure.retryAfter(), now),
                    failure.reason(), now);
        } else {
            request.fail("tentativas esgotadas (%d): %s".formatted(request.getAttempts(), failure.reason()), now);
        }
    }
}

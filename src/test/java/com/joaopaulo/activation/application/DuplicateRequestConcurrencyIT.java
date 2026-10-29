package com.joaopaulo.activation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.joaopaulo.activation.IntegrationTest;
import com.joaopaulo.activation.domain.ActivationRequest;

class DuplicateRequestConcurrencyIT extends IntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private ActivationRequestService service;

    @RepeatedTest(5)
    void simultaneousRequestsCreateExactlyOneRecord() throws Exception {
        NewActivationRequest command = new NewActivationRequest("EXT-RACE", "CUST-1", "INTERNET-500");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CreationResult>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.create(command, "client-a");
                }));
            }
            start.countDown();
        }

        List<CreationResult> results = new ArrayList<>();
        for (Future<CreationResult> future : futures) {
            results.add(future.get());
        }
        assertThat(results).filteredOn(CreationResult::created).hasSize(1);
        assertThat(results).extracting(result -> result.request().getId()).containsOnly(results.getFirst().request().getId());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void databaseRejectsDuplicateExternalId() {
        Instant now = Instant.now();
        repository.saveAndFlush(ActivationRequest.create("EXT-1", "CUST-1", "P1", "client-a", null, now));

        assertThatThrownBy(() -> repository.saveAndFlush(
                ActivationRequest.create("EXT-1", "CUST-2", "P2", "client-b", null, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

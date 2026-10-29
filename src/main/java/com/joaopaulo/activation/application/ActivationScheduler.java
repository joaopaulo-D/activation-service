package com.joaopaulo.activation.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "activation.processor.enabled", havingValue = "true")
public class ActivationScheduler {

    private final ActivationProcessor processor;

    public ActivationScheduler(ActivationProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${activation.processor.poll-interval}")
    public void processDueRequests() {
        processor.processDueRequests();
    }

    @Scheduled(fixedDelayString = "${activation.processor.reconcile-interval}")
    public void releaseStuckRequests() {
        processor.releaseStuckRequests();
    }
}

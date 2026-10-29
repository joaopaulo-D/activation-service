package com.joaopaulo.activation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.joaopaulo.activation.domain.ActivationRequest;
import com.joaopaulo.activation.domain.ActivationRequestRepository;

@ExtendWith(MockitoExtension.class)
class ActivationRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
    private static final String CLIENT = "client-a";
    private static final NewActivationRequest COMMAND = new NewActivationRequest("EXT-1", "CUST-1", "INTERNET-500");

    @Mock
    private ActivationRequestRepository repository;

    private ActivationRequestService service() {
        return new ActivationRequestService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ActivationRequest existing(String productCode) {
        return ActivationRequest.create("EXT-1", "CUST-1", productCode, CLIENT, null, NOW);
    }

    @Test
    void createsNewRequest() {
        when(repository.findByExternalId("EXT-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CreationResult result = service().create(COMMAND, CLIENT);

        assertThat(result.created()).isTrue();
        assertThat(result.request().getExternalId()).isEqualTo("EXT-1");
        assertThat(result.request().getClientId()).isEqualTo(CLIENT);
        assertThat(result.request().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void retryWithSameContentReturnsExistingWithoutInserting() {
        ActivationRequest existing = existing("INTERNET-500");
        when(repository.findByExternalId("EXT-1")).thenReturn(Optional.of(existing));

        CreationResult result = service().create(COMMAND, CLIENT);

        assertThat(result.created()).isFalse();
        assertThat(result.request()).isSameAs(existing);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void sameExternalIdWithDifferentContentIsRejected() {
        when(repository.findByExternalId("EXT-1")).thenReturn(Optional.of(existing("INTERNET-1000")));

        assertThatThrownBy(() -> service().create(COMMAND, CLIENT))
                .isInstanceOf(DuplicateActivationRequestException.class);
    }

    @Test
    void sameExternalIdFromAnotherClientIsRejected() {
        when(repository.findByExternalId("EXT-1")).thenReturn(Optional.of(existing("INTERNET-500")));

        assertThatThrownBy(() -> service().create(COMMAND, "client-b"))
                .isInstanceOf(DuplicateActivationRequestException.class);
    }

    @Test
    void lostInsertRaceReturnsTheWinner() {
        ActivationRequest winner = existing("INTERNET-500");
        when(repository.findByExternalId("EXT-1")).thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_activation_request_external_id"));

        CreationResult result = service().create(COMMAND, CLIENT);

        assertThat(result.created()).isFalse();
        assertThat(result.request()).isSameAs(winner);
    }

    @Test
    void integrityViolationUnrelatedToDuplicityIsPropagated() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("other");
        when(repository.findByExternalId("EXT-1")).thenReturn(Optional.empty(), Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(violation);

        assertThatThrownBy(() -> service().create(COMMAND, CLIENT)).isSameAs(violation);
    }
}

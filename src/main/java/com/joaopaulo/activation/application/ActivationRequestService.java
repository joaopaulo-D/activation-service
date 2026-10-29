package com.joaopaulo.activation.application;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.joaopaulo.activation.domain.ActivationRequest;
import com.joaopaulo.activation.domain.ActivationRequestRepository;
import com.joaopaulo.activation.domain.RequestStatus;
import com.joaopaulo.activation.observability.CorrelationId;

@Service
public class ActivationRequestService {

    private static final Logger log = LoggerFactory.getLogger(ActivationRequestService.class);

    private final ActivationRequestRepository repository;
    private final Clock clock;

    public ActivationRequestService(ActivationRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    // Sem @Transactional: no PostgreSQL a violação da UNIQUE aborta a transação,
    // então a releitura do registro vencedor precisa acontecer em outra.
    public CreationResult create(NewActivationRequest command, String clientId) {
        Optional<ActivationRequest> existing = repository.findByExternalId(command.externalId());
        if (existing.isPresent()) {
            return replay(existing.get(), command, clientId);
        }

        ActivationRequest request = ActivationRequest.create(command.externalId(), command.customerId(),
                command.productCode(), clientId, CorrelationId.current(), clock.instant());
        try {
            ActivationRequest saved = repository.saveAndFlush(request);
            log.info("Solicitação {} criada para externalId {}", saved.getId(), saved.getExternalId());
            return new CreationResult(saved, true);
        } catch (DataIntegrityViolationException e) {
            ActivationRequest winner = repository.findByExternalId(command.externalId()).orElseThrow(() -> e);
            log.info("Criação concorrente para externalId {}; usando a solicitação {}",
                    command.externalId(), winner.getId());
            return replay(winner, command, clientId);
        }
    }

    @Transactional(readOnly = true)
    public ActivationRequest get(UUID id, String clientId) {
        return repository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new ActivationRequestNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<ActivationRequest> list(RequestStatus status, String customerId, String clientId, int page, int size) {
        Specification<ActivationRequest> spec = (root, query, cb) -> cb.equal(root.get("clientId"), clientId);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (customerId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        return repository.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    private static CreationResult replay(ActivationRequest existing, NewActivationRequest command, String clientId) {
        if (!existing.hasSameContent(command.customerId(), command.productCode(), clientId)) {
            throw new DuplicateActivationRequestException(command.externalId());
        }
        return new CreationResult(existing, false);
    }
}

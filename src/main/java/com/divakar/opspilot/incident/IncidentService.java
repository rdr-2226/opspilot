package com.divakar.opspilot.incident;

import com.divakar.opspilot.agent.TriageEngine;
import com.divakar.opspilot.agent.TriageResult;
import com.divakar.opspilot.common.NotFoundException;
import com.divakar.opspilot.incident.dto.CreateIncidentRequest;
import com.divakar.opspilot.incident.dto.ReviewRequest;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {

    private final IncidentRepository repository;
    private final TriageEngine triageEngine;

    public IncidentService(IncidentRepository repository, TriageEngine triageEngine) {
        this.repository = repository;
        this.triageEngine = triageEngine;
    }

    @Transactional
    public Incident create(CreateIncidentRequest request) {
        Incident incident = new Incident();
        incident.setTitle(request.title().trim());
        incident.setServiceName(request.serviceName());
        incident.setDescription(request.description());
        incident.setSeverity(request.severity());
        incident.setStatus(IncidentStatus.OPEN);
        return repository.save(incident);
    }

    @Transactional(readOnly = true)
    public Incident get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Incident " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<Incident> list(IncidentStatus status) {
        return status == null
                ? repository.findAllByOrderByCreatedAtDesc()
                : repository.findByStatusOrderByCreatedAtDesc(status);
    }

    /**
     * Runs the AI agents and stores their proposal. The incident then waits for a human.
     * Deliberately not @Transactional: the LLM call can take many seconds and we don't want
     * to hold a database transaction open during it.
     */
    public Incident triage(Long id) {
        Incident incident = get(id);
        requireStatus(incident, Set.of(IncidentStatus.OPEN, IncidentStatus.REJECTED), "triage");

        TriageResult result = triageEngine.triage(incident);

        incident.setRootCause(result.rootCause());
        incident.setSuggestedFix(String.join("\n", result.fixSteps()));
        incident.setConfidence(result.confidence());
        incident.setRunbookUsed(result.runbookUsed());
        incident.setReviewedBy(null);
        incident.setReviewComment(null);
        incident.setStatus(IncidentStatus.PENDING_APPROVAL);
        return repository.save(incident);
    }

    /** Human-in-the-loop: nothing the AI proposes is acted on until a person approves it. */
    @Transactional
    public Incident approve(Long id, ReviewRequest review) {
        return review(id, review, IncidentStatus.APPROVED);
    }

    @Transactional
    public Incident reject(Long id, ReviewRequest review) {
        return review(id, review, IncidentStatus.REJECTED);
    }

    @Transactional
    public Incident resolve(Long id) {
        Incident incident = get(id);
        requireStatus(incident, Set.of(IncidentStatus.APPROVED), "resolve");
        incident.setStatus(IncidentStatus.RESOLVED);
        return repository.save(incident);
    }

    private Incident review(Long id, ReviewRequest review, IncidentStatus decision) {
        Incident incident = get(id);
        requireStatus(incident, Set.of(IncidentStatus.PENDING_APPROVAL), decision.name().toLowerCase());
        incident.setReviewedBy(review.reviewer());
        incident.setReviewComment(review.comment());
        incident.setStatus(decision);
        return repository.save(incident);
    }

    private static void requireStatus(Incident incident, Set<IncidentStatus> allowed, String action) {
        if (!allowed.contains(incident.getStatus())) {
            throw new IllegalStateException("Cannot " + action + " incident " + incident.getId()
                    + " in status " + incident.getStatus() + " (allowed: " + allowed + ")");
        }
    }
}

package com.divakar.opspilot.incident;

import com.divakar.opspilot.incident.dto.CreateIncidentRequest;
import com.divakar.opspilot.incident.dto.IncidentResponse;
import com.divakar.opspilot.incident.dto.ReviewRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<IncidentResponse> create(@Valid @RequestBody CreateIncidentRequest request) {
        Incident created = service.create(request);
        return ResponseEntity.created(URI.create("/api/incidents/" + created.getId()))
                .body(IncidentResponse.from(created));
    }

    @GetMapping
    public List<IncidentResponse> list(@RequestParam(required = false) IncidentStatus status) {
        return service.list(status).stream().map(IncidentResponse::from).toList();
    }

    @GetMapping("/{id}")
    public IncidentResponse get(@PathVariable Long id) {
        return IncidentResponse.from(service.get(id));
    }

    /** Runs the multi-agent AI pipeline on this incident. */
    @PostMapping("/{id}/triage")
    public IncidentResponse triage(@PathVariable Long id) {
        return IncidentResponse.from(service.triage(id));
    }

    @PostMapping("/{id}/approve")
    public IncidentResponse approve(@PathVariable Long id, @Valid @RequestBody ReviewRequest review) {
        return IncidentResponse.from(service.approve(id, review));
    }

    @PostMapping("/{id}/reject")
    public IncidentResponse reject(@PathVariable Long id, @Valid @RequestBody ReviewRequest review) {
        return IncidentResponse.from(service.reject(id, review));
    }

    @PostMapping("/{id}/resolve")
    public IncidentResponse resolve(@PathVariable Long id) {
        return IncidentResponse.from(service.resolve(id));
    }
}

package com.divakar.opspilot.incident;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divakar.opspilot.agent.TriageEngine;
import com.divakar.opspilot.agent.TriageException;
import com.divakar.opspilot.agent.TriageResult;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end API tests of the incident workflow.
 * The AI engine is mocked, so these run fast, offline and without an API key.
 */
@SpringBootTest
class IncidentApiTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private IncidentRepository repository;

    @MockitoBean
    private TriageEngine triageEngine;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        repository.deleteAll();
    }

    private long createIncident() throws Exception {
        String body = """
                {"title": "Payments failing with 500",
                 "serviceName": "payment-service",
                 "description": "Checkout errors since 10:00",
                 "severity": "HIGH"}
                """;
        String json = mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    @Test
    void fullWorkflow_createTriageApproveResolve() throws Exception {
        when(triageEngine.triage(any())).thenReturn(new TriageResult(
                "DB connection pool exhausted by slow unindexed query",
                List.of("Add index on merchant_id", "Raise pool size to 20"),
                0.86,
                "db-connection-pool-exhaustion.md"));

        long id = createIncident();

        mvc.perform(post("/api/incidents/{id}/triage", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.suggestedFix", hasSize(2)))
                .andExpect(jsonPath("$.confidence").value(0.86))
                .andExpect(jsonPath("$.runbookUsed").value("db-connection-pool-exhaustion.md"));

        mvc.perform(post("/api/incidents/{id}/approve", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewer\": \"divakar\", \"comment\": \"Looks right\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedBy").value("divakar"));

        mvc.perform(post("/api/incidents/{id}/resolve", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        mvc.perform(get("/api/incidents").param("status", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void cannotApproveBeforeTriage() throws Exception {
        long id = createIncident();

        mvc.perform(post("/api/incidents/{id}/approve", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewer\": \"divakar\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectedIncidentCanBeTriagedAgain() throws Exception {
        when(triageEngine.triage(any())).thenReturn(
                new TriageResult("Guess", List.of("Restart"), 0.3, "none"));
        long id = createIncident();

        mvc.perform(post("/api/incidents/{id}/triage", id)).andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{id}/reject", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewer\": \"divakar\", \"comment\": \"Not enough evidence\"}"))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(post("/api/incidents/{id}/triage", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    void invalidRequestIsRejected() throws Exception {
        String body = """
                {"title": "", "serviceName": "../etc/passwd", "severity": "HIGH"}
                """;
        mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verify(triageEngine, never()).triage(any());
    }

    @Test
    void aiFailureReturns502AndKeepsIncidentOpen() throws Exception {
        when(triageEngine.triage(any())).thenThrow(new TriageException("model timeout"));
        long id = createIncident();

        mvc.perform(post("/api/incidents/{id}/triage", id)).andExpect(status().isBadGateway());
        mvc.perform(get("/api/incidents/{id}", id))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void incidentAlreadyBeingTriagedReturns409() throws Exception {
        long id = createIncident();
        // Simulate another request that has already claimed this incident.
        repository.transition(id, IncidentStatus.OPEN, IncidentStatus.TRIAGING);

        mvc.perform(post("/api/incidents/{id}/triage", id)).andExpect(status().isConflict());
        verify(triageEngine, never()).triage(any());
    }

    @Test
    void failedTriageReturnsRejectedIncidentToRejected() throws Exception {
        when(triageEngine.triage(any()))
                .thenReturn(new TriageResult("Guess", List.of("Restart"), 0.3, "none"))
                .thenThrow(new TriageException("model timeout"));
        long id = createIncident();
        mvc.perform(post("/api/incidents/{id}/triage", id)).andExpect(status().isOk());
        mvc.perform(post("/api/incidents/{id}/reject", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewer\": \"divakar\"}"));

        mvc.perform(post("/api/incidents/{id}/triage", id)).andExpect(status().isBadGateway());
        mvc.perform(get("/api/incidents/{id}", id)).andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void unknownIncidentReturns404() throws Exception {
        mvc.perform(get("/api/incidents/{id}", 999_999)).andExpect(status().isNotFound());
    }
}

package com.divakar.opspilot.incident;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    List<Incident> findByStatusOrderByCreatedAtDesc(IncidentStatus status);

    List<Incident> findAllByOrderByCreatedAtDesc();

    /**
     * Atomically moves an incident from one status to another, only if it is currently in {@code from}.
     * Runs as a single SQL UPDATE ... WHERE status = :from, so when two requests race,
     * the database lets exactly one of them win.
     *
     * @return 1 if the transition happened, 0 if the incident was not in {@code from}
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Incident i set i.status = :to where i.id = :id and i.status = :from")
    int transition(@Param("id") Long id,
                   @Param("from") IncidentStatus from,
                   @Param("to") IncidentStatus to);
}

package io.flakehunter.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A unique test within a project, identified by {@code testKey = suite::name}.
 * Rows are created by a concurrency-safe SQL upsert during ingestion (see TestCaseWriter),
 * so this entity is only used for reads and quarantine updates.
 */
@Entity
@Table(name = "test_cases")
public class TestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "test_key", nullable = false, updatable = false)
    private String testKey;

    @Column(nullable = false)
    private String suite;

    @Column(nullable = false)
    private String name;

    private String file;

    private boolean quarantined;

    @Column(name = "quarantine_reason")
    private String quarantineReason;

    @Column(name = "quarantined_at")
    private Instant quarantinedAt;

    protected TestCase() {
        // for JPA
    }

    public void quarantine(String reason) {
        this.quarantined = true;
        this.quarantineReason = reason;
        this.quarantinedAt = Instant.now();
    }

    public void release() {
        this.quarantined = false;
        this.quarantineReason = null;
        this.quarantinedAt = null;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getTestKey() {
        return testKey;
    }

    public String getSuite() {
        return suite;
    }

    public String getName() {
        return name;
    }

    public String getFile() {
        return file;
    }

    public boolean isQuarantined() {
        return quarantined;
    }

    public String getQuarantineReason() {
        return quarantineReason;
    }

    public Instant getQuarantinedAt() {
        return quarantinedAt;
    }
}

package io.flakehunter.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One CI execution of a project's test suite (one uploaded report). */
@Entity
@Table(name = "test_runs")
public class TestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "commit_sha", nullable = false, length = 40)
    private String commitSha;

    @Column(nullable = false)
    private String branch;

    @Column(name = "build_id")
    private String buildId;

    private int total;
    private int passed;
    private int failed;
    private int skipped;

    @Column(name = "duration_ms")
    private long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TestRun() {
        // for JPA
    }

    public TestRun(Long projectId, String commitSha, String branch, String buildId,
                   int total, int passed, int failed, int skipped, long durationMs) {
        this.projectId = projectId;
        this.commitSha = commitSha;
        this.branch = branch;
        this.buildId = buildId;
        this.total = total;
        this.passed = passed;
        this.failed = failed;
        this.skipped = skipped;
        this.durationMs = durationMs;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getBranch() {
        return branch;
    }

    public String getBuildId() {
        return buildId;
    }

    public int getTotal() {
        return total;
    }

    public int getPassed() {
        return passed;
    }

    public int getFailed() {
        return failed;
    }

    public int getSkipped() {
        return skipped;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

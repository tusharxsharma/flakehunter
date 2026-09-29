package io.flakehunter.api.persistence;

import io.flakehunter.api.domain.TestStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-side SQL for analytics. Kept as explicit SQL so the query plans are obvious and index-backed. */
@Component
public class AnalyticsQueries {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public AnalyticsQueries(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /** The newest {@code limit} runs of a project, newest first. Uses idx_test_runs_project_id_desc. */
    public List<RunRef> recentRuns(long projectId, int limit) {
        return jdbc.query("""
                        SELECT id, commit_sha, passed, failed
                        FROM test_runs WHERE project_id = ? ORDER BY id DESC LIMIT ?
                        """,
                (rs, i) -> new RunRef(rs.getLong("id"), rs.getString("commit_sha"),
                        rs.getInt("passed"), rs.getInt("failed")),
                projectId, limit);
    }

    /** All results for the given runs, oldest run first, preserving in-run attempt order. */
    public List<ResultRef> resultsForRuns(List<Long> runIds) {
        if (runIds.isEmpty()) {
            return List.of();
        }
        return named.query("""
                        SELECT test_case_id, run_id, status
                        FROM test_results WHERE run_id IN (:runIds)
                        ORDER BY run_id, id
                        """,
                new MapSqlParameterSource("runIds", runIds),
                (rs, i) -> new ResultRef(rs.getLong("test_case_id"), rs.getLong("run_id"),
                        TestStatus.valueOf(rs.getString("status"))));
    }

    public List<HistoryRow> testHistory(long testCaseId, int limit) {
        return jdbc.query("""
                        SELECT r.run_id, tr.commit_sha, tr.branch, tr.created_at, r.status, r.duration_ms, r.failure_message
                        FROM test_results r
                        JOIN test_runs tr ON tr.id = r.run_id
                        WHERE r.test_case_id = ?
                        ORDER BY r.run_id DESC, r.id DESC
                        LIMIT ?
                        """,
                (rs, i) -> new HistoryRow(
                        rs.getLong("run_id"),
                        rs.getString("commit_sha"),
                        rs.getString("branch"),
                        toInstant(rs.getTimestamp("created_at")),
                        TestStatus.valueOf(rs.getString("status")),
                        rs.getLong("duration_ms"),
                        rs.getString("failure_message")),
                testCaseId, limit);
    }

    public List<RunResultRow> resultsOfRun(long runId) {
        return jdbc.query("""
                        SELECT tc.id AS test_id, tc.test_key, tc.suite, tc.name, r.status, r.duration_ms, r.failure_message
                        FROM test_results r
                        JOIN test_cases tc ON tc.id = r.test_case_id
                        WHERE r.run_id = ?
                        ORDER BY CASE r.status WHEN 'FAILED' THEN 0 WHEN 'SKIPPED' THEN 1 ELSE 2 END, tc.test_key, r.id
                        """,
                (rs, i) -> new RunResultRow(
                        rs.getLong("test_id"),
                        rs.getString("test_key"),
                        rs.getString("suite"),
                        rs.getString("name"),
                        TestStatus.valueOf(rs.getString("status")),
                        rs.getLong("duration_ms"),
                        rs.getString("failure_message")),
                runId);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public record RunRef(long id, String commitSha, int passed, int failed) {
    }

    public record ResultRef(long testCaseId, long runId, TestStatus status) {
    }

    public record HistoryRow(long runId, String commitSha, String branch, Instant createdAt, TestStatus status,
                             long durationMs, String failureMessage) {
    }

    public record RunResultRow(long testId, String testKey, String suite, String name, TestStatus status,
                               long durationMs, String failureMessage) {
    }
}

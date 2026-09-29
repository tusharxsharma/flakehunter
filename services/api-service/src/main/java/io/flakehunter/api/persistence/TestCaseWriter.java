package io.flakehunter.api.persistence;

import io.flakehunter.api.domain.TestStatus;
import io.flakehunter.api.ingestion.ParsedTestCase;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hot write path of ingestion, using plain JDBC batches instead of JPA.
 *
 * <p>A report can contain thousands of tests. Persisting them one entity at a time would mean
 * thousands of round trips; JDBC batching sends them in a handful. Test cases are created with
 * {@code INSERT ... ON CONFLICT DO NOTHING}, which is safe when two CI jobs for the same project
 * upload at the same moment and both discover the same new test.
 */
@Component
public class TestCaseWriter {

    private static final int IN_CLAUSE_CHUNK = 1000;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public TestCaseWriter(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /** Ensures every test case exists and returns {@code testKey -> id}. */
    public Map<String, Long> upsertTestCases(long projectId, List<ParsedTestCase> cases) {
        Map<String, ParsedTestCase> byKey = new LinkedHashMap<>();
        for (ParsedTestCase c : cases) {
            byKey.putIfAbsent(c.testKey(), c);
        }

        // Steady state: almost every test already exists, so look up first and insert only the new ones.
        Map<String, Long> ids = findIds(projectId, new ArrayList<>(byKey.keySet()));
        List<ParsedTestCase> missing = byKey.values().stream()
                .filter(c -> !ids.containsKey(c.testKey()))
                .toList();

        if (!missing.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO test_cases (project_id, test_key, suite, name, file)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (project_id, test_key) DO NOTHING
                    """, missing, 500, (ps, c) -> {
                ps.setLong(1, projectId);
                ps.setString(2, c.testKey());
                ps.setString(3, c.suite());
                ps.setString(4, c.name());
                if (c.file() == null) {
                    ps.setNull(5, Types.VARCHAR);
                } else {
                    ps.setString(5, c.file());
                }
            });
            ids.putAll(findIds(projectId, missing.stream().map(ParsedTestCase::testKey).toList()));
        }
        return ids;
    }

    public void insertResults(long runId, List<ResultRow> rows) {
        jdbc.batchUpdate("""
                INSERT INTO test_results (run_id, test_case_id, status, duration_ms, failure_message)
                VALUES (?, ?, ?, ?, ?)
                """, rows, 500, (ps, row) -> {
            ps.setLong(1, runId);
            ps.setLong(2, row.testCaseId());
            ps.setString(3, row.status().name());
            ps.setLong(4, row.durationMs());
            if (row.failureMessage() == null) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, row.failureMessage());
            }
        });
    }

    private Map<String, Long> findIds(long projectId, List<String> keys) {
        Map<String, Long> ids = new HashMap<>();
        for (int from = 0; from < keys.size(); from += IN_CLAUSE_CHUNK) {
            List<String> chunk = keys.subList(from, Math.min(keys.size(), from + IN_CLAUSE_CHUNK));
            var params = new MapSqlParameterSource()
                    .addValue("projectId", projectId)
                    .addValue("keys", chunk);
            named.query("SELECT id, test_key FROM test_cases WHERE project_id = :projectId AND test_key IN (:keys)",
                    params, rs -> {
                        ids.put(rs.getString("test_key"), rs.getLong("id"));
                    });
        }
        return ids;
    }

    public record ResultRow(long testCaseId, TestStatus status, long durationMs, String failureMessage) {
    }
}

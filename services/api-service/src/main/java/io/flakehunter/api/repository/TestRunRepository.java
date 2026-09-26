package io.flakehunter.api.repository;

import io.flakehunter.api.domain.TestRun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRunRepository extends JpaRepository<TestRun, Long> {

    Optional<TestRun> findByProjectIdAndBuildId(Long projectId, String buildId);

    Optional<TestRun> findByIdAndProjectId(Long id, Long projectId);

    List<TestRun> findByProjectIdOrderByIdDesc(Long projectId, Pageable pageable);

    long countByProjectId(Long projectId);
}

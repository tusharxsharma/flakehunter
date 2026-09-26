package io.flakehunter.api.repository;

import io.flakehunter.api.domain.TestCase;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

    Optional<TestCase> findByIdAndProjectId(Long id, Long projectId);

    List<TestCase> findByProjectIdAndQuarantinedTrueOrderByTestKeyAsc(Long projectId);

    List<TestCase> findByIdIn(Collection<Long> ids);

    long countByProjectId(Long projectId);

    long countByProjectIdAndQuarantinedTrue(Long projectId);
}

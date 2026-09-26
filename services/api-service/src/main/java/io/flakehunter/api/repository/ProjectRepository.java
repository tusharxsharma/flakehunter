package io.flakehunter.api.repository;

import io.flakehunter.api.domain.Project;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByApiKeyHash(String apiKeyHash);

    boolean existsByName(String name);

    List<Project> findAllByOrderByNameAsc();
}

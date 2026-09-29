package io.flakehunter.api.service;

import io.flakehunter.api.domain.Project;
import io.flakehunter.api.repository.ProjectRepository;
import io.flakehunter.api.security.ApiKeys;
import io.flakehunter.api.web.ApiException;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projects;

    public ProjectService(ProjectRepository projects) {
        this.projects = projects;
    }

    /** Creates a project and returns its API key. The key is never stored or shown again. */
    @Transactional
    public CreatedProject create(String name) {
        if (projects.existsByName(name)) {
            throw ApiException.conflict("A project named '" + name + "' already exists");
        }
        String apiKey = ApiKeys.generate();
        try {
            Project project = projects.saveAndFlush(new Project(name, ApiKeys.hash(apiKey)));
            return new CreatedProject(project, apiKey);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent creates with the same name: the unique constraint is the source of truth.
            throw ApiException.conflict("A project named '" + name + "' already exists");
        }
    }

    @Transactional(readOnly = true)
    public List<Project> list() {
        return projects.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Project get(long projectId) {
        return projects.findById(projectId).orElseThrow(() -> ApiException.notFound("Project " + projectId));
    }

    public record CreatedProject(Project project, String apiKey) {
    }
}

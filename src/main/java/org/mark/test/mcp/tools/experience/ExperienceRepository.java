package org.mark.test.mcp.tools.experience;

import java.util.List;

public interface ExperienceRepository {

	ExperienceRecord save(ExperienceRecord record);

	ExperienceRecord getById(String id);

	List<ExperienceRecord> list(String taskType, List<String> tags, int limit);

	List<ExperienceRecord> listAll();
}

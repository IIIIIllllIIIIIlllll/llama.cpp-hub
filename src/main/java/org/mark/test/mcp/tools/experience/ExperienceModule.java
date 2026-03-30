package org.mark.test.mcp.tools.experience;

public final class ExperienceModule {

	private static final ExperienceRepository REPOSITORY = new FileExperienceRepository();
	private static final ExperienceMatcher MATCHER = new ExperienceMatcher();

	private ExperienceModule() {
	}

	public static ExperienceRepository repository() {
		return REPOSITORY;
	}

	public static ExperienceMatcher matcher() {
		return MATCHER;
	}
}

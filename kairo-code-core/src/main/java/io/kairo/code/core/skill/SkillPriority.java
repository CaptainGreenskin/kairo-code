package io.kairo.code.core.skill;

/**
 * 6-level skill priority, from lowest (MCP) to highest (MANAGED).
 *
 * <p>Higher priority skills override lower priority ones by name during merge.
 * PLUGIN, MCP, and BUNDLED are defined for future use — currently not loaded.
 */
public enum SkillPriority {
    MCP(1),        // MCP dynamic skills (reserved)
    BUNDLED(2),    // jar bundled skills (reserved)
    PLUGIN(3),     // plugin-provided skills (reserved)
    PROJECT(4),    // project-level: .kairo-code/skills/
    USER(5),       // user global: ~/.kairo-code/skills/
    MANAGED(6);    // admin-managed: ~/.kairo-code/skills/managed/

    public final int priority;

    SkillPriority(int priority) {
        this.priority = priority;
    }
}

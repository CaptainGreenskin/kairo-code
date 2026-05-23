# Plugins

Bundled units of skills + commands + agents + hooks + MCP servers,
distributable via GitHub / NPM / git / filesystem.

Backed by [kairo-plugin](https://github.com/captaingreenskin/kairo) upstream;
reads the Claude Code-compatible `plugin.json` manifest format.

## Install

```
:plugin install github:owner/repo[#ref]      # GitHub archive
:plugin install npm:@scope/package[@version] # NPM tarball
:plugin install git:https://...[#branch]     # arbitrary git
:plugin install path:./my-plugin             # local filesystem
```

After install, `enable` to activate:

```
:plugin list                        # find the id
:plugin enable <id>
:skill reload                       # pick up newly-contributed skills
```

## Authoring

Minimum viable plugin:

```
my-plugin/
├── plugin.json
└── skills/
    └── my-skill.md
```

`plugin.json`:

```json
{
  "name": "my-plugin",
  "version": "0.1.0",
  "description": "Adds my custom skill",
  "author": "@me",
  "license": "MIT"
}
```

The `skills/` directory follows the same format as user skills (see
[Skills](./skills)). Other supported directories: `commands/`, `agents/`,
`hooks/`, `mcp/`, `bin/`, `output-styles/`.

## Variable substitution

Use `${KAIRO_PLUGIN_ROOT}` in any plugin file to refer to its install
location:

```json
{
  "mcp": [{
    "command": "node",
    "args": ["${KAIRO_PLUGIN_ROOT}/server.js"]
  }]
}
```

`${CLAUDE_PLUGIN_ROOT}` is also accepted for Claude Code plugin compatibility.

## Storage

Plugins live under:

- `~/.kairo-code/plugins/cache/<sha8>/` — extracted source artifacts
- `~/.kairo-code/plugins/data/<plugin-name>/` — runtime data dirs

`:plugin uninstall <id>` removes both. `:plugin update <id>` re-fetches.

## Lifecycle events

Subscribe via `manager.events()` if you're embedding via the SDK:

```java
manager.events()
    .filter(PluginEvent.Installed.class::isInstance)
    .subscribe(e -> System.out.println("installed: " + e));
```

Useful for IDE plugins that want to refresh their UI when plugins change.

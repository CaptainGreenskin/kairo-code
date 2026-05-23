# kairo-code distribution

Three install paths, depending on what you have:

| Channel | Best for | One-line |
|---|---|---|
| **npm** | Anyone with Node — broadest reach | `npm install -g @kairo/code` |
| **Homebrew** | macOS / Linuxbrew users | `brew tap captaingreenskin/kairo && brew install kairo-code` |
| **Direct jar** | CI, Docker, air-gapped | `wget …/kairo-code-cli-<ver>-SNAPSHOT.jar && java -jar …` |

All three end up running the same fat jar. The npm and brew wrappers add
JDK detection + auto-download + sensible exit-code propagation.

## Layout

```
install/
├── npm/                   ← npm package
│   ├── package.json       ← @kairo/code metadata
│   ├── bin/kairo-code.js  ← launcher (downloads + execs jar)
│   └── README.md          ← user-facing install instructions
├── homebrew/
│   └── kairo-code.rb      ← Homebrew formula (drop into a tap repo)
└── README.md              ← (this file)
```

## Release process

When cutting a release vX.Y.Z:

1. **Build the fat jar** locally:
   ```bash
   mvn package -DskipTests -pl kairo-code-cli -am
   ```
2. **GitHub Release** — create tag `vX.Y.Z`, upload `kairo-code-cli-X.Y.Z-SNAPSHOT.jar`.
3. **npm publish**:
   ```bash
   cd install/npm
   npm version X.Y.Z
   npm publish --access public
   ```
4. **Homebrew tap update**:
   ```bash
   # Bump `url`, `version`, and `sha256` in install/homebrew/kairo-code.rb,
   # then commit + push to the tap repo. Users on `brew upgrade kairo-code`
   # will pick it up.
   shasum -a 256 kairo-code-cli-X.Y.Z-SNAPSHOT.jar
   ```

## Notes on dependencies

- **Java ≥ 17 required** in all three paths. We do not bundle a JRE — too
  heavy for npm (~60 MB compressed) and Homebrew already provides openjdk@21.
- The npm launcher caches the jar under `~/.kairo-code/runtime/<version>/`.
  `npm uninstall -g @kairo/code` does NOT delete the cache; users can
  `rm -rf ~/.kairo-code/runtime/` to reclaim disk.
- Homebrew formula reads `KAIRO_CODE_JAVA_HOME` to let users pin a specific
  JDK without uninstalling openjdk@21.

# Installation

Three install paths, depending on your environment.

## npm (broadest)

```bash
npm install -g @kairo/code
```

The npm package is a Node.js launcher (~30 KB). On first run it downloads the
Java fat jar (~25 MB) into `~/.kairo-code/runtime/<version>/` and forwards
all CLI args.

**Requires** Node ≥ 18 and Java ≥ 17 on PATH.

To use a private mirror or corporate proxy for the jar:
```bash
export KAIRO_CODE_JAR_URL=https://my-mirror.example.com/kairo-code.jar
```

## Homebrew (macOS / Linuxbrew)

```bash
brew tap captaingreenskin/kairo
brew install kairo-code
```

Pulls `openjdk@21` automatically. Set `KAIRO_CODE_JAVA_HOME` to use a
different JDK without uninstalling the brew-provisioned one.

## Direct jar (CI, Docker, air-gapped)

```bash
# 1. Grab the jar from GitHub Releases
wget https://github.com/captaingreenskin/kairo-code/releases/download/v0.2.0/kairo-code-cli-0.2.0-SNAPSHOT.jar

# 2. Make sure java 17+ is on PATH
java -version

# 3. Run
java -jar kairo-code-cli-0.2.0-SNAPSHOT.jar --help
```

In CI you can cache the jar between runs — see the [E2E workflow](https://github.com/captaingreenskin/kairo-code/blob/main/.github/workflows/e2e.yml)
for an example.

## Build from source

```bash
git clone https://github.com/captaingreenskin/kairo-code.git
cd kairo-code
mvn package -DskipTests -pl kairo-code-cli -am
java -jar kairo-code-cli/target/kairo-code-cli-0.2.0-SNAPSHOT.jar
```

Build takes ~30 s on a warm Maven cache.

## Upgrading

::: code-group
```bash [npm]
npm update -g @kairo/code
# remove cached old jars:
rm -rf ~/.kairo-code/runtime
```
```bash [Homebrew]
brew update && brew upgrade kairo-code
```
:::

## Uninstall

::: code-group
```bash [npm]
npm uninstall -g @kairo/code
rm -rf ~/.kairo-code/runtime
```
```bash [Homebrew]
brew uninstall kairo-code
```
:::

The user data dir `~/.kairo-code/` (sessions, skills, plugins, cron tasks)
is preserved across uninstall — delete manually if you want a clean slate.

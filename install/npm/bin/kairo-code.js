#!/usr/bin/env node
/* Kairo Code launcher — checks JDK 17+, downloads the jar on first run,
 * forwards all CLI args to it. The jar is cached under ~/.kairo-code/runtime/. */

const { spawn, execSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const https = require('node:https');

const PKG = require('../package.json');
const KAIRO_VERSION = PKG.version;
const JAR_NAME = `kairo-code-cli-${KAIRO_VERSION}.jar`;
const HOME_DIR = path.join(os.homedir(), '.kairo-code', 'runtime', KAIRO_VERSION);
const JAR_PATH = path.join(HOME_DIR, JAR_NAME);

// Download URL — pin to a GitHub Release asset. Falls back to a configurable
// override so private mirrors / corporate proxies work.
const DOWNLOAD_URL =
  process.env.KAIRO_CODE_JAR_URL ||
  `https://github.com/captaingreenskin/kairo-code/releases/download/v${KAIRO_VERSION}/${JAR_NAME}`;

function ensureJdk() {
  try {
    const out = execSync('java -version 2>&1', { encoding: 'utf8' });
    const match = out.match(/version "(\d+)/);
    if (!match || Number(match[1]) < 17) {
      console.error(
        `kairo-code requires Java 17 or later. Detected: ${out.split('\n')[0]}\n` +
        `Install from https://adoptium.net/ or 'brew install openjdk@21'.`,
      );
      process.exit(127);
    }
  } catch (e) {
    console.error(
      'kairo-code requires Java 17 or later, and no `java` was found on PATH.\n' +
      'Install from https://adoptium.net/ or `brew install openjdk@21`.',
    );
    process.exit(127);
  }
}

function downloadJar() {
  return new Promise((resolve, reject) => {
    fs.mkdirSync(HOME_DIR, { recursive: true });
    const tmp = JAR_PATH + '.partial';
    const file = fs.createWriteStream(tmp);
    process.stderr.write(`Downloading kairo-code ${KAIRO_VERSION}... `);
    https
      .get(DOWNLOAD_URL, (res) => {
        // Follow one redirect (GitHub Release CDN)
        if (res.statusCode === 302 || res.statusCode === 301) {
          https
            .get(res.headers.location, (res2) => res2.pipe(file))
            .on('error', reject);
          return;
        }
        if (res.statusCode !== 200) {
          file.close();
          fs.unlinkSync(tmp);
          reject(new Error(`download failed: HTTP ${res.statusCode} from ${DOWNLOAD_URL}`));
          return;
        }
        res.pipe(file);
      })
      .on('error', reject);
    file.on('finish', () => {
      file.close();
      fs.renameSync(tmp, JAR_PATH);
      process.stderr.write('done.\n');
      resolve();
    });
  });
}

async function main() {
  ensureJdk();
  if (!fs.existsSync(JAR_PATH)) {
    await downloadJar();
  }
  // Forward all args to the jar. Inherit stdio so REPL works.
  const args = ['-jar', JAR_PATH, ...process.argv.slice(2)];
  const child = spawn('java', args, { stdio: 'inherit' });
  child.on('exit', (code, signal) => {
    if (signal) {
      // Re-raise signal so shells (set -e, &&, etc.) see the original
      // exit reason; Node would otherwise translate to exit 0.
      process.kill(process.pid, signal);
    } else {
      process.exit(code ?? 0);
    }
  });
}

main().catch((err) => {
  console.error('kairo-code launcher failed:', err.message);
  process.exit(1);
});

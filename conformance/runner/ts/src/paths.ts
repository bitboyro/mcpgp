import { existsSync, readdirSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** The runner's own directory (conformance/runner/ts). */
export const RUNNER = resolve(dirname(fileURLToPath(import.meta.url)), "..");
/** The repository root. */
export const REPO = resolve(RUNNER, "..", "..", "..");
export const CASES = join(REPO, "conformance", "cases");
export const FIXTURES = join(REPO, "conformance", "fixtures");
/** Everything the gate writes: built packages, server data, the report. */
export const WORK = join(RUNNER, ".gate");

export function workDir(...parts: string[]): string {
  const dir = join(WORK, ...parts);
  mkdirSync(dir, { recursive: true });
  return dir;
}

/** The CLI that builds packages: MCPGP_CLI_JAR, else the one `mvn -pl cli -am package` left in cli/target. */
export function cliJar(): string {
  const configured = process.env.MCPGP_CLI_JAR;
  if (configured) {
    if (!existsSync(configured)) throw new Error(`MCPGP_CLI_JAR=${configured} does not exist`);
    return configured;
  }
  const target = join(REPO, "cli", "target");
  const jar = existsSync(target) ? readdirSync(target).find((f) => /^mcpgp-cli-.*-exec\.jar$/.test(f)) : undefined;
  if (!jar) throw new Error("No CLI jar: run `mvn -B -pl cli -am package -DskipTests` first, or set MCPGP_CLI_JAR");
  return join(target, jar);
}

/** The JVM: JAVA_HOME's, else `java` on the PATH. */
export function java(): string {
  return process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", "java") : "java";
}

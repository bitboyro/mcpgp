import { defineConfig } from "vitest/config";

// The release gate (docs/process/release-gate.md). Matrix and selection come from the environment:
//   GATE_FORMATS     jar,mcpb,docker (default all three; docker only on Linux with a running daemon)
//   GATE_TRANSPORTS  stdio,http (default both)
//   GATE_UPDATE_GOLDEN=1  rewrite the golden surfaces (S2) instead of comparing; review the diff
//   GATE_REVISIONS   2025-03-26,2025-06-18,2025-11-25 (default: every revision the server claims)
//   GATE_SEED        seed for generated arguments (S3)
//   MCPGP_CLI_JAR    the CLI to build packages with (default: cli/target/mcpgp-cli-*-exec.jar)
export default defineConfig({
  test: {
    include: ["tests/**/*.test.ts"],
    globalSetup: ["src/global-setup.ts"],
    testTimeout: 120_000,
    hookTimeout: 240_000,
    pool: "forks",
    maxWorkers: 3,
    // a release gate never retries: a flaky test is a bug to fix
    retry: 0,
    reporters: ["default", ["junit", { outputFile: ".gate/report/junit.xml" }], "./src/report.ts"],
  },
});

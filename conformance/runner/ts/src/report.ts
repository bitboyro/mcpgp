import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { join, relative } from "node:path";
import type { Reporter, TestCase, TestModule } from "vitest/node";
import { REPO, RUNNER, WORK, java } from "./paths.js";

/**
 * The gate report (release-gate.md §9): gate-report.json for machines and gate-report.html for people,
 * in conformance/runner/ts/.gate/report/. CI keeps both with the run and attaches them to the release.
 */
interface Row {
  suite: string;
  combination: string;
  name: string;
  file: string;
  state: "passed" | "failed" | "skipped" | "pending";
  durationMs: number;
  errors: string[];
  repro: string;
}

function command(cmd: string, args: string[]): string {
  try {
    return execFileSync(cmd, args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
  } catch (e: any) {
    return String(e?.stderr ?? "").split("\n")[0].trim();
  }
}

function describeName(test: TestCase): string {
  const names: string[] = [];
  let parent: any = test.parent;
  while (parent && parent.type === "suite") {
    names.unshift(parent.name);
    parent = parent.parent;
  }
  return names.join(" > ");
}

/** "S3 kitchen-sink-oas31 · jar · stdio · 2025-11-25" → suite "S3 kitchen-sink-oas31", combination "jar · stdio · 2025-11-25". */
function split(describe: string): { suite: string; combination: string } {
  const parts = describe.split(" · ");
  return parts.length >= 4 ? { suite: parts[0], combination: parts.slice(1).join(" · ") } : { suite: describe || "(top level)", combination: "—" };
}

function escape(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);
}

function rowsOf(modules: ReadonlyArray<TestModule>): Row[] {
  const rows: Row[] = [];
  for (const module of modules) {
    const file = relative(RUNNER, module.moduleId);
    for (const test of module.children.allTests()) {
      const result = test.result();
      const describe = describeName(test);
      const { suite, combination } = split(describe);
      const combo = combination.split(" · ");
      const env = combo.length >= 3 ? `GATE_TRANSPORTS=${combo[1]} GATE_REVISIONS=${combo[2]} ` : "";
      rows.push({
        suite,
        combination,
        name: test.name,
        file,
        state: result.state,
        durationMs: Math.round(test.diagnostic()?.duration ?? 0),
        errors: result.state === "failed" ? (result.errors ?? []).map((e) => e.message ?? String(e)) : [],
        repro: `cd conformance/runner/ts && ${env}npx vitest run ${file} -t ${JSON.stringify(test.name)}`,
      });
    }
  }
  return rows;
}

function html(report: any): string {
  const rows: Row[] = report.tests;
  const suites = [...new Set(rows.map((r) => r.suite))];
  const combos = [...new Set(rows.map((r) => r.combination))].sort();
  const cell = (suite: string, combo: string) => {
    const in_ = rows.filter((r) => r.suite === suite && r.combination === combo);
    if (!in_.length) return `<td class="none">—</td>`;
    const failed = in_.filter((r) => r.state === "failed").length;
    const passed = in_.filter((r) => r.state === "passed").length;
    return failed ? `<td class="bad">${failed} failed<br><small>${passed} passed</small></td>` : `<td class="good">${passed} passed</td>`;
  };
  const failures = rows.filter((r) => r.state === "failed");
  const groups = new Map<string, Row[]>();
  for (const f of failures) groups.set(f.name, [...(groups.get(f.name) ?? []), f]);
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Gate report</title>
<style>
:root { --bg:#fff; --fg:#1d1d1f; --muted:#6e6e73; --line:#e5e5ea; --good:#1a7f37; --good-bg:#e9f7ee; --bad:#c62828; --bad-bg:#fdecec; --code:#f5f5f7; }
@media (prefers-color-scheme: dark) { :root { --bg:#161618; --fg:#f2f2f7; --muted:#a1a1a6; --line:#2c2c2e; --good:#5cd67f; --good-bg:#12301c; --bad:#ff6b6b; --bad-bg:#3a1515; --code:#222225; } }
* { box-sizing: border-box; } body { margin:0; background:var(--bg); color:var(--fg); font:15px/1.5 -apple-system, system-ui, sans-serif; }
main { max-width: 1100px; margin: 0 auto; padding: 24px 16px 64px; }
h1 { font-size: 24px; margin: 0 0 4px; } h2 { font-size: 18px; margin: 32px 0 8px; }
.verdict { font-size: 20px; font-weight: 600; padding: 12px 16px; border-radius: 10px; margin: 16px 0; }
.verdict.pass { background: var(--good-bg); color: var(--good); } .verdict.fail { background: var(--bad-bg); color: var(--bad); }
.muted { color: var(--muted); } table { border-collapse: collapse; width: 100%; } .scroll { overflow-x: auto; }
th, td { border-bottom: 1px solid var(--line); padding: 6px 10px; text-align: left; vertical-align: top; font-variant-numeric: tabular-nums; }
td.good { color: var(--good); } td.bad { color: var(--bad); font-weight: 600; } td.none { color: var(--muted); }
code, pre { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; background: var(--code); border-radius: 6px; }
code { padding: 1px 5px; } pre { padding: 10px; white-space: pre-wrap; word-break: break-word; margin: 6px 0; }
details { border: 1px solid var(--line); border-radius: 8px; padding: 8px 12px; margin: 8px 0; } summary { cursor: pointer; font-weight: 600; }
</style></head><body><main>
<h1>Gate report</h1>
<div class="muted">mcpgp ${escape(report.run.version)} · ${escape(report.run.commit)} · ${escape(report.run.finishedAt)}</div>
<div class="verdict ${report.verdict === "pass" ? "pass" : "fail"}">${report.verdict === "pass" ? "Pass" : "Fail"}: ${report.counts.passed} passed, ${report.counts.failed} failed, ${report.counts.skipped} skipped</div>
${failures.length ? `<h2>What blocks the release</h2>${[...groups.entries()].map(([name, list]) => `<details${groups.size <= 8 ? " open" : ""}><summary>${escape(name)} <span class="muted">· ${list.length} combination(s)</span></summary>
<div class="muted">${list.map((r) => escape(`${r.suite} · ${r.combination}`)).join("<br>")}</div>
<pre>${escape(list[0].errors.join("\n\n").slice(0, 4000))}</pre>
<div class="muted">Run it alone:</div><pre>${escape(list[0].repro)}</pre></details>`).join("\n")}` : ""}
<h2>Suites × combinations</h2>
<div class="scroll"><table><thead><tr><th>Suite</th>${combos.map((c) => `<th>${escape(c)}</th>`).join("")}</tr></thead><tbody>
${suites.map((s) => `<tr><th>${escape(s)}</th>${combos.map((c) => cell(s, c)).join("")}</tr>`).join("\n")}
</tbody></table></div>
<h2>Run</h2>
<table><tbody>
<tr><th>Transports</th><td>${escape(report.run.transports)}</td></tr>
<tr><th>Revisions</th><td>${escape(report.run.revisions)}</td></tr>
<tr><th>Seed</th><td>${escape(String(report.run.seed))}</td></tr>
<tr><th>Java</th><td>${escape(report.run.java)}</td></tr>
<tr><th>Node</th><td>${escape(report.run.node)}</td></tr>
<tr><th>Duration</th><td>${Math.round(report.run.durationMs / 1000)} s</td></tr>
</tbody></table>
</main></body></html>`;
}

export default class GateReport implements Reporter {
  private started = Date.now();

  onTestRunStart() {
    this.started = Date.now();
  }

  onTestRunEnd(modules: ReadonlyArray<TestModule>, errors: ReadonlyArray<{ message?: string }>) {
    const tests = rowsOf(modules);
    const counts = {
      passed: tests.filter((t) => t.state === "passed").length,
      failed: tests.filter((t) => t.state === "failed").length,
      skipped: tests.filter((t) => t.state === "skipped" || t.state === "pending").length,
    };
    const report = {
      verdict: counts.failed === 0 && errors.length === 0 && counts.passed > 0 ? "pass" : "fail",
      counts,
      run: {
        version: command("sh", ["-c", `sed -n 's/^  <version>\\(.*\\)<\\/version>/\\1/p' '${join(REPO, "pom.xml")}' | head -1`]),
        commit: command("git", ["-C", REPO, "rev-parse", "--short", "HEAD"]),
        finishedAt: new Date().toISOString(),
        durationMs: Date.now() - this.started,
        transports: process.env.GATE_TRANSPORTS ?? "stdio,http",
        revisions: process.env.GATE_REVISIONS ?? "all claimed",
        seed: Number(process.env.GATE_SEED ?? 0),
        java: command(java(), ["-version"]),
        node: process.version,
      },
      unhandledErrors: errors.map((e) => e.message ?? String(e)),
      tests,
    };
    const dir = join(WORK, "report");
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, "gate-report.json"), JSON.stringify(report, null, 2));
    writeFileSync(join(dir, "gate-report.html"), html(report));
    process.stdout.write(`\nGate report: ${relative(process.cwd(), join(dir, "gate-report.html"))} (${report.verdict})\n`);
  }
}

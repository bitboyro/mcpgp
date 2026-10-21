import { afterAll, beforeAll, beforeEach, describe, expect, inject, test } from "vitest";
import { contractsOf, fixtures } from "../src/fixtures.js";
import { callConfirmed, expectedCode, mutations, validArguments, type Tool } from "../src/generate.js";
import { Mock } from "../src/mock.js";
import { codeOf, documentedCodes, text, type ToolResult } from "../src/results.js";
import { CLAIMED_REVISIONS, revisions, startServer, transports, type Running } from "../src/server.js";
import { checkResult } from "../src/traffic.js";

/**
 * S3 · Operations (release-gate.md §6): for every tool, calls generated from its input schema.
 *   valid      → exactly one API request, which the contract accepts, and a successful result
 *   invalid    → INPUT_VALIDATION, and nothing sent
 *   statuses   → every documented answer of the operation becomes its documented code, with no retry
 *   faults     → a dropped connection and an unparsable answer become their codes
 */
const packages = inject("packages");
const seed = Number(process.env.GATE_SEED ?? 0);
const CODES = documentedCodes();

for (const fixture of fixtures()) {
  const pkg = packages[fixture.definition];
  const specs = contractsOf(fixture.definition).map((c) => c.spec);

  // the tools, once, to name the tests (the same package serves the same tools on every transport)
  const probeMock = await Mock.start(specs);
  const probe = await startServer({ pkg, transport: "stdio", revision: CLAIMED_REVISIONS.at(-1)!, config: fixture.config(probeMock.url) });
  const tools = ((await probe.client.listTools()).tools as Tool[]).filter((t) => t.name.includes("_"));
  await probe.close();
  await probeMock.stop();

  for (const transport of transports()) {
    for (const revision of revisions()) {
      describe(`S3 ${fixture.id} · jar · ${transport} · ${revision}`, () => {
        let mock: Mock;
        let server: Running;
        const operationOf = new Map<string, string>();

        beforeAll(async () => {
          mock = await Mock.start(specs);
          server = await startServer({ pkg, transport, revision, config: fixture.config(mock.url) });
        });
        afterAll(async () => {
          await server?.close();
          await mock?.stop();
        });
        beforeEach(() => mock.reset());

        /** The operation a tool calls: learned from a valid call, so every test can also run on its own. */
        async function operationFor(tool: Tool): Promise<string> {
          if (!operationOf.has(tool.name)) {
            mock.reset();
            await callConfirmed(server, tool.name, validArguments(tool, "required", seed));
            const key = mock.journal[0]?.operation;
            if (!key) throw new Error(`${tool.name}: a valid call reached no operation of the contract`);
            operationOf.set(tool.name, key);
            mock.reset();
          }
          return operationOf.get(tool.name)!;
        }

        /** The API side of one call: how many requests, and nothing the contract rejects. */
        function sent(): { count: number; problems: string[] } {
          const problems = mock.journal.flatMap((r) => r.violations.map((v) => `${r.method} ${r.path}: ${v}`));
          return { count: mock.journal.length, problems };
        }

        for (const tool of tools) {
          for (const variant of ["required", "all"] as const) {
            test(`${tool.name} › valid (${variant === "required" ? "required fields" : "every field"})`, async () => {
              const args = validArguments(tool, variant, seed);
              const result = await callConfirmed(server, tool.name, args);
              const api = sent();
              const problems = [...api.problems];
              checkResult(result, { isError: false }, problems);
              if (api.count !== 1) problems.push(`expected 1 API request, got ${api.count}`);
              if (mock.journal[0]?.operation) operationOf.set(tool.name, mock.journal[0].operation);
              expect(problems, `${problems.join("\n")}\narguments: ${JSON.stringify(args)}`).toEqual([]);
            });
          }

          for (const mutation of mutations(tool, validArguments(tool, "all", seed))) {
            test(`${tool.name} › invalid: ${mutation.name}`, async () => {
              const result = await callConfirmed(server, tool.name, mutation.args);
              const problems: string[] = [];
              checkResult(result, { isError: true, code: "INPUT_VALIDATION" }, problems);
              if (mock.journal.length) problems.push(`sent ${mock.journal.length} API request(s): ${mock.journal.map((r) => `${r.method} ${r.path}`).join(", ")}`);
              expect(problems, `${problems.join("\n")}\narguments: ${JSON.stringify(mutation.args)}`).toEqual([]);
            });
          }

          test(`${tool.name} › every documented answer becomes its code`, async () => {
            const key = await operationFor(tool);
            const op = mock.operations().find((o) => o.key === key)!;
            const problems: string[] = [];
            for (const status of Object.keys(op.responses)) {
              const codes = expectedCode(status, CODES);
              if (codes === null) continue;
              mock.reset();
              mock.forOperation(op.key, mock.response(op, status));
              const result: ToolResult = await callConfirmed(server, tool.name, validArguments(tool, "required", seed));
              const code = codeOf(result);
              if (codes.length === 0 && result.isError) problems.push(`${status}: expected success, got ${code}: ${text(result).slice(0, 200)}`);
              if (codes.length && (!result.isError || !codes.includes(code ?? ""))) problems.push(`${status}: expected ${codes.join(" or ")}, got ${result.isError ? code : "success"}`);
              if (mock.journal.length !== 1) problems.push(`${status}: expected 1 API request (no retry), got ${mock.journal.length}`);
              checkResult(result, undefined, problems);
            }
            expect(problems, problems.join("\n")).toEqual([]);
          });

          test(`${tool.name} › a dropped connection is UPSTREAM_UNREACHABLE, or resent once for GET/HEAD`, async () => {
            const key = await operationFor(tool);
            mock.forOperation(key, { fault: "reset" });   // the next request only
            const result = await callConfirmed(server, tool.name, validArguments(tool, "required", seed));
            const problems: string[] = [];
            // requirements.md §7: no automatic retries of upstream calls, except HTTP's own for GET and HEAD:
            // sent once more when the connection closes without an answer (tools-contract §6, RFC 9110 §9.2.2),
            // so the agent gets the answer to the resend
            const method = (mock.operations().find((o) => o.key === key)?.method ?? "").toUpperCase();
            if (method === "GET" || method === "HEAD") {
              if (result.isError) problems.push(`expected the answer to the resend, got ${codeOf(result)}: ${text(result).slice(0, 200)}`);
              if (mock.journal.length !== 2) problems.push(`expected 2 API requests (one resend for ${method}), got ${mock.journal.length}`);
            } else {
              checkResult(result, { isError: true, code: "UPSTREAM_UNREACHABLE" }, problems);
              if (mock.journal.length !== 1) problems.push(`expected 1 API request (no retry), got ${mock.journal.length}`);
            }
            expect(problems, problems.join("\n")).toEqual([]);
          });

          test(`${tool.name} › an answer that isn't the declared JSON is RESPONSE_PARSE_ERROR`, async (ctx) => {
            const key = await operationFor(tool);
            const op = mock.operations().find((o) => o.key === key);
            const success = op && Object.entries(op.responses).find(([s]) => s.startsWith("2"));
            if (!success || !Object.keys(success[1].content).some((t) => t.includes("json"))) ctx.skip();
            mock.forOperation(key, { status: Number(success![0]), fault: "invalid-json" });
            const result = await callConfirmed(server, tool.name, validArguments(tool, "required", seed));
            const problems: string[] = [];
            checkResult(result, { isError: true, code: "RESPONSE_PARSE_ERROR" }, problems);
            expect(problems, problems.join("\n")).toEqual([]);
          });
        }
      });
    }
  }
}

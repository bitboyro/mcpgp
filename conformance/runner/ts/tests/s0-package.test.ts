import { afterAll, beforeAll, describe, expect, inject, test } from "vitest";
import { contractsOf, fixtures, pointAtMock, trafficCases } from "../src/fixtures.js";
import { Mock } from "../src/mock.js";
import { combinations } from "../src/matrix.js";
import { startServer, type Running } from "../src/server.js";

/**
 * S0 · Package (release-gate.md §6): every package starts as the customer runs it, on every transport
 * and claimed revision, negotiates that revision and lists its tools, with nothing but the protocol
 * on the wire.
 */
const packages = inject("packages");
const targets = [
  ...fixtures().map((f) => ({ id: f.id, definition: f.definition, config: f.config })),
  ...trafficCases().map((c) => ({ id: `conformance/${c.id}`, definition: c.definition, config: (url: string) => pointAtMock(c.runtime, url) })),
];

for (const target of targets) {
  const pkg = packages[target.definition];
  for (const { format, transport, revision, label } of combinations(pkg)) {
    {
      describe(`S0 ${target.id} · ${label}`, () => {
        let mock: Mock;
        let server: Running;
        beforeAll(async () => {
          mock = await Mock.start(contractsOf(target.definition).map((c) => c.spec));
          server = await startServer({ pkg, format, transport, revision, config: target.config(mock.url) });
        });
        afterAll(async () => {
          await server?.close();
          await mock?.stop();
        });

        test("negotiates the revision the client asked for", () => {
          expect(server.negotiated).toBe(revision);
        });

        test("lists its tools", async () => {
          const { tools } = await server.client.listTools();
          expect(tools.length).toBeGreaterThan(0);
          for (const tool of tools) {
            expect(tool.inputSchema?.type, tool.name).toBe("object");
            expect(tool.description, `${tool.name} has a description`).toBeTruthy();
          }
        });

        test("puts nothing but the protocol on the wire", () => {
          expect(server.transportErrors, server.log()).toEqual([]);
        });
      });
    }
  }
}

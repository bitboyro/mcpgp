import { afterAll, beforeAll, describe, inject, test } from "vitest";
import { contractsOf, pointAtMock, trafficCases } from "../src/fixtures.js";
import { Mock } from "../src/mock.js";
import { combinations } from "../src/matrix.js";
import { startServer, type Running } from "../src/server.js";
import { runStep } from "../src/traffic.js";

/**
 * The conformance cases (conformance/cases/**\/traffic.yaml), replayed against the packaged server over
 * real transports. The Java runner plays the same files in-process; both must pass.
 */
const packages = inject("packages");

for (const c of trafficCases()) {
  const pkg = packages[c.definition];
  for (const { format, transport, revision, label } of combinations(pkg, c.revisions)) {
    {
      describe(`conformance/${c.id} · ${label}`, () => {
        let mock: Mock;
        let server: Running;
        beforeAll(async () => {
          mock = await Mock.start(contractsOf(c.definition).map((x) => x.spec));
          server = await startServer({ pkg, format, transport, revision, config: pointAtMock(c.runtime, mock.url) });
        });
        afterAll(async () => {
          await server?.close();
          await mock?.stop();
        });
        for (const step of c.traffic.steps ?? []) {
          test(step.name, async () => {
            await runStep(server, mock, step);
          });
        }
      });
    }
  }
}

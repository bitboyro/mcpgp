import { readFileSync } from "node:fs";
import { Ajv2020 } from "ajv/dist/2020.js";
import { parse } from "yaml";
import { ToolListChangedNotificationSchema } from "@modelcontextprotocol/sdk/types.js";
import { afterAll, beforeAll, beforeEach, describe, expect, inject, test } from "vitest";
import { contractsOf, fixtures, pointAtMock, trafficCases, type Exposure } from "../src/fixtures.js";
import { validArguments, type Tool } from "../src/generate.js";
import { differences, forRevision, normalize, readGolden, UPDATE, writeGolden, type Surface } from "../src/golden.js";
import { combinations } from "../src/matrix.js";
import { Mock } from "../src/mock.js";
import { codeOf, structured, type ToolResult } from "../src/results.js";
import { CLAIMED_REVISIONS, startServer, type Running } from "../src/server.js";
import { checkResult } from "../src/traffic.js";

/**
 * S2 · Surface (release-gate.md §6): what each package lists must equal its reviewed golden file, every
 * schema in it must be a valid JSON Schema, and each exposure mode (tools-contract §15) must offer the
 * same operations as the plain build, found and called its own way.
 */
const packages = inject("packages");
const NEWEST = CLAIMED_REVISIONS.at(-1)!;
const PLAIN = "kitchen-sink-oas31";   // the reference: every API tool, listed directly

const DISCOVERY: Record<Exposure, string[]> = {
  plain: [],
  "plain-search": ["search_tools", "describe_tool"],
  search: ["search_tools", "describe_tool", "call_tool"],
  catalog: ["list_operations", "get_operation_schema", "invoke_operation"],
  toolsets: ["toolsets_list", "toolset_enable", "toolset_disable"],
  hybrid: ["search_tools", "describe_tool", "call_tool"],
};

const targets = [
  ...fixtures().map((f) => ({ id: f.id, definition: f.definition, exposure: f.exposure, config: f.config, pinned: pinnedOf(f.definition) })),
  ...trafficCases().map((c) => ({ id: `conformance/${c.id}`, definition: c.definition, exposure: "plain" as Exposure, config: (url: string) => pointAtMock(c.runtime, url), pinned: [] as string[] })),
];

function pinnedOf(definition: string): string[] {
  return parse(readFileSync(definition, "utf8"))?.exposure?.pinned ?? [];
}

async function listSurface(server: Running): Promise<Surface> {
  const caps = server.client.getServerCapabilities() ?? {};
  const surface: Surface = { tools: (await server.client.listTools()).tools };
  if (caps.resources) {
    surface.resources = (await server.client.listResources()).resources;
    surface.resourceTemplates = (await server.client.listResourceTemplates()).resourceTemplates;
  }
  if (caps.prompts) surface.prompts = (await server.client.listPrompts()).prompts;
  return surface;
}

const ajv = new Ajv2020({ strict: false, validateFormats: false });

for (const target of targets) {
  const pkg = packages[target.definition];
  for (const { format, transport, revision, label } of combinations(pkg)) {
    describe(`S2 ${target.id} · ${label}`, () => {
      let mock: Mock;
      let server: Running;
      let surface: Surface;
      const reference = () => {
        const plain = readGolden(PLAIN);
        if (!plain) throw new Error(`no golden surface for ${PLAIN}: run with GATE_UPDATE_GOLDEN=1 and review it`);
        return plain.tools.filter((t: any) => t.name.startsWith("kitchen_")) as Tool[];
      };

      beforeAll(async () => {
        mock = await Mock.start(contractsOf(target.definition).map((c) => c.spec));
        server = await startServer({ pkg, format, transport, revision, config: target.config(mock.url) });
        surface = normalize(await listSurface(server));
      });
      afterAll(async () => {
        await server?.close();
        await mock?.stop();
      });
      beforeEach(() => mock.reset());

      test("lists exactly its golden surface", () => {
        if (UPDATE) {
          if (format === "jar" && transport === "stdio" && revision === NEWEST) writeGolden(target.id, surface);
          return;
        }
        const golden = readGolden(target.id);
        expect(golden, `no golden surface for ${target.id}: run with GATE_UPDATE_GOLDEN=1 and review the file`).toBeDefined();
        const diff = differences(normalize(forRevision(golden!, revision)), normalize(forRevision(surface, revision)));
        expect(diff, `the surface changed (if on purpose: GATE_UPDATE_GOLDEN=1, then review the diff)\n${diff.join("\n")}`).toEqual([]);
      });

      test("every schema it lists is a valid JSON Schema", () => {
        const problems: string[] = [];
        for (const tool of surface.tools) {
          for (const key of ["inputSchema", "outputSchema"]) {
            if (tool[key] && !ajv.validateSchema(tool[key])) problems.push(`${tool.name}.${key}: ${ajv.errorsText(ajv.errors)}`);
          }
        }
        expect(problems, problems.join("\n")).toEqual([]);
      });

      if (target.exposure === "plain") return;

      test(`lists what ${target.exposure} mode lists (tools-contract §15)`, () => {
        const names = surface.tools.map((t) => t.name).sort();
        const api = reference().map((t) => t.name);
        const want = target.exposure === "plain-search" ? [...api, ...DISCOVERY[target.exposure]]
          : target.exposure === "hybrid" ? [...target.pinned, ...DISCOVERY.hybrid]
          : DISCOVERY[target.exposure];
        expect(names).toEqual([...want].sort());
      });

      if (["plain-search", "search", "hybrid"].includes(target.exposure)) {
        test("search_tools ranks each tool first by its exact name, and describe_tool gives its plain schema", async () => {
          const problems: string[] = [];
          for (const tool of reference()) {
            const found = structured((await server.client.callTool({ name: "search_tools", arguments: { query: tool.name } })) as ToolResult);
            if (found?.tools?.[0]?.name !== tool.name) problems.push(`search_tools("${tool.name}") ranks ${found?.tools?.[0]?.name} first`);
            const described = structured((await server.client.callTool({ name: "describe_tool", arguments: { name: tool.name } })) as ToolResult);
            if (JSON.stringify(normalize({ tools: [described?.inputSchema ?? null] })) !== JSON.stringify(normalize({ tools: [tool.inputSchema] }))) {
              problems.push(`describe_tool("${tool.name}").inputSchema differs from the plain build's`);
            }
          }
          expect(problems, problems.join("\n")).toEqual([]);
        });
      }

      if (target.exposure === "catalog") {
        test("list_operations pages through every operation, and get_operation_schema gives its plain schema", async () => {
          const names: string[] = [];
          let cursor: string | undefined;
          for (let page = 0; page < 50; page++) {
            const result = structured((await server.client.callTool({ name: "list_operations", arguments: cursor ? { cursor } : {} })) as ToolResult);
            names.push(...(result?.items ?? []).map((i: any) => i.name));
            cursor = result?.nextCursor ?? undefined;
            if (!result?.hasMore || !cursor) break;
          }
          expect(names.sort()).toEqual(reference().map((t) => t.name).sort());
          const problems: string[] = [];
          for (const tool of reference()) {
            const schema = structured((await server.client.callTool({ name: "get_operation_schema", arguments: { name: tool.name } })) as ToolResult);
            if (JSON.stringify(normalize({ tools: [schema?.inputSchema ?? null] })) !== JSON.stringify(normalize({ tools: [tool.inputSchema] }))) {
              problems.push(`get_operation_schema("${tool.name}").inputSchema differs from the plain build's`);
            }
          }
          expect(problems, problems.join("\n")).toEqual([]);
        });
      }

      if (["search", "catalog", "hybrid"].includes(target.exposure)) {
        const via = target.exposure === "catalog" ? "invoke_operation" : "call_tool";
        test(`${via} calls every operation as the plain tool would`, async () => {
          const problems: string[] = [];
          for (const tool of reference()) {
            mock.reset();
            const args = validArguments(tool, "required");
            let result = (await server.client.callTool({ name: via, arguments: { name: tool.name, arguments: args } })) as ToolResult;
            if (result.isError && codeOf(result) === "CONFIRMATION_REQUIRED") {
              const token = structured(result)?.confirm;
              result = (await server.client.callTool({ name: via, arguments: { name: tool.name, arguments: { ...args, confirm: token } } })) as ToolResult;
            }
            const before = problems.length;
            checkResult(result, { isError: false }, problems);
            if (mock.journal.length !== 1) problems.push(`${tool.name}: ${mock.journal.length} API requests, expected 1`);
            for (const r of mock.journal) for (const v of r.violations) problems.push(`${tool.name}: ${r.method} ${r.path}: ${v}`);
            if (problems.length > before) problems.splice(before, 0, `— ${tool.name} through ${via}:`);
          }
          expect(problems, problems.join("\n")).toEqual([]);
        });
      }

      if (target.exposure === "hybrid") {
        test("pinned tools are called directly", async () => {
          const problems: string[] = [];
          for (const name of target.pinned) {
            mock.reset();
            const tool = reference().find((t) => t.name === name)!;
            checkResult((await server.client.callTool({ name, arguments: validArguments(tool, "required") })) as ToolResult, { isError: false }, problems);
            if (mock.journal.length !== 1) problems.push(`${name}: ${mock.journal.length} API requests, expected 1`);
          }
          expect(problems, problems.join("\n")).toEqual([]);
        });
      }

      if (target.exposure === "toolsets") {
        test("a toolset's tools appear when it is enabled, with list_changed, and are refused when it is disabled", async () => {
          const problems: string[] = [];
          let changed = 0;
          server.client.setNotificationHandler(ToolListChangedNotificationSchema, async () => {
            changed++;
          });
          const sets = structured((await server.client.callTool({ name: "toolsets_list", arguments: {} })) as ToolResult)?.toolsets ?? [];
          const total = sets.reduce((n: number, s: any) => n + s.tools, 0);
          if (total !== reference().length) problems.push(`the toolsets hold ${total} tools, the build has ${reference().length}`);
          const items = reference().find((t) => t.name === "kitchen_item_get")!;

          try {
            const refused = (await server.client.callTool({ name: items.name, arguments: validArguments(items, "required") })) as ToolResult;
            checkResult(refused, { isError: true, code: "TOOLSET_DISABLED" }, problems);
          } catch (error: any) {
            problems.push(`a disabled toolset's tool got a protocol error instead of TOOLSET_DISABLED (tools-contract §15.3): ${error?.message ?? error}`);
          }
          if (mock.journal.length) problems.push(`a disabled toolset's tool sent ${mock.journal.length} API request(s)`);

          await server.client.callTool({ name: "toolset_enable", arguments: { name: "items" } });
          const listed = (await server.client.listTools()).tools.map((t) => t.name);
          const want = sets.find((s: any) => s.name === "items")?.tools ?? 0;
          const inItems = listed.filter((n) => n.startsWith("kitchen_"));
          if (inItems.length !== want) problems.push(`after enabling items: ${inItems.length} API tools listed, toolsets_list said ${want}`);
          await new Promise((r) => setTimeout(r, 300));
          // over HTTP the notification needs the session's GET stream; without one the next tools/list shows the change
          if (transport === "stdio" && changed === 0) problems.push("no notifications/tools/list_changed after toolset_enable");

          mock.reset();
          try {
            checkResult((await server.client.callTool({ name: items.name, arguments: validArguments(items, "required") })) as ToolResult, { isError: false }, problems);
          } catch (error: any) {
            problems.push(`after toolset_enable, ${items.name} (listed: ${listed.includes(items.name)}) can't be called: ${error?.message ?? error}`);
          }

          await server.client.callTool({ name: "toolset_disable", arguments: { name: "items" } });
          const after = (await server.client.listTools()).tools.map((t) => t.name).filter((n) => n.startsWith("kitchen_"));
          if (after.length) problems.push(`after disabling items, still listed: ${after.join(", ")}`);
          expect(problems, problems.join("\n")).toEqual([]);
        });
      }
    });
  }
}

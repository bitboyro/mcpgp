import { existsSync, readdirSync, readFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { parse } from "yaml";
import { CASES, FIXTURES, REPO } from "./paths.js";

/** A contract a definition ships: the mock serves and checks requests against it. */
export interface ContractFile {
  api: string;
  version: string;
  spec: string;
}

/** The contracts a definition lists, read from the definition itself (not from mcpgp's compiler). */
export function contractsOf(definition: string): ContractFile[] {
  const doc = parse(readFileSync(definition, "utf8")) ?? {};
  const apis = doc.apis ?? (doc.api ? [doc.api] : []);
  return apis.flatMap((api: any) =>
    (api.contracts ?? []).map((c: any) => ({ api: api.id, version: String(c.version), spec: join(dirname(definition), c.spec) })),
  );
}

export type Exposure = "plain" | "plain-search" | "search" | "catalog" | "toolsets" | "hybrid";

export interface Fixture {
  id: string;
  definition: string;
  /** How its tools are offered (tools-contract §15); S2 checks the mode's behaviour. */
  exposure: Exposure;
  /** Whether S3 generates calls for its tools (directly listed API tools only, so far). */
  s3: boolean;
  workspace?: string;
  /** The operator's config with every instance pointed at the mock. */
  config(mockUrl: string): Record<string, unknown>;
}

const API_KEY = "gate-not-a-secret";

/** The fixtures of release-gate.md §6.1 that exist so far. */
export function fixtures(): Fixture[] {
  const kitchen = (file: string) => join(FIXTURES, "definitions", "kitchen-sink-oas31", file);
  const config = (mockUrl: string) => ({
    // a dummy credential, inline because the gate runs without an environment
    allowInlineSecrets: true,
    instance: { baseUrl: mockUrl, auth: { type: "apiKey", value: API_KEY } },
  });
  return [
    { id: "kitchen-sink-oas31", definition: kitchen("kitchen-sink.mcpgp.yaml"), exposure: "plain", s3: true, config },
    { id: "kitchen-sink-plain-search", definition: kitchen("kitchen-sink-plain-search.mcpgp.yaml"), exposure: "plain-search", s3: false, config },
    { id: "kitchen-sink-search", definition: kitchen("kitchen-sink-search.mcpgp.yaml"), exposure: "search", s3: false, config },
    { id: "kitchen-sink-catalog", definition: kitchen("kitchen-sink-catalog.mcpgp.yaml"), exposure: "catalog", s3: false, config },
    { id: "kitchen-sink-toolsets", definition: kitchen("kitchen-sink-toolsets.mcpgp.yaml"), exposure: "toolsets", s3: false, config },
    { id: "kitchen-sink-hybrid", definition: kitchen("kitchen-sink-hybrid.mcpgp.yaml"), exposure: "hybrid", s3: false, config },
  ];
}

/** A conformance case with traffic (conformance.md §2): definition/, runtime.yaml, traffic.yaml. */
export interface TrafficCase {
  id: string;
  dir: string;
  definition: string;
  revisions: string[] | undefined;
  runtime: Record<string, unknown>;
  traffic: any;
}

export function trafficCases(): TrafficCase[] {
  const found: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.isDirectory()) walk(join(dir, entry.name));
      else if (entry.name === "traffic.yaml") found.push(dir);
    }
  };
  walk(CASES);
  return found.sort().map((dir) => {
    const definitionDir = join(dir, "definition");
    const root = readdirSync(definitionDir).find((f) => f.endsWith(".mcpgp.yaml"));
    if (!root) throw new Error(`${relative(REPO, dir)}: no *.mcpgp.yaml in definition/`);
    const caseFile = join(dir, "case.yaml");
    const meta = existsSync(caseFile) ? parse(readFileSync(caseFile, "utf8")) ?? {} : {};
    return {
      id: relative(CASES, dir),
      dir,
      definition: join(definitionDir, root),
      revisions: meta.mcpRevisions,
      runtime: parse(readFileSync(join(dir, "runtime.yaml"), "utf8")) ?? {},
      traffic: parse(readFileSync(join(dir, "traffic.yaml"), "utf8")),
    };
  });
}

/** Replaces `baseUrl: mock` (conformance.md: instances point to `mock`) with the mock's address. */
export function pointAtMock(config: unknown, mockUrl: string): any {
  if (Array.isArray(config)) return config.map((v) => pointAtMock(v, mockUrl));
  if (config && typeof config === "object") {
    return Object.fromEntries(
      Object.entries(config).map(([k, v]) => [k, k === "baseUrl" && v === "mock" ? mockUrl : pointAtMock(v, mockUrl)]),
    );
  }
  return config;
}

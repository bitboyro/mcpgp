import { rmSync } from "node:fs";
import type { TestProject } from "vitest/node";
import { buildPackage } from "./packages.js";
import { buildImage, formats } from "./formats.js";
import { fixtures, trafficCases } from "./fixtures.js";
import { WORK } from "./paths.js";

declare module "vitest" {
  export interface ProvidedContext {
    /** Built packages by definition path. */
    packages: Record<string, import("./packages.js").Package>;
  }
}

/** Builds every package the suites need once, before any test runs (builds are slow and cached). */
export default function setup(project: TestProject) {
  rmSync(`${WORK}/runs`, { recursive: true, force: true });
  const packages: Record<string, import("./packages.js").Package> = {};
  const definitions = [
    ...fixtures().map((f) => ({ definition: f.definition, workspace: f.workspace })),
    ...trafficCases().map((c) => ({ definition: c.definition, workspace: undefined })),
  ];
  for (const { definition, workspace } of definitions) {
    if (!packages[definition]) packages[definition] = buildPackage(definition, { workspace });
  }
  if (formats().includes("docker")) {
    for (const pkg of Object.values(packages)) if (pkg.dockerfile) buildImage(pkg);
  }
  project.provide("packages", packages);
}

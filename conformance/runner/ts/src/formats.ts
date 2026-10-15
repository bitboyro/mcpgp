import { spawnSync } from "node:child_process";
import { basename } from "node:path";
import type { Package } from "./packages.js";
import type { TransportKind } from "./server.js";

/** The package formats (packaging.md §1, §7): the server jar, the desktop bundle, the container image. */
export type Format = "jar" | "mcpb" | "docker";

const ALL: Format[] = ["jar", "mcpb", "docker"];

let dockerChecked: boolean | undefined;

/** Docker is used on Linux only: the image runs with the host's network, so it reaches the gate's mock on loopback. */
export function dockerAvailable(): boolean {
  if (dockerChecked === undefined) {
    dockerChecked = process.platform === "linux" && spawnSync("docker", ["info"], { stdio: "ignore", timeout: 20_000 }).status === 0;
  }
  return dockerChecked;
}

/**
 * The formats to test: GATE_FORMATS, else all three. Docker is left out when it can't run here (a
 * Mac, no daemon), unless GATE_FORMATS asks for it explicitly; then that is an error, not a skip.
 */
export function formats(): Format[] {
  const chosen = process.env.GATE_FORMATS?.split(",").map((s) => s.trim()).filter(Boolean) as Format[] | undefined;
  for (const f of chosen ?? []) if (!ALL.includes(f)) throw new Error(`GATE_FORMATS: unknown format ${f} (jar, mcpb, docker)`);
  if (chosen?.includes("docker") && !dockerAvailable()) {
    throw new Error("GATE_FORMATS asks for docker, but Docker can't run here (it needs Linux and a running daemon)");
  }
  return chosen?.length ? chosen : ALL.filter((f) => f !== "docker" || dockerAvailable());
}

/** Whether a package ships a format, and which transports that format runs on (a bundle is stdio, an image HTTP). */
export function supports(pkg: Package, format: Format, transport: TransportKind): boolean {
  if (format === "jar") return true;
  if (format === "mcpb") return !!pkg.mcpb && transport === "stdio";
  return !!pkg.dockerfile && transport === "http";
}

/** The image a package's Dockerfile builds, named after the package so a changed package gets a new image. */
export function imageTag(pkg: Package): string {
  return `mcpgp-gate/${pkg.name}:${basename(pkg.dir)}`;
}

/** Builds the image from the package exactly as INSTALL.md tells the customer to (once per package). */
export function buildImage(pkg: Package): string {
  const tag = imageTag(pkg);
  if (spawnSync("docker", ["image", "inspect", tag], { stdio: "ignore" }).status === 0) return tag;
  const build = spawnSync("docker", ["build", "-f", "docker/Dockerfile", "-t", tag, "."], { cwd: pkg.dir, encoding: "utf8", timeout: 600_000 });
  if (build.status !== 0) throw new Error(`docker build for ${pkg.zip} failed:\n${build.stdout}\n${build.stderr}`);
  return tag;
}

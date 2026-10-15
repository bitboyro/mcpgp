import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join, relative } from "node:path";
import { cliJar, java, workDir, WORK } from "./paths.js";

/** A server package as the customer gets it, unpacked. */
export interface Package {
  /** The definition it was built from. */
  definition: string;
  /** The server's name, e.g. kitchen-sink. */
  name: string;
  /** Where the package's zip was unpacked (its top directory, as the customer has it). */
  dir: string;
  /** The server jar: what `java -jar` runs. */
  jar: string;
  /** The desktop bundle, unpacked, when the build has the mcpb target. */
  mcpb?: string;
  /** docker/Dockerfile, when the build has the docker target. */
  dockerfile?: string;
  /** The package's zip name, e.g. kitchen-sink-1.0.0.zip. */
  zip: string;
}

function files(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? files(join(dir, e.name)) : [join(dir, e.name)],
  );
}

/** Same definition files and same CLI → same package, so it is built once. */
function key(definition: string, definitionDir: string, cli: string): string {
  const hash = createHash("sha256");
  hash.update(`${relative(definitionDir, definition)}\n`);   // several root definitions can share a project
  const stat = statSync(cli);
  hash.update(`${cli}:${stat.size}:${stat.mtimeMs}\n`);
  for (const file of files(definitionDir).sort()) {
    hash.update(relative(definitionDir, file) + "\n");
    hash.update(readFileSync(file));
  }
  return hash.digest("hex").slice(0, 16);
}

/**
 * Builds the package with `mcpgp build` exactly as a builder would, and keeps only the server jar
 * (the zip's other files are docs and checksums; a package is ~35 MB, so builds are cached).
 */
export function buildPackage(definition: string, options: { workspace?: string } = {}): Package {
  const cli = cliJar();
  const definitionDir = join(definition, "..");
  const dir = join(WORK, "packages", key(definition, definitionDir, cli));
  const done = join(dir, "package.json");
  if (existsSync(done)) return JSON.parse(readFileSync(done, "utf8")) as Package;

  workDir("packages");
  const out = mkdtempSync(join(WORK, "packages", "build-"));
  try {
    const args = ["-jar", cli, "build", "--quiet", "--out", out];
    if (options.workspace) args.push("--workspace", options.workspace);
    args.push(definition);
    const build = spawnSync(java(), args, { encoding: "utf8", env: { ...process.env, SOURCE_DATE_EPOCH: "0" }, timeout: 180_000 });
    if (build.status !== 0) {
      throw new Error(`mcpgp build ${definition} failed (exit ${build.status}):\n${build.stdout}\n${build.stderr}`);
    }
    const zip = readdirSync(out).find((f) => f.endsWith(".zip"));
    if (!zip) throw new Error(`mcpgp build ${definition} wrote no zip in ${out}`);
    mkdirSync(dir, { recursive: true });
    // the whole package, as the customer unpacks it
    const unzip = spawnSync("unzip", ["-o", "-q", join(out, zip), "-d", dir], { encoding: "utf8" });
    if (unzip.status !== 0) throw new Error(`unzip ${zip}: ${unzip.stderr}`);
    const jar = readdirSync(dir).find((f) => f.endsWith(".jar"));
    if (!jar) throw new Error(`${zip} has no server jar`);
    const pkg: Package = { definition, name: jar.replace(/\.jar$/, ""), dir, jar: join(dir, jar), zip };
    const bundle = readdirSync(dir).find((f) => f.endsWith(".mcpb"));
    if (bundle) {
      // unpacked as a desktop client installs it
      const target = join(dir, "mcpb");
      const unpack = spawnSync("unzip", ["-o", "-q", join(dir, bundle), "-d", target], { encoding: "utf8" });
      if (unpack.status !== 0) throw new Error(`unzip ${bundle}: ${unpack.stderr}`);
      rmSync(join(dir, bundle));   // ~35 MB; the unpacked copy is what runs
      pkg.mcpb = target;
    }
    if (existsSync(join(dir, "docker", "Dockerfile"))) pkg.dockerfile = join(dir, "docker", "Dockerfile");
    writeFileSync(done, JSON.stringify(pkg));
    return pkg;
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
}

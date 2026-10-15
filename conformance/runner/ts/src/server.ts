import { spawn, spawnSync, type ChildProcess } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { getDefaultEnvironment, StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import type { Transport } from "@modelcontextprotocol/sdk/shared/transport.js";
import { stringify } from "yaml";
import type { Package } from "./packages.js";
import { imageTag, type Format } from "./formats.js";
import { java, workDir } from "./paths.js";

export type TransportKind = "stdio" | "http";

/** MCP revisions the server claims (ManifestWriter.MCP_REVISIONS); GATE_REVISIONS narrows them. */
export const CLAIMED_REVISIONS = ["2025-03-26", "2025-06-18", "2025-11-25"];

export function revisions(): string[] {
  const chosen = process.env.GATE_REVISIONS?.split(",").map((s) => s.trim()).filter(Boolean);
  return chosen?.length ? chosen : CLAIMED_REVISIONS;
}

export function transports(): TransportKind[] {
  const chosen = process.env.GATE_TRANSPORTS?.split(",").map((s) => s.trim()).filter(Boolean) as TransportKind[] | undefined;
  return chosen?.length ? chosen : ["stdio", "http"];
}

export interface StartOptions {
  pkg: Package;
  /** How the customer runs it: the jar, the desktop bundle (stdio), or the container image (HTTP). */
  format?: Format;
  transport: TransportKind;
  revision: string;
  /** The operator's runtime config (instances, auth, …); the server section is added for HTTP. */
  config: Record<string, unknown>;
  /** Client capabilities, e.g. { elicitation: {} }; none by default. */
  capabilities?: Record<string, unknown>;
}

export interface Running {
  client: Client;
  transport: TransportKind;
  /** The revision the server answered `initialize` with. */
  negotiated: string | undefined;
  /** Transport-level problems: a non-JSON line on stdout, a dropped stream, … */
  transportErrors: string[];
  /** What the server wrote to stderr (stdio) or its output (http), for failure reports. */
  log(): string;
  close(): Promise<void>;
}

/**
 * The client sends its own latest revision in `initialize`; the gate pins the one under test, and
 * records the revision the server answers with.
 */
function pinRevision(inner: Transport, revision: string, onNegotiated: (v: string) => void): Transport {
  let initializeId: unknown;
  return new Proxy(inner, {
    get(target, prop) {
      if (prop === "send") {
        return (message: any, options?: any) => {
          if (message?.method === "initialize") {
            initializeId = message.id;
            message = { ...message, params: { ...message.params, protocolVersion: revision } };
          }
          return target.send(message, options);
        };
      }
      const value = Reflect.get(target, prop, target);
      return typeof value === "function" ? value.bind(target) : value;
    },
    set(target, prop, value) {
      if (prop === "onmessage" && typeof value === "function") {
        const handler = value;
        value = (message: any, extra?: any) => {
          if (message?.id !== undefined && message.id === initializeId && message.result?.protocolVersion) {
            onNegotiated(message.result.protocolVersion);
          }
          return handler(message, extra);
        };
      }
      (target as any)[prop] = value;
      return true;
    },
  });
}

async function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : 0;
      server.close(() => resolve(port));
    });
  });
}

async function waitFor(url: string, process: ChildProcess, output: () => string, timeoutMs = 90_000): Promise<void> {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    if (process.exitCode !== null) throw new Error(`the server exited with ${process.exitCode} before it was ready:\n${output()}`);
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {
      // not listening yet
    }
    await new Promise((r) => setTimeout(r, 150));
  }
  throw new Error(`the server was not ready at ${url} within ${timeoutMs / 1000} s:\n${output()}`);
}

/** The key the gate's client signs in with when a format needs inbound auth (the container listens beyond loopback). */
const CLIENT_KEY = "gate-client-key-0123456789abcdef0123456789";

/** The environment prefix of a server's settings, e.g. KITCHEN_SINK_ (runtime-config §1.1). */
function envPrefix(name: string): string {
  return name.toUpperCase().replace(/[^A-Z0-9]+/g, "_") + "_";
}

/**
 * What a desktop client would put in the bundle's install dialog, from the operator config the test
 * would otherwise give in a file: the target server and its sign-in, or the config file itself.
 */
function userConfig(config: Record<string, any>, configFile: string): Record<string, string> {
  const instance = config.instance ?? Object.values<any>(config.apis ?? {})[0]?.instance ?? config.instances?.[0] ?? {};
  const auth = instance.auth ?? {};
  const values: Record<string, string | undefined> = {
    config: configFile,
    base_url: instance.baseUrl,
    username: auth.username,
    password: auth.password,
    token: auth.token,
    api_key: auth.value,
    token_url: auth.tokenUrl,
    client_id: auth.clientId,
    client_secret: auth.clientSecret,
  };
  return Object.fromEntries(Object.entries(values).filter(([, v]) => v !== undefined)) as Record<string, string>;
}

/** The bundle's command, arguments and environment, filled in as a desktop client does (MCPB manifest 0.3). */
function bundleCommand(dir: string, home: string, values: Record<string, string>): { args: string[]; env: Record<string, string> } {
  const manifest = JSON.parse(readFileSync(`${dir}/manifest.json`, "utf8"));
  const declared: Record<string, any> = manifest.user_config ?? {};
  const fill = (text: string) =>
    text.replace(/\$\{([^}]+)\}/g, (_m, name: string) => {
      if (name === "__dirname") return dir;
      if (name === "HOME") return home;
      if (name.startsWith("user_config.")) {
        const key = name.slice("user_config.".length);
        if (!(key in declared)) throw new Error(`the bundle uses \${${name}} but declares no user_config.${key}`);
        if (values[key] === undefined) throw new Error(`the bundle asks for user_config.${key}, which the gate's config doesn't give`);
        return values[key];
      }
      throw new Error(`the bundle uses \${${name}}, which desktop clients don't fill in`);
    });
  for (const [key, field] of Object.entries(declared)) {
    if (field.required && values[key] === undefined) throw new Error(`the bundle requires user_config.${key}, which the gate's config doesn't give`);
  }
  const config = manifest.server?.mcp_config ?? {};
  if (config.command !== "java") throw new Error(`the bundle runs ${config.command}, not java`);
  return {
    args: (config.args ?? []).map(fill),
    env: Object.fromEntries(Object.entries<string>(config.env ?? {}).map(([k, v]) => [k, fill(v)])),
  };
}

function running(client: Client, transport: TransportKind, state: { negotiated?: string }, transportErrors: string[], log: () => string, close: () => Promise<void>): Running {
  return {
    client,
    transport,
    get negotiated() {
      return state.negotiated;
    },
    transportErrors,
    log,
    close,
  };
}

/** Starts the packaged server as the customer would and connects the independent client to it. */
export async function startServer(options: StartOptions): Promise<Running> {
  const format = options.format ?? "jar";
  const dir = mkdtempSync(join(workDir("runs"), "run-"));
  const config: Record<string, any> = structuredClone(options.config);
  const state: { negotiated?: string } = {};
  const transportErrors: string[] = [];
  const client = new Client({ name: "mcpgp-gate", version: "1" }, { capabilities: options.capabilities ?? {} });
  client.onerror = (error) => transportErrors.push(String(error?.message ?? error));
  const pin = (inner: Transport) => pinRevision(inner, options.revision, (v) => (state.negotiated = v));

  if (format === "mcpb") {
    if (options.transport !== "stdio") throw new Error("a desktop bundle runs over stdio");
    if (!options.pkg.mcpb) throw new Error(`${options.pkg.zip} has no desktop bundle`);
    const configFile = join(dir, `${options.pkg.name}.yaml`);
    writeFileSync(configFile, stringify(config));
    const home = join(dir, "home");
    mkdirSync(home);
    const { args, env } = bundleCommand(options.pkg.mcpb, home, userConfig(config, configFile));
    const inner = new StdioClientTransport({ command: java(), args, env: { ...getDefaultEnvironment(), HOME: home, ...env }, cwd: dir, stderr: "pipe" });
    let stderr = "";
    inner.stderr?.on("data", (chunk) => (stderr += chunk.toString()));
    await client.connect(pin(inner));
    return running(client, "stdio", state, transportErrors, () => stderr, () => client.close());
  }

  if (format === "docker") {
    if (options.transport !== "http") throw new Error("the container image runs over HTTP");
    const port = await freePort();
    const configDir = join(dir, "config");
    mkdirSync(configDir);
    // beyond loopback the server needs inbound auth (auth.md §1): the gate signs in with a static key
    config.server = { ...(config.server ?? {}), auth: { mode: "static", keys: [{ name: "gate", value: CLIENT_KEY }] } };
    config.allowInlineSecrets = true;
    writeFileSync(join(configDir, `${options.pkg.name}.yaml`), stringify(config));
    chmodSync(configDir, 0o755);
    chmodSync(join(configDir, `${options.pkg.name}.yaml`), 0o644);
    const run = spawnSync("docker", ["run", "-d", "--rm", "--network", "host", "-e", `${envPrefix(options.pkg.name)}SERVER__PORT=${port}`,
      "-v", `${configDir}:/config:ro`, imageTag(options.pkg)], { encoding: "utf8" });
    if (run.status !== 0) throw new Error(`docker run failed: ${run.stderr}`);
    const container = run.stdout.trim();
    const logs = () => spawnSync("docker", ["logs", container], { encoding: "utf8" }).stdout + spawnSync("docker", ["logs", container], { encoding: "utf8" }).stderr;
    const stop = () => void spawnSync("docker", ["rm", "-f", container], { stdio: "ignore" });
    const base = `http://127.0.0.1:${port}`;
    try {
      const until = Date.now() + 120_000;
      for (;;) {
        try {
          if ((await fetch(`${base}/health/live`)).ok) break;
        } catch {
          // not listening yet
        }
        if (Date.now() > until) throw new Error(`the container was not ready within 120 s:\n${logs()}`);
        await new Promise((r) => setTimeout(r, 250));
      }
      const inner = new StreamableHTTPClientTransport(new URL(`${base}/mcp`), { requestInit: { headers: { Authorization: `Bearer ${CLIENT_KEY}` } } });
      await client.connect(pin(inner as unknown as Transport));
    } catch (error) {
      stop();
      throw error;
    }
    return running(client, "http", state, transportErrors, logs, async () => {
      try {
        await client.close();
      } finally {
        stop();
      }
    });
  }

  let port = 0;
  if (options.transport === "http") {
    port = await freePort();
    config.server = { ...(config.server ?? {}), transport: "http", host: "127.0.0.1", port };
  } else {
    config.server = { ...(config.server ?? {}), transport: "stdio" };
  }
  const configFile = join(dir, "server.yaml");
  writeFileSync(configFile, stringify(config));
  const args = ["-jar", options.pkg.jar, "--config", configFile, "--storage.root", join(dir, "data")];

  if (options.transport === "stdio") {
    const inner = new StdioClientTransport({ command: java(), args, cwd: dir, stderr: "pipe" });
    let stderr = "";
    inner.stderr?.on("data", (chunk) => (stderr += chunk.toString()));
    await client.connect(pin(inner));
    return running(client, "stdio", state, transportErrors, () => stderr, () => client.close());
  }

  let output = "";
  const child = spawn(java(), args, { cwd: dir, stdio: ["ignore", "pipe", "pipe"] });
  child.stdout?.on("data", (chunk) => (output += chunk.toString()));
  child.stderr?.on("data", (chunk) => (output += chunk.toString()));
  const base = `http://127.0.0.1:${port}`;
  try {
    await waitFor(`${base}/health/live`, child, () => output);
    const inner = new StreamableHTTPClientTransport(new URL(`${base}/mcp`));
    await client.connect(pin(inner as unknown as Transport));
  } catch (error) {
    child.kill("SIGKILL");
    throw error;
  }
  return running(client, "http", state, transportErrors, () => output, async () => {
    try {
      await client.close();
    } finally {
      child.kill("SIGTERM");
      await new Promise<void>((resolve) => {
        const timer = setTimeout(() => {
          child.kill("SIGKILL");
          resolve();
        }, 5000);
        child.once("exit", () => {
          clearTimeout(timer);
          resolve();
        });
      });
    }
  });
}

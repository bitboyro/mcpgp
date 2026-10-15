import { spawn, type ChildProcess } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import type { Transport } from "@modelcontextprotocol/sdk/shared/transport.js";
import { stringify } from "yaml";
import type { Package } from "./packages.js";
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

/** Starts the packaged server as the customer would and connects the independent client to it. */
export async function startServer(options: StartOptions): Promise<Running> {
  const dir = mkdtempSync(join(workDir("runs"), "run-"));
  const config: Record<string, any> = structuredClone(options.config);
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

  let negotiated: string | undefined;
  const transportErrors: string[] = [];
  const client = new Client({ name: "mcpgp-gate", version: "1" }, { capabilities: options.capabilities ?? {} });
  client.onerror = (error) => transportErrors.push(String(error?.message ?? error));

  if (options.transport === "stdio") {
    const inner = new StdioClientTransport({ command: java(), args, cwd: dir, stderr: "pipe" });
    let stderr = "";
    inner.stderr?.on("data", (chunk) => (stderr += chunk.toString()));
    const transport = pinRevision(inner, options.revision, (v) => (negotiated = v));
    await client.connect(transport);
    return {
      client,
      transport: "stdio",
      get negotiated() {
        return negotiated;
      },
      transportErrors,
      log: () => stderr,
      close: async () => {
        await client.close();
      },
    };
  }

  let output = "";
  const child = spawn(java(), args, { cwd: dir, stdio: ["ignore", "pipe", "pipe"] });
  child.stdout?.on("data", (chunk) => (output += chunk.toString()));
  child.stderr?.on("data", (chunk) => (output += chunk.toString()));
  const base = `http://127.0.0.1:${port}`;
  try {
    await waitFor(`${base}/health/live`, child, () => output);
    const inner = new StreamableHTTPClientTransport(new URL(`${base}/mcp`));
    const transport = pinRevision(inner as unknown as Transport, options.revision, (v) => (negotiated = v));
    await client.connect(transport);
  } catch (error) {
    child.kill("SIGKILL");
    throw error;
  }
  return {
    client,
    transport: "http",
    get negotiated() {
      return negotiated;
    },
    transportErrors,
    log: () => output,
    close: async () => {
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
    },
  };
}

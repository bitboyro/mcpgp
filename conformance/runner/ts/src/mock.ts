import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { sample } from "./sample.js";
import { findOperation, loadContract, type Contract, type Operation } from "./spec.js";
import { validateRequest, type Request } from "./validate.js";

/** How the mock answers one request. */
export interface Reply {
  status?: number;
  headers?: Record<string, string>;
  json?: unknown;
  body?: string;
  /** Faults: drop the connection, or answer a JSON operation with text that isn't JSON. */
  fault?: "reset" | "invalid-json";
  delayMs?: number;
}

export interface Received extends Request {
  /** The operation it matched, e.g. GET /api/items/{itemId}. */
  operation?: string;
  /** What is wrong with it, checked against the contract (empty when right). */
  violations: string[];
  status: number;
}

/**
 * The gate's API mock (release-gate.md §3): answers every operation of the contracts with a value
 * made from its success response, or with what a test asked for, and checks every request against
 * the contract. It never calls anything and listens on loopback only.
 */
export class Mock {
  readonly journal: Received[] = [];
  private readonly queue: Reply[] = [];
  private readonly perOperation = new Map<string, Reply[]>();
  private server?: Server;
  url = "";

  private constructor(readonly contracts: Contract[]) {}

  static async start(specFiles: string[]): Promise<Mock> {
    const mock = new Mock(await Promise.all(specFiles.map(loadContract)));
    mock.server = createServer((req, res) => void mock.handle(req, res));
    await new Promise<void>((resolve) => mock.server!.listen(0, "127.0.0.1", resolve));
    mock.url = `http://127.0.0.1:${(mock.server!.address() as AddressInfo).port}`;
    return mock;
  }

  async stop(): Promise<void> {
    this.server?.closeAllConnections();
    await new Promise<void>((resolve) => this.server?.close(() => resolve()) ?? resolve());
  }

  /** The next request, whatever its operation, gets this reply. */
  next(reply: Reply): void {
    this.queue.push(reply);
  }

  /** The next request to this operation gets this reply. */
  forOperation(key: string, reply: Reply): void {
    const list = this.perOperation.get(key) ?? [];
    list.push(reply);
    this.perOperation.set(key, list);
  }

  reset(): void {
    this.journal.length = 0;
    this.queue.length = 0;
    this.perOperation.clear();
  }

  operations(): Operation[] {
    return this.contracts.flatMap((c) => c.operations);
  }

  private async handle(req: IncomingMessage, res: ServerResponse) {
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk as Buffer);
    const url = new URL(req.url ?? "/", "http://mock");
    const headers: Record<string, string> = {};
    for (const [k, v] of Object.entries(req.headers)) headers[k.toLowerCase()] = Array.isArray(v) ? v.join(", ") : String(v ?? "");
    const request: Request = {
      method: (req.method ?? "GET").toUpperCase(),
      path: url.pathname,
      query: url.search.replace(/^\?/, ""),
      headers,
      body: Buffer.concat(chunks),
    };
    const op = findOperation(this.contracts, request.method, request.path);
    const violations = op ? validateRequest(op, request) : [`no operation ${request.method} ${request.path} in the contracts`];
    const reply = (op && this.perOperation.get(op.key)?.shift()) ?? this.queue.shift() ?? (op ? this.success(op) : { status: 404, json: { title: "no such operation" } });
    const status = reply.status ?? 200;
    this.journal.push({ ...request, operation: op?.key, violations, status });
    if (reply.delayMs) await new Promise((r) => setTimeout(r, reply.delayMs));
    if (reply.fault === "reset") {
      req.socket.destroy();
      return;
    }
    const outHeaders: Record<string, string> = { ...(reply.headers ?? {}) };
    let body: string | Buffer | undefined;
    if (reply.fault === "invalid-json") {
      outHeaders["content-type"] ??= "application/json";
      body = "{ this is not json";
    } else if (reply.json !== undefined) {
      outHeaders["content-type"] ??= "application/json";
      body = JSON.stringify(reply.json);
    } else if (reply.body !== undefined) {
      body = reply.body;
    }
    res.writeHead(status, outHeaders);
    res.end(status === 204 || req.method === "HEAD" ? undefined : body);
  }

  /** The operation's first success response, else 200 with an empty object. */
  success(op: Operation): Reply {
    const status = Object.keys(op.responses).find((s) => s.startsWith("2"));
    return status ? this.response(op, status) : { status: 200, json: {} };
  }

  /** A reply for one of the operation's documented responses, with a body made from its schema. */
  response(op: Operation, status: string): Reply {
    const code = /^\d{3}$/.test(status) ? Number(status) : status.toUpperCase() === "DEFAULT" ? 500 : Number(status[0]) * 100;
    const r = op.responses[status];
    const headers: Record<string, string> = {};
    if (r?.headers?.Location || r?.headers?.location) headers.location = `${op.contract.basePath}/gate/1`;
    if (code === 429) headers["retry-after"] = "3";
    if (!r || code === 204) return { status: code, headers };
    const types = Object.keys(r.content);
    if (!types.length) return { status: code, headers };
    const type = types.find((t) => t.includes("json")) ?? types[0];
    const media = r.content[type];
    headers["content-type"] = type === "*/*" ? "application/json" : type;
    if (type.includes("json")) {
      const value = media.example ?? sample(media.schema ?? {}, { optional: "all", examples: true, resolve: op.contract.resolve });
      return { status: code, headers, json: value };
    }
    if (type === "text/csv") return { status: code, headers, body: "id,name\ngate-1,Gate\n" };
    if (type.startsWith("text/")) return { status: code, headers, body: "gate" };
    return { status: code, headers, body: "gate-bytes" };
  }
}

import { afterAll, beforeAll, describe, expect, test } from "vitest";
import { contractsOf, fixtures } from "../src/fixtures.js";
import { Mock } from "../src/mock.js";
import { documentedCodes } from "../src/results.js";
import { sample } from "../src/sample.js";
import { matches } from "../src/traffic.js";

/**
 * The gate checking itself: a green run only means something if the mock catches wrong requests. Each
 * request here is wrong in one way (or right), sent straight to the mock, without mcpgp.
 */
describe("self-check: the mock flags what the contract rejects", () => {
  let mock: Mock;
  const key = { "x-api-key": "k" };

  beforeAll(async () => {
    mock = await Mock.start(contractsOf(fixtures()[0].definition).map((c) => c.spec));
  });
  afterAll(async () => mock?.stop());

  async function violations(path: string, init: RequestInit = {}): Promise<string[]> {
    mock.reset();
    await fetch(mock.url + path, init).then((r) => r.arrayBuffer()).catch(() => undefined);
    expect(mock.journal).toHaveLength(1);
    return mock.journal[0].violations;
  }

  function multipart(parts: Array<{ name: string; type?: string; filename?: string; body: string }>) {
    const boundary = "gateboundary";
    const body = parts
      .map((p) => `--${boundary}\r\nContent-Disposition: form-data; name="${p.name}"${p.filename ? `; filename="${p.filename}"` : ""}\r\n${p.type ? `Content-Type: ${p.type}\r\n` : ""}\r\n${p.body}\r\n`)
      .join("") + `--${boundary}--\r\n`;
    return { method: "POST", headers: { ...key, "content-type": `multipart/form-data; boundary=${boundary}` }, body };
  }

  test("a right request has no violations", async () => {
    expect(await violations("/api/items/abc-1", { headers: key })).toEqual([]);
    expect(await violations("/api/items?limit=5&tag=red&tag=blue&sort=-name", { headers: key })).toEqual([]);
    expect(await violations("/api/status")).toEqual([]);
  });

  const wrong: Array<[string, string, RequestInit, RegExp]> = [
    ["no credentials", "/api/items/abc", {}, /no credentials/],
    ["credentials sent to a public operation", "/api/status", { headers: key }, /public operation/],
    ["a path parameter outside its pattern", "/api/items/UPPER_CASE", { headers: key }, /path itemId/],
    ["an integer parameter that isn't one", "/api/items?limit=many", { headers: key }, /query limit/],
    ["a parameter above its maximum", "/api/items?limit=500", { headers: key }, /query limit/],
    ["a value outside an enum", "/api/items?sort=size", { headers: key }, /query sort/],
    ["a query parameter the operation doesn't have", "/api/items?colour=red", { headers: key }, /query colour/],
    ["a missing required query parameter", "/api/reports/items", { headers: key }, /query from: required/],
    ["a JSON body missing a required field", "/api/items", { method: "POST", headers: { ...key, "content-type": "application/json" }, body: JSON.stringify({ kind: "tool" }) }, /body.*name/],
    ["a JSON body of the wrong type", "/api/items", { method: "POST", headers: { ...key, "content-type": "application/json" }, body: JSON.stringify({ name: 3, kind: "tool" }) }, /body\/name/],
    ["a body that isn't JSON", "/api/items", { method: "POST", headers: { ...key, "content-type": "application/json" }, body: "{oops" }, /not JSON/],
    ["a content type the operation doesn't take", "/api/items", { method: "POST", headers: { ...key, "content-type": "text/plain" }, body: "x" }, /Content-Type text\/plain/],
    ["a missing required body", "/api/items", { method: "POST", headers: key }, /body: required/],
    ["a body sent to an operation without one", "/api/items/abc", { method: "DELETE", headers: { ...key, "content-type": "application/json" }, body: "{}" }, /takes none/],
    ["a form missing a required field", "/api/search", { method: "POST", headers: { ...key, "content-type": "application/x-www-form-urlencoded" }, body: "page=2" }, /form.*query/],
    ["a form field of the wrong type", "/api/search", { method: "POST", headers: { ...key, "content-type": "application/x-www-form-urlencoded" }, body: "query=a&page=two" }, /form\/page/],
    ["a multipart body missing a required part", "/api/items/abc/attachments", multipart([{ name: "caption", body: "hi" }]), /part file is required/],
    ["a multipart part with the wrong content type", "/api/items/abc/attachments", multipart([{ name: "file", filename: "a.bin", type: "application/octet-stream", body: "x" }, { name: "meta", type: "text/plain", body: "{}" }]), /part meta has Content-Type/],
    ["a request no operation has", "/api/nowhere", { headers: key }, /no operation/],
  ];
  for (const [name, path, init, problem] of wrong) {
    test(`flags ${name}`, async () => {
      const found = await violations(path, init);
      expect(found.some((v) => problem.test(v)), `violations: ${JSON.stringify(found)}`).toBe(true);
    });
  }

  test("values made from a schema satisfy it", () => {
    const contract = mock.contracts[0];
    for (const name of ["NewItem", "Item", "Owner", "Shape"]) {
      const schema = { $ref: `#/components/schemas/${name}` };
      for (const optional of ["all", "none"] as const) {
        const value = sample(schema, { optional, resolve: contract.resolve });
        const validate = contract.validator(schema);
        expect(validate(value), `${name} (${optional}): ${JSON.stringify(validate.errors)} for ${JSON.stringify(value)}`).toBe(true);
      }
    }
  });
});

describe("self-check: matchers and codes", () => {
  test("the error codes are read from audit-and-errors.md", () => {
    const codes = documentedCodes();
    for (const code of ["INPUT_VALIDATION", "CONFIRMATION_REQUIRED", "UPSTREAM_4XX", "UPSTREAM_UNREACHABLE", "INTERNAL"]) expect(codes.has(code), code).toBe(true);
  });

  test("matchers", () => {
    const check = (actual: unknown, want: unknown) => {
      const problems: string[] = [];
      matches(actual, want, "x", problems);
      return problems.length === 0;
    };
    expect(check("multipart/form-data; boundary=1", { startsWith: "multipart/form-data; boundary=" })).toBe(true);
    expect(check("abc", { startsWith: "x" })).toBe(false);
    expect(check("0123456789abcdef0123456789abcdef", { matches: "^[0-9a-f]{32}$" })).toBe(true);
    expect(check(undefined, { absent: true })).toBe(true);
    expect(check("Basic x", { absent: true })).toBe(false);
    expect(check({ a: 1, b: 2 }, { a: 1 })).toBe(true);
    expect(check({ a: 1 }, { a: 2 })).toBe(false);
    expect(check(undefined, { any: true })).toBe(false);
  });
});

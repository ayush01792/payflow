import { expect, it, afterEach } from "vitest";
import { client } from "./client";
import { replaceKey } from "./session";
import type { AxiosAdapter, InternalAxiosRequestConfig } from "axios";
const original = client.defaults.adapter;
afterEach(() => {
  client.defaults.adapter = original;
});
it("attaches the credential to merchant requests but not health", async () => {
  replaceKey("unit-test-only");
  const captured: InternalAxiosRequestConfig[] = [];
  const adapter: AxiosAdapter = async (config) => {
    captured.push(config);
    return { data: {}, status: 200, statusText: "OK", headers: {}, config };
  };
  client.defaults.adapter = adapter;
  await client.get("/api/v1/payments");
  await client.get("/api/v1/health");
  expect(captured[0].headers.get("X-API-Key")).toBe("unit-test-only");
  expect(captured[1].headers.has("X-API-Key")).toBe(false);
});
it("honors a candidate key during verification instead of replacing it with old session", async () => {
  replaceKey("old-unit-test");
  let sent: unknown;
  client.defaults.adapter = async (config) => {
    sent = config.headers.get("X-API-Key");
    return { data: {}, status: 200, statusText: "OK", headers: {}, config };
  };
  await client.get("/api/v1/payments", {
    headers: { "X-API-Key": "candidate-unit-test" },
  });
  expect(sent).toBe("candidate-unit-test");
});

import { expect, it } from "vitest";
import {
  getKey,
  replaceKey,
  recentRefunds,
  saveRecentRefund,
  pendingKey,
  KEY_STORAGE,
} from "./session";
it("stores credentials only in tab session storage", () => {
  replaceKey("unit-test-only");
  expect(getKey()).toBe("unit-test-only");
  expect(sessionStorage.getItem(KEY_STORAGE)).toBe("unit-test-only");
  expect(localStorage.getItem(KEY_STORAGE)).toBeNull();
});
it("clears tenant history and unknown submissions on credential switch", () => {
  replaceKey("first-unit-credential");
  saveRecentRefund({
    id: "r",
    paymentId: "p",
    amount: 1,
    currency: "USD",
    createdAt: "2026-10-11T00:00:00Z",
  });
  sessionStorage.setItem(pendingKey("payment"), "{}");
  replaceKey("second-unit-credential");
  expect(recentRefunds()).toEqual([]);
  expect(sessionStorage.getItem(pendingKey("payment"))).toBeNull();
  expect(getKey()).toBe("second-unit-credential");
});
it("disconnect clears key without deleting unrelated session data", () => {
  replaceKey("unit-test-only");
  sessionStorage.setItem("another-app", "keep");
  replaceKey("");
  expect(getKey()).toBe("");
  expect(sessionStorage.getItem("another-app")).toBe("keep");
});

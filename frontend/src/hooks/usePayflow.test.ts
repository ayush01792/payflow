import { expect, it } from "vitest";
import { pollInterval } from "./usePayflow";
it.each(["CREATED", "PROCESSING"] as const)(
  "polls active %s at 2 seconds",
  (status) => expect(pollInterval(status)).toBe(2000),
);
it("slows uncertain results instead of declaring failure", () =>
  expect(pollInterval("PENDING_CONFIRMATION")).toBe(10000));
it.each(["SUCCESS", "FAILED", "REFUNDED", "PARTIALLY_REFUNDED"] as const)(
  "stops terminal %s polling",
  (status) => expect(pollInterval(status)).toBe(false),
);

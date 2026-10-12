import { describe, expect, it } from "vitest";
import { amount, shortId } from "./format";
describe("financial display", () => {
  it("uses INR currency formatting", () =>
    expect(amount(2499, "INR")).toBe("₹2,499.00"));
  it("uses USD currency formatting", () =>
    expect(amount(29.99, "USD")).toBe("$29.99"));
  it("preserves supported fractional display", () =>
    expect(amount(1.1234, "USD")).toBe("$1.1234"));
  it("shortens display without changing original identity", () => {
    const id = "c4830685-22d8-41b2-b5eb-c22ef6bd53b1";
    expect(shortId(id)).toBe("c4830685…53b1");
    expect(id).toHaveLength(36);
  });
});

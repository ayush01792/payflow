import { expect, it } from "vitest";
import { validateAmount, validatePayment, validateRefund } from "./validation";
it.each(["0", "-1", "1e3", "1.12345", "1000000000000000", "abc"])(
  "rejects unsafe amount %s",
  (value) => expect(validateAmount(value)).not.toBe(""),
);
it.each(["1", "0.0001", "123.4567", "999999999999999"])(
  "accepts decimal amount %s",
  (value) => expect(validateAmount(value)).toBe(""),
);
it("validates payment identity and currency", () => {
  const errors = validatePayment({
    userId: " ",
    amount: "10",
    currency: "inr",
    paymentMethod: "UPI",
  });
  expect(errors.userId).toBeTruthy();
  expect(errors.currency).toBeTruthy();
});
it("requires a refund reference", () =>
  expect(
    validateRefund({ amount: "1", merchantRefundReference: "" })
      .merchantRefundReference,
  ).toBeTruthy());

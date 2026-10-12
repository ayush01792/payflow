import type { CreatePayment, CreateRefund } from "../types/api";
export function validateAmount(value: string) {
  return /^(?:0|[1-9]\d{0,14})(?:\.\d{1,4})?$/.test(value) &&
    /[1-9]/.test(value)
    ? ""
    : "Enter a positive amount with up to 15 integer digits and 4 decimal places.";
}
export function validatePayment(p: CreatePayment) {
  const errors: Record<string, string> = {};
  if (!p.userId.trim() || p.userId.length > 100)
    errors.userId = "Enter a user ID (1–100 characters).";
  if (validateAmount(p.amount)) errors.amount = validateAmount(p.amount);
  if (!/^[A-Z]{3}$/.test(p.currency))
    errors.currency = "Use a three-letter currency code, such as INR or USD.";
  if (!["UPI", "CARD", "NET_BANKING", "WALLET"].includes(p.paymentMethod))
    errors.paymentMethod = "Choose a supported method.";
  return errors;
}
export function validateRefund(p: CreateRefund) {
  const errors: Record<string, string> = {};
  if (validateAmount(p.amount)) errors.amount = validateAmount(p.amount);
  if (
    !p.merchantRefundReference.trim() ||
    p.merchantRefundReference.length > 100
  )
    errors.merchantRefundReference =
      "Enter a unique reference (1–100 characters).";
  return errors;
}

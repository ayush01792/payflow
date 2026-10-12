export function amount(value: number, currency: string) {
  try {
    return new Intl.NumberFormat("en-IN", {
      style: "currency",
      currency,
      maximumFractionDigits: 4,
    }).format(value);
  } catch {
    return (
      new Intl.NumberFormat("en-IN", { maximumFractionDigits: 4 }).format(
        value,
      ) +
      " " +
      currency
    );
  }
}
export function date(value: string) {
  const d = new Date(value);
  return Number.isNaN(d.valueOf())
    ? "Unavailable"
    : new Intl.DateTimeFormat(undefined, {
        dateStyle: "medium",
        timeStyle: "short",
      }).format(d);
}
export function shortId(id: string) {
  return id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id;
}
export const methodLabels = {
  UPI: "UPI",
  CARD: "Card",
  NET_BANKING: "Net banking",
  WALLET: "Wallet",
};
export const providerLabel = (value: string | null) =>
  value === "PROVIDER_A"
    ? "Provider A"
    : value === "PROVIDER_B"
      ? "Provider B"
      : value || "Not yet available";

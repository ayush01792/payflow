import type { RecentRefund } from "../types/api";
const prefix = "payflow.demo.";
export const KEY_STORAGE = prefix + "apiKey";
export function getKey() {
  try {
    return sessionStorage.getItem(KEY_STORAGE) || "";
  } catch {
    return "";
  }
}
export function replaceKey(key: string) {
  const entries = Object.keys(sessionStorage).filter((k) =>
    k.startsWith(prefix),
  );
  entries.forEach((k) => sessionStorage.removeItem(k));
  if (key) sessionStorage.setItem(KEY_STORAGE, key.trim());
}
export function recentRefunds(): RecentRefund[] {
  try {
    return JSON.parse(
      sessionStorage.getItem(prefix + "refunds") || "[]",
    ) as RecentRefund[];
  } catch {
    return [];
  }
}
export function saveRecentRefund(r: RecentRefund) {
  const list = [r, ...recentRefunds().filter((x) => x.id !== r.id)].slice(
    0,
    25,
  );
  sessionStorage.setItem(prefix + "refunds", JSON.stringify(list));
  return list;
}
export const pendingKey = (scope: string) => prefix + "pending." + scope;

import { useRef, useState } from "react";
import { pendingKey } from "../api/session";
interface Attempt<T> {
  body: T;
  key: string;
}
export function useLogicalSubmission<T>(scope: string) {
  const storage = pendingKey(scope);
  const [attempt, setAttempt] = useState<Attempt<T> | null>(() => {
    try {
      return JSON.parse(
        sessionStorage.getItem(storage) || "null",
      ) as Attempt<T> | null;
    } catch {
      return null;
    }
  });
  const current = useRef(attempt);
  function begin(body: T) {
    if (current.current) return current.current;
    const next = { body, key: crypto.randomUUID() };
    sessionStorage.setItem(storage, JSON.stringify(next));
    current.current = next;
    setAttempt(next);
    return next;
  }
  function clear() {
    sessionStorage.removeItem(storage);
    current.current = null;
    setAttempt(null);
  }
  return { attempt, begin, clear };
}

import {
  createContext,
  useContext,
  useState,
  useRef,
  type ReactNode,
} from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  getKey,
  replaceKey,
  recentRefunds,
  saveRecentRefund,
} from "../api/session";
import type { RecentRefund } from "../types/api";
interface Session {
  connected: boolean;
  sessionId: string;
  refunds: RecentRefund[];
  connect: (key: string) => Promise<void>;
  disconnect: () => Promise<void>;
  addRefund: (refund: RecentRefund) => void;
}
const Context = createContext<Session | null>(null);
export function SessionProvider({ children }: { children: ReactNode }) {
  const query = useQueryClient();
  const [connected, setConnected] = useState(() => Boolean(getKey()));
  const [sessionId, setSessionId] = useState(() => crypto.randomUUID());
  const current = useRef(sessionId);
  const [refunds, setRefunds] = useState(recentRefunds);
  async function change(key: string) {
    const next = crypto.randomUUID();
    current.current = next;
    await query.cancelQueries();
    query.clear();
    replaceKey(key);
    setSessionId(next);
    setConnected(Boolean(key));
    setRefunds([]);
  }
  return (
    <Context.Provider
      value={{
        connected,
        sessionId,
        refunds,
        connect: change,
        disconnect: () => change(""),
        addRefund: (r) => {
          if (current.current === sessionId) setRefunds(saveRecentRefund(r));
        },
      }}
    >
      {children}
    </Context.Provider>
  );
}
export function useSession() {
  const value = useContext(Context);
  if (!value) throw new Error("SessionProvider is required");
  return value;
}

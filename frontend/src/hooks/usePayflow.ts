import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import * as api from "../api/payflow";
import { useSession } from "./SessionProvider";
import type {
  CreatePayment,
  CreateRefund,
  PaymentStatus,
  RefundStatus,
} from "../types/api";
export function pollInterval(status?: PaymentStatus | RefundStatus) {
  return !status || status === "CREATED" || status === "PROCESSING"
    ? 2000
    : status === "PENDING_CONFIRMATION"
      ? 10000
      : false;
}
export function usePayments(page = 0, size = 20, sort = "createdAt,desc") {
  const { sessionId, connected } = useSession();
  return useQuery({
    queryKey: [sessionId, "payments", page, size, sort],
    queryFn: ({ signal }) => api.payments(page, size, sort, signal),
    enabled: connected,
    placeholderData: keepPreviousData,
  });
}
export function usePayment(id: string) {
  const { sessionId, connected } = useSession();
  return useQuery({
    queryKey: [sessionId, "payment", id],
    queryFn: ({ signal }) => api.payment(id, signal),
    enabled: connected && Boolean(id),
    refetchInterval: (q) =>
      q.state.error ? false : pollInterval(q.state.data?.status),
    refetchIntervalInBackground: false,
  });
}
export function useRefund(id: string) {
  const { sessionId, connected } = useSession();
  return useQuery({
    queryKey: [sessionId, "refund", id],
    queryFn: ({ signal }) => api.refund(id, signal),
    enabled: connected && Boolean(id),
    refetchInterval: (q) =>
      q.state.error ? false : pollInterval(q.state.data?.status),
    refetchIntervalInBackground: false,
  });
}
export function useHealth() {
  return useQuery({
    queryKey: ["health"],
    queryFn: ({ signal }) => api.health(signal),
    staleTime: 15000,
    refetchInterval: 30000,
    refetchIntervalInBackground: false,
  });
}
export function useCreatePayment() {
  const query = useQueryClient();
  const { sessionId } = useSession();
  return useMutation({
    mutationFn: ({ body, key }: { body: CreatePayment; key: string }) =>
      api.createPayment(body, key),
    retry: false,
    onSuccess: () =>
      query.invalidateQueries({ queryKey: [sessionId, "payments"] }),
  });
}
export function useCreateRefund(id: string) {
  const query = useQueryClient();
  const { sessionId, addRefund } = useSession();
  return useMutation({
    mutationFn: ({ body, key }: { body: CreateRefund; key: string }) =>
      api.createRefund(id, body, key),
    retry: false,
    onSuccess: (r) => {
      addRefund(r);
      void query.invalidateQueries({ queryKey: [sessionId, "payment", id] });
      void query.invalidateQueries({ queryKey: [sessionId, "payments"] });
    },
  });
}

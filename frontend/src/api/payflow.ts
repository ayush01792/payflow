import { client } from "./client";
import type {
  Payment,
  Refund,
  Page,
  CreatePayment,
  CreateRefund,
} from "../types/api";
export async function payments(
  page: number,
  size: number,
  sort: string,
  signal?: AbortSignal,
) {
  return (
    await client.get<Page<Payment>>("/api/v1/payments", {
      params: { page, size, sort },
      signal,
    })
  ).data;
}
export async function payment(id: string, signal?: AbortSignal) {
  return (
    await client.get<Payment>(`/api/v1/payments/${encodeURIComponent(id)}`, {
      signal,
    })
  ).data;
}
export async function createPayment(body: CreatePayment, key: string) {
  return (
    await client.post<Payment>("/api/v1/payments", body, {
      headers: { "Idempotency-Key": key },
    })
  ).data;
}
export async function refund(id: string, signal?: AbortSignal) {
  return (
    await client.get<Refund>(`/api/v1/refunds/${encodeURIComponent(id)}`, {
      signal,
    })
  ).data;
}
export async function createRefund(
  id: string,
  body: CreateRefund,
  key: string,
) {
  return (
    await client.post<Refund>(
      `/api/v1/payments/${encodeURIComponent(id)}/refunds`,
      body,
      { headers: { "Idempotency-Key": key } },
    )
  ).data;
}
export async function health(signal?: AbortSignal) {
  return (
    await client.get<{ status: string; service: string }>("/api/v1/health", {
      signal,
    })
  ).data;
}
export async function verifyKey(key: string) {
  await client.get("/api/v1/payments", {
    params: { page: 0, size: 1 },
    headers: { "X-API-Key": key },
  });
}

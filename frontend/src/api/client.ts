import axios from "axios";
import { getKey } from "./session";
// In development VITE_PAYFLOW_API_URL is the Vite proxy target. Container builds use /backend.
const configured =
  import.meta.env.VITE_PAYFLOW_API_URL || "http://localhost:8080";
export const client = axios.create({
  baseURL: import.meta.env.DEV ? "/backend" : configured,
  timeout: 15000,
});
client.interceptors.request.use((config) => {
  const merchant = /^\/api\/v1\/(payments|refunds)(?:\/|$)/.test(
    config.url || "",
  );
  if (merchant && !config.headers.has("X-API-Key")) {
    const key = getKey();
    if (key) config.headers.set("X-API-Key", key);
  }
  return config;
});
export interface FriendlyError {
  status?: number;
  title: string;
  message: string;
  retryAfter?: number;
}
export function friendlyError(error: unknown): FriendlyError {
  if (!axios.isAxiosError(error))
    return {
      title: "Something went wrong",
      message:
        "Please retry. If the problem continues, reconnect your demo credential.",
    };
  if (!error.response)
    return {
      title: "Cannot reach PayFlow",
      message:
        "Check that the backend is running and the API proxy is configured, then try again.",
    };
  const status = error.response.status;
  const map: Record<number, [string, string]> = {
    400: [
      "Check your request",
      "Some submitted values are invalid. Review the fields and try again.",
    ],
    401: [
      "Connection needs attention",
      "Your API key is missing, expired or revoked. Connect a valid merchant key.",
    ],
    403: ["Access denied", "This credential cannot perform this action."],
    404: [
      "Resource not found",
      "This resource is unavailable to the connected merchant. Check the full ID.",
    ],
    409: [
      "Request conflict",
      "An idempotency or refund conflict occurred. The original request or available balance may differ.",
    ],
    429: [
      "Please wait a moment",
      "Too many payment requests. Please try again shortly.",
    ],
    503: [
      "PayFlow is temporarily unavailable",
      "A required service is unavailable. Try again shortly.",
    ],
    500: [
      "Request could not be completed",
      "PayFlow encountered a problem. Retry the same submission before creating another one.",
    ],
  };
  const [title, message] = map[status] || [
    "Request failed",
    "The request could not be completed. Please try again.",
  ];
  const raw = Number(error.response.headers["retry-after"]);
  return {
    status,
    title,
    message,
    retryAfter: Number.isFinite(raw) && raw > 0 ? raw : undefined,
  };
}

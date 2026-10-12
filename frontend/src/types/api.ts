export type PaymentStatus =
  | "CREATED"
  | "PROCESSING"
  | "SUCCESS"
  | "FAILED"
  | "PENDING_CONFIRMATION"
  | "PARTIALLY_REFUNDED"
  | "REFUNDED";
export type RefundStatus =
  "CREATED" | "PROCESSING" | "SUCCESS" | "FAILED" | "PENDING_CONFIRMATION";
export type PaymentMethod = "UPI" | "CARD" | "NET_BANKING" | "WALLET";
export interface Payment {
  id: string;
  merchantId: string;
  userId: string;
  amount: number;
  currency: string;
  paymentMethod: PaymentMethod;
  status: PaymentStatus;
  createdAt: string;
  updatedAt: string;
}
export interface Refund {
  id: string;
  paymentId: string;
  amount: number;
  currency: string;
  status: RefundStatus;
  merchantRefundReference: string;
  provider: string | null;
  providerRefundId: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  createdAt: string;
  updatedAt: string;
}
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
export interface CreatePayment {
  userId: string;
  amount: string;
  currency: string;
  paymentMethod: PaymentMethod;
}
export interface CreateRefund {
  amount: string;
  merchantRefundReference: string;
}
export interface RecentRefund {
  id: string;
  paymentId: string;
  amount: number;
  currency: string;
  createdAt: string;
}
export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
}

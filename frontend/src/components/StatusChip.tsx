import { Chip, type ChipProps } from "@mui/material";
import {
  CheckCircleOutline,
  Schedule,
  Autorenew,
  ErrorOutline,
  HelpOutline,
  Undo,
  DoneAll,
} from "@mui/icons-material";
import type { PaymentStatus, RefundStatus } from "../types/api";
export const statusLabels: Record<PaymentStatus, string> = {
  CREATED: "Created",
  PROCESSING: "Processing",
  SUCCESS: "Successful",
  FAILED: "Failed",
  PENDING_CONFIRMATION: "Awaiting confirmation",
  PARTIALLY_REFUNDED: "Partially refunded",
  REFUNDED: "Refunded",
};
const color: Record<PaymentStatus, ChipProps["color"]> = {
  CREATED: "default",
  PROCESSING: "info",
  SUCCESS: "success",
  FAILED: "error",
  PENDING_CONFIRMATION: "warning",
  PARTIALLY_REFUNDED: "info",
  REFUNDED: "default",
};
const icons = {
  CREATED: <Schedule />,
  PROCESSING: <Autorenew />,
  SUCCESS: <CheckCircleOutline />,
  FAILED: <ErrorOutline />,
  PENDING_CONFIRMATION: <HelpOutline />,
  PARTIALLY_REFUNDED: <Undo />,
  REFUNDED: <DoneAll />,
};
export function PaymentStatusChip({ status }: { status: PaymentStatus }) {
  return (
    <Chip
      size="small"
      variant="outlined"
      icon={icons[status]}
      label={statusLabels[status] || "Unknown status"}
      color={color[status] || "default"}
      sx={{ fontWeight: 600, height: 29, bgcolor: "background.paper" }}
    />
  );
}
export function RefundStatusChip({ status }: { status: RefundStatus }) {
  return <PaymentStatusChip status={status} />;
}

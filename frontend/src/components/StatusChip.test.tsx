import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import {
  PaymentStatusChip,
  RefundStatusChip,
  statusLabels,
} from "./StatusChip";
import type { PaymentStatus, RefundStatus } from "../types/api";
describe("status presentation", () => {
  for (const status of Object.keys(statusLabels) as PaymentStatus[]) {
    it(`labels ${status} without raw enum formatting`, () => {
      render(<PaymentStatusChip status={status} />);
      expect(screen.getByText(statusLabels[status])).toBeVisible();
      expect(screen.queryByText(status)).not.toBeInTheDocument();
    });
  }
  for (const status of [
    "CREATED",
    "PROCESSING",
    "SUCCESS",
    "FAILED",
    "PENDING_CONFIRMATION",
  ] as RefundStatus[]) {
    it(`supports refund ${status}`, () => {
      render(<RefundStatusChip status={status} />);
      expect(screen.getByText(statusLabels[status])).toBeVisible();
    });
  }
});

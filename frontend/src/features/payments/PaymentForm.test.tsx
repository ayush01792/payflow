import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { expect, it, vi } from "vitest";
import { PaymentForm } from "./PaymentForm";
import { SessionProvider } from "../../hooks/SessionProvider";
import { ToastProvider } from "../../hooks/ToastProvider";
import { createPayment } from "../../api/payflow";
import type { Payment } from "../../types/api";
vi.mock("../../api/payflow", () => ({ createPayment: vi.fn() }));
const payment: Payment = {
  id: "unit-payment",
  merchantId: "unit-merchant",
  userId: "customer",
  amount: 10,
  currency: "INR",
  paymentMethod: "UPI",
  status: "CREATED",
  createdAt: "2026-10-11T00:00:00Z",
  updatedAt: "2026-10-11T00:00:00Z",
};
function mount(onCreated = vi.fn()) {
  const query = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={query}>
      <SessionProvider>
        <ToastProvider>
          <PaymentForm onCreated={onCreated} />
        </ToastProvider>
      </SessionProvider>
    </QueryClientProvider>,
  );
}
it("prevents invalid submission before making HTTP calls", async () => {
  vi.mocked(createPayment).mockReset();
  mount();
  await userEvent.click(screen.getByRole("button", { name: "Submit payment" }));
  expect(screen.getByText("Enter a user ID (1–100 characters).")).toBeVisible();
  expect(screen.getByText(/Enter a positive amount/)).toBeVisible();
  expect(createPayment).not.toHaveBeenCalled();
});
it("retries an unknown HTTP result with the same immutable payload and key", async () => {
  vi.mocked(createPayment)
    .mockReset()
    .mockRejectedValueOnce({ isAxiosError: true })
    .mockResolvedValueOnce(payment);
  const onCreated = vi.fn();
  mount(onCreated);
  await userEvent.type(screen.getByLabelText("User ID"), "customer");
  await userEvent.type(screen.getByLabelText("Amount"), "10");
  await userEvent.click(screen.getByRole("button", { name: "Submit payment" }));
  await screen.findByRole("button", { name: "Retry same payment" });
  expect(screen.getByLabelText("Amount")).toBeDisabled();
  await userEvent.click(
    screen.getByRole("button", { name: "Retry same payment" }),
  );
  await waitFor(() => expect(onCreated).toHaveBeenCalledWith(payment));
  const calls = vi.mocked(createPayment).mock.calls;
  expect(calls).toHaveLength(2);
  expect(calls[1]).toEqual(calls[0]);
  expect(calls[0][0].amount).toBe("10");
  expect(calls[0][1]).toMatch(/^[0-9a-f-]{36}$/);
});
it("keeps retry identity after remounting the page", async () => {
  vi.mocked(createPayment)
    .mockReset()
    .mockRejectedValue({ isAxiosError: true });
  const view = mount();
  await userEvent.type(screen.getByLabelText("User ID"), "customer");
  await userEvent.type(screen.getByLabelText("Amount"), "10");
  await userEvent.click(screen.getByRole("button", { name: "Submit payment" }));
  await screen.findByRole("button", { name: "Retry same payment" });
  const first = vi.mocked(createPayment).mock.calls[0];
  view.unmount();
  mount();
  await userEvent.click(
    screen.getByRole("button", { name: "Retry same payment" }),
  );
  await waitFor(() => expect(createPayment).toHaveBeenCalledTimes(2));
  expect(vi.mocked(createPayment).mock.calls[1]).toEqual(first);
});

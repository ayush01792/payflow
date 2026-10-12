import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { expect, it, vi } from "vitest";
import { ErrorState } from "./ErrorState";
const error = (status: number) => ({
  isAxiosError: true,
  response: {
    status,
    headers: { "retry-after": "7" },
    data: { message: "SECRET stacktrace java.lang.Exception" },
  },
});
it.each([400, 401, 403, 404, 409, 429, 503, 500])(
  "renders safe actionable API error %s",
  (status) => {
    render(
      <MemoryRouter>
        <ErrorState error={error(status)} retry={vi.fn()} />
      </MemoryRouter>,
    );
    expect(screen.getByRole("alert")).toBeVisible();
    expect(
      screen.queryByText(/SECRET|java.lang.Exception/),
    ).not.toBeInTheDocument();
    if ([401, 403].includes(status))
      expect(
        screen.getByRole("link", { name: "Connect API key" }),
      ).toHaveAttribute("href", "/connect");
    else
      expect(screen.getByRole("button", { name: "Try again" })).toBeVisible();
  },
);
it("distinguishes a network error", () => {
  render(<ErrorState error={{ isAxiosError: true }} />);
  expect(screen.getByText("Cannot reach PayFlow")).toBeVisible();
});
it("shows retry-after for admission failure", () => {
  render(<ErrorState error={error(429)} />);
  expect(screen.getByText(/Retry after 7 seconds/)).toBeVisible();
});

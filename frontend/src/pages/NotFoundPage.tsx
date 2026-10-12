import { EmptyState } from "../components/EmptyState";
export function NotFoundPage() {
  return (
    <EmptyState
      title="Page not found"
      message="This page is not part of the merchant workspace."
      action="Back to overview"
      to="/"
    />
  );
}

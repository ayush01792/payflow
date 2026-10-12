import { Navigate, Outlet, Route, Routes, useLocation } from "react-router-dom";
import { DashboardLayout } from "./layouts/DashboardLayout";
import { useSession } from "./hooks/SessionProvider";
import { lazy, Suspense } from "react";
import { LoadingState } from "./components/LoadingState";
const DashboardPage = lazy(() =>
  import("./pages/DashboardPage").then((m) => ({ default: m.DashboardPage })),
);
const PaymentsPage = lazy(() =>
  import("./pages/PaymentsPage").then((m) => ({ default: m.PaymentsPage })),
);
const PaymentDetailPage = lazy(() =>
  import("./pages/PaymentDetailPage").then((m) => ({
    default: m.PaymentDetailPage,
  })),
);
const CreatePaymentPage = lazy(() =>
  import("./pages/CreatePaymentPage").then((m) => ({
    default: m.CreatePaymentPage,
  })),
);
const RefundDetailPage = lazy(() =>
  import("./pages/RefundDetailPage").then((m) => ({
    default: m.RefundDetailPage,
  })),
);
const RefundsPage = lazy(() =>
  import("./pages/RefundsPage").then((m) => ({ default: m.RefundsPage })),
);
const ConnectPage = lazy(() =>
  import("./pages/ConnectPage").then((m) => ({ default: m.ConnectPage })),
);
const SystemPage = lazy(() =>
  import("./pages/SystemPage").then((m) => ({ default: m.SystemPage })),
);
const NotFoundPage = lazy(() =>
  import("./pages/NotFoundPage").then((m) => ({ default: m.NotFoundPage })),
);
function MerchantRoute() {
  const { connected } = useSession();
  const location = useLocation();
  return connected ? (
    <Outlet />
  ) : (
    <Navigate to="/connect" replace state={{ from: location.pathname }} />
  );
}
export default function App() {
  return (
    <Suspense fallback={<LoadingState />}>
      <Routes>
        <Route element={<DashboardLayout />}>
          <Route path="connect" element={<ConnectPage />} />
          <Route path="system" element={<SystemPage />} />
          <Route element={<MerchantRoute />}>
            <Route index element={<DashboardPage />} />
            <Route path="dashboard" element={<Navigate to="/" replace />} />
            <Route path="payments" element={<PaymentsPage />} />
            <Route path="payments/new" element={<CreatePaymentPage />} />
            <Route path="payments/:paymentId" element={<PaymentDetailPage />} />
            <Route path="refunds" element={<RefundsPage />} />
            <Route path="refunds/:refundId" element={<RefundDetailPage />} />
          </Route>
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </Suspense>
  );
}

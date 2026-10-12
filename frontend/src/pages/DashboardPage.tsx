import { Alert, Box, Button, Card, Stack, Typography } from "@mui/material";
import {
  Add,
  ArrowForward,
  Refresh,
  PaymentsOutlined,
  CheckCircleOutline,
  Autorenew,
  ErrorOutline,
  HelpOutline,
} from "@mui/icons-material";
import { Link } from "react-router-dom";
import { useHealth, usePayments } from "../hooks/usePayflow";
import { PageHeading } from "../components/PageHeading";
import { ErrorState } from "../components/ErrorState";
import { LoadingState } from "../components/LoadingState";
import { PaymentTable } from "../features/payments/PaymentTable";
import { lazy, Suspense } from "react";
const StatusBreakdown = lazy(() =>
  import("../features/dashboard/StatusBreakdown").then((m) => ({
    default: m.StatusBreakdown,
  })),
);
import { RecentRefunds } from "../features/refunds/RecentRefunds";
export function DashboardPage() {
  const query = usePayments(0, 20);
  const health = useHealth();
  const items = query.data?.content || [];
  const up = !health.isError && health.data?.status === "UP";
  const stats = [
    {
      label: "Loaded payments",
      value: items.length,
      icon: <PaymentsOutlined />,
      color: "#466756",
    },
    {
      label: "Successful",
      value: items.filter((p) => p.status === "SUCCESS").length,
      icon: <CheckCircleOutline />,
      color: "#2c7653",
    },
    {
      label: "In progress",
      value: items.filter((p) => ["CREATED", "PROCESSING"].includes(p.status))
        .length,
      icon: <Autorenew />,
      color: "#326b91",
    },
    {
      label: "Failed",
      value: items.filter((p) => p.status === "FAILED").length,
      icon: <ErrorOutline />,
      color: "#b74740",
    },
    {
      label: "Awaiting confirmation",
      value: items.filter((p) => p.status === "PENDING_CONFIRMATION").length,
      icon: <HelpOutline />,
      color: "#936414",
    },
  ];
  return (
    <>
      <PageHeading
        title="Your payments, in view."
        subtitle="A clear view of your latest payment activity."
        action={
          <Button
            component={Link}
            to="/payments/new"
            startIcon={<Add />}
            variant="contained"
          >
            Create payment
          </Button>
        }
      />
      <Stack
        direction="row"
        alignItems="center"
        justifyContent="space-between"
        sx={{ mb: 2 }}
      >
        <Typography variant="body2" color="text.secondary">
          Snapshot · latest {items.length} loaded payments
        </Typography>
        <Button
          size="small"
          startIcon={<Refresh />}
          disabled={query.isFetching}
          onClick={() => {
            void query.refetch();
            void health.refetch();
          }}
        >
          Refresh
        </Button>
      </Stack>
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : (
        <>
          <Box
            sx={{
              display: "grid",
              gridTemplateColumns: {
                xs: "minmax(0, 1fr)",
                sm: "repeat(2, 1fr)",
                md: "repeat(3, 1fr)",
                lg: "repeat(5, 1fr)",
              },
              gap: 2,
              mb: 3,
            }}
          >
            {stats.map((s) => (
              <Card key={s.label} sx={{ p: 2.2 }}>
                <Box sx={{ color: s.color, mb: 1.5 }}>{s.icon}</Box>
                <Typography variant="h4">{s.value}</Typography>
                <Typography
                  variant="body2"
                  color="text.secondary"
                  sx={{ mt: 0.8 }}
                >
                  {s.label}
                </Typography>
              </Card>
            ))}
          </Box>
          <Alert
            severity="info"
            icon={false}
            sx={{ mb: 3, bgcolor: "#eef3ef", color: "text.secondary" }}
          >
            Statistics and chart describe this loaded page, not the full
            database. Amounts across currencies are not combined.
          </Alert>
          <Box
            sx={{
              display: "grid",
              gridTemplateColumns: {
                xs: "minmax(0, 1fr)",
                lg: "minmax(0, 2.2fr) minmax(270px, 1fr)",
              },
              gap: 3,
              alignItems: "start",
            }}
          >
            <Stack spacing={3} sx={{ minWidth: 0 }}>
              <Card>
                <Stack
                  direction="row"
                  justifyContent="space-between"
                  alignItems="center"
                  sx={{ p: 3 }}
                >
                  <Box>
                    <Typography variant="h6">Recent payments</Typography>
                    <Typography
                      variant="body2"
                      color="text.secondary"
                      sx={{ mt: 0.5 }}
                    >
                      Latest loaded · newest first
                    </Typography>
                  </Box>
                  <Button
                    component={Link}
                    to="/payments"
                    endIcon={<ArrowForward />}
                    size="small"
                  >
                    View all
                  </Button>
                </Stack>
                <PaymentTable items={items.slice(0, 5)} />
              </Card>
              <RecentRefunds />
            </Stack>
            <Stack spacing={3} sx={{ minWidth: 0 }}>
              <Card sx={{ p: 3 }}>
                <Typography variant="h6">Status breakdown</Typography>
                <Typography
                  color="text.secondary"
                  variant="body2"
                  sx={{ mt: 0.5 }}
                >
                  Current page only
                </Typography>
                {items.length ? (
                  <Suspense fallback={<LoadingState />}>
                    <StatusBreakdown payments={items} />
                  </Suspense>
                ) : (
                  <Typography color="text.secondary" sx={{ py: 4 }}>
                    Create a payment to see a breakdown.
                  </Typography>
                )}
              </Card>
              <Card sx={{ p: 3 }}>
                <Stack direction="row" alignItems="center" spacing={1}>
                  <Box
                    sx={{
                      width: 8,
                      height: 8,
                      borderRadius: "50%",
                      bgcolor: health.isPending
                        ? "grey.400"
                        : up
                          ? "success.main"
                          : "error.main",
                    }}
                  />
                  <Typography variant="h6">PayFlow API</Typography>
                </Stack>
                <Typography
                  color="text.secondary"
                  variant="body2"
                  sx={{ mt: 1 }}
                >
                  {health.isPending
                    ? "Checking connection…"
                    : up
                      ? "Responding to health checks"
                      : "Unavailable from this browser"}
                </Typography>
                <Button
                  component={Link}
                  to="/system"
                  size="small"
                  sx={{ mt: 1.5, px: 0 }}
                >
                  View system status
                </Button>
              </Card>
            </Stack>
          </Box>
        </>
      )}
    </>
  );
}

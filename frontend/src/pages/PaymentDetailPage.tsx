import { useState } from "react";
import { Alert, Box, Button, Card, Stack, Typography } from "@mui/material";
import {
  ArrowBack,
  Refresh,
  Undo,
  CircleOutlined,
  CheckCircleOutline,
} from "@mui/icons-material";
import { Link, useLocation, useParams } from "react-router-dom";
import { usePayment } from "../hooks/usePayflow";
import { PageHeading } from "../components/PageHeading";
import { PaymentStatusChip, statusLabels } from "../components/StatusChip";
import { ErrorState } from "../components/ErrorState";
import { LoadingState } from "../components/LoadingState";
import { DetailField } from "../components/DetailField";
import { date, amount, methodLabels } from "../utils/format";
import { RefundDialog } from "../features/refunds/RefundDialog";
import { RecentRefunds } from "../features/refunds/RecentRefunds";
export function PaymentDetailPage() {
  const { paymentId = "" } = useParams();
  const q = usePayment(paymentId);
  const [refundOpen, setRefundOpen] = useState(false);
  const location = useLocation();
  const p = q.data;
  return (
    <>
      <Button
        component={Link}
        to="/payments"
        startIcon={<ArrowBack />}
        sx={{ mb: 2, px: 0 }}
      >
        Back to payments
      </Button>
      <PageHeading
        title="Payment details"
        subtitle="Track the current result of this payment."
        action={
          <Button
            startIcon={<Refresh />}
            disabled={q.isFetching}
            onClick={() => void q.refetch()}
          >
            Refresh status
          </Button>
        }
      />
      {(location.state as { submitted?: boolean } | null)?.submitted && (
        <Alert severity="success" sx={{ mb: 3 }}>
          Payment submitted successfully. Processing is asynchronous; this page
          checks its status while work is in progress.
        </Alert>
      )}
      {q.isPending ? (
        <LoadingState />
      ) : q.isError && !p ? (
        <ErrorState error={q.error} retry={() => void q.refetch()} />
      ) : (
        p && (
          <>
            {q.isError && (
              <ErrorState error={q.error} retry={() => void q.refetch()} />
            )}
            <Card sx={{ p: { xs: 2.5, sm: 3.5 }, mb: 3 }}>
              <Stack
                direction={{ xs: "column", sm: "row" }}
                justifyContent="space-between"
                alignItems={{ xs: "flex-start", sm: "center" }}
                spacing={2}
              >
                <Box>
                  <Typography variant="body2" color="text.secondary">
                    Payment amount
                  </Typography>
                  <Typography variant="h4" sx={{ my: 1 }}>
                    {amount(p.amount, p.currency)}
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    {p.currency} · {methodLabels[p.paymentMethod]}
                  </Typography>
                </Box>
                <PaymentStatusChip status={p.status} />
              </Stack>
              {p.status === "PENDING_CONFIRMATION" && (
                <Alert severity="warning" sx={{ mt: 3 }}>
                  PayFlow is confirming the final result with the payment
                  provider. An uncertain result is not a failed payment. Status
                  checks slow to every 10 seconds.
                </Alert>
              )}
              {p.status === "FAILED" && (
                <Alert severity="error" sx={{ mt: 3 }}>
                  The payment failed. This API response does not include a
                  failure code or message.
                </Alert>
              )}
              <Box
                sx={{
                  display: "grid",
                  gridTemplateColumns: {
                    xs: "1fr",
                    md: "repeat(2, minmax(0, 1fr))",
                  },
                  gap: 3,
                  mt: 4,
                }}
              >
                <DetailField label="Payment ID" value={p.id} copy />
                <DetailField label="User ID" value={p.userId} />
                <DetailField label="Created" value={date(p.createdAt)} />
                <DetailField label="Last updated" value={date(p.updatedAt)} />
              </Box>
            </Card>
            <Box
              sx={{
                display: "grid",
                gridTemplateColumns: {
                  xs: "1fr",
                  md: "minmax(0, 1.2fr) minmax(0, 1fr)",
                },
                gap: 3,
              }}
            >
              <Stack spacing={3}>
                <Card sx={{ p: 3 }}>
                  <Typography variant="h6">Status record</Typography>
                  <Typography
                    color="text.secondary"
                    variant="body2"
                    sx={{ mt: 1 }}
                  >
                    Only creation and the current state are exposed.
                    Intermediate transition times are unavailable.
                  </Typography>
                  <Stack spacing={2.5} sx={{ mt: 3 }}>
                    <Stack direction="row" spacing={1.5}>
                      <CircleOutlined color="disabled" />
                      <Box>
                        <Typography fontWeight={600}>
                          Payment created
                        </Typography>
                        <Typography color="text.secondary" variant="body2">
                          {date(p.createdAt)}
                        </Typography>
                      </Box>
                    </Stack>
                    <Stack direction="row" spacing={1.5}>
                      <CheckCircleOutline
                        color={p.status === "SUCCESS" ? "success" : "primary"}
                      />
                      <Box>
                        <Typography fontWeight={600}>
                          Current state: {statusLabels[p.status]}
                        </Typography>
                        <Typography color="text.secondary" variant="body2">
                          Last record update: {date(p.updatedAt)}
                        </Typography>
                      </Box>
                    </Stack>
                  </Stack>
                </Card>
                <Card sx={{ p: 3 }}>
                  <Typography variant="h6">Provider information</Typography>
                  <Typography
                    color="text.secondary"
                    variant="body2"
                    sx={{ mt: 1 }}
                  >
                    Provider identity and transaction ID are not exposed by the
                    current payment response. No values are inferred.
                  </Typography>
                </Card>
              </Stack>
              <Stack spacing={3}>
                <Card sx={{ p: 3 }}>
                  <Typography variant="h6">Refund this payment</Typography>
                  <Typography
                    variant="body2"
                    color="text.secondary"
                    sx={{ mt: 1, mb: 2 }}
                  >
                    Refunds are accepted for successful or partially refunded
                    payments. PayFlow validates the remaining balance.
                  </Typography>
                  {["SUCCESS", "PARTIALLY_REFUNDED"].includes(p.status) ? (
                    <Button
                      startIcon={<Undo />}
                      variant="outlined"
                      onClick={() => setRefundOpen(true)}
                    >
                      Submit a refund
                    </Button>
                  ) : (
                    <Typography variant="body2" color="text.secondary">
                      This payment is not currently eligible for a refund.
                    </Typography>
                  )}
                </Card>
                <RecentRefunds paymentId={p.id} />
              </Stack>
            </Box>
            <RefundDialog
              paymentId={p.id}
              currency={p.currency}
              open={refundOpen}
              close={() => setRefundOpen(false)}
            />
          </>
        )
      )}
    </>
  );
}

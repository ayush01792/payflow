import { Alert, Box, Button, Card, Stack, Typography } from "@mui/material";
import { ArrowBack, Refresh } from "@mui/icons-material";
import { Link, useParams } from "react-router-dom";
import { useRefund } from "../hooks/usePayflow";
import { PageHeading } from "../components/PageHeading";
import { LoadingState } from "../components/LoadingState";
import { ErrorState } from "../components/ErrorState";
import { RefundStatusChip } from "../components/StatusChip";
import { DetailField } from "../components/DetailField";
import { amount, date, providerLabel } from "../utils/format";
export function RefundDetailPage() {
  const { refundId = "" } = useParams();
  const q = useRefund(refundId);
  const r = q.data;
  return (
    <>
      <Button
        component={Link}
        to="/refunds"
        startIcon={<ArrowBack />}
        sx={{ mb: 2, px: 0 }}
      >
        Recent session refunds
      </Button>
      <PageHeading
        title="Refund details"
        subtitle="The authoritative refund result, retrieved from PayFlow."
        action={
          <Button
            startIcon={<Refresh />}
            disabled={q.isFetching}
            onClick={() => void q.refetch()}
          >
            Refresh refund
          </Button>
        }
      />
      {q.isPending ? (
        <LoadingState />
      ) : q.isError && !r ? (
        <ErrorState error={q.error} retry={() => void q.refetch()} />
      ) : (
        r && (
          <>
            {q.isError && (
              <ErrorState error={q.error} retry={() => void q.refetch()} />
            )}
            <Card sx={{ p: { xs: 2.5, sm: 4 } }}>
              <Stack
                direction={{ xs: "column", sm: "row" }}
                spacing={2}
                justifyContent="space-between"
                alignItems={{ xs: "flex-start", sm: "center" }}
              >
                <Box>
                  <Typography color="text.secondary" variant="body2">
                    Refund amount
                  </Typography>
                  <Typography variant="h4" sx={{ my: 1 }}>
                    {amount(r.amount, r.currency)}
                  </Typography>
                </Box>
                <RefundStatusChip status={r.status} />
              </Stack>
              {r.status === "PENDING_CONFIRMATION" && (
                <Alert severity="warning" sx={{ mt: 3 }}>
                  PayFlow is confirming the refund with the original provider.
                  The amount remains reserved while the outcome is uncertain.
                </Alert>
              )}
              {r.status === "FAILED" && (
                <Alert severity="error" sx={{ mt: 3 }}>
                  The refund failed.
                  {r.failureCode && ` Code: ${r.failureCode}.`}
                  {r.failureMessage &&
                    !/exception|stacktrace|\bat \w+\./i.test(
                      r.failureMessage,
                    ) &&
                    ` ${r.failureMessage.slice(0, 300)}`}
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
                <DetailField label="Refund ID" value={r.id} copy />
                <DetailField label="Payment ID" value={r.paymentId} copy />
                <DetailField
                  label="Merchant refund reference"
                  value={r.merchantRefundReference}
                />
                <DetailField
                  label="Provider"
                  value={providerLabel(r.provider)}
                />
                <DetailField
                  label="Provider refund ID"
                  value={r.providerRefundId || "Not yet available"}
                  copy={Boolean(r.providerRefundId)}
                />
                <DetailField label="Currency" value={r.currency} />
                <DetailField label="Created" value={date(r.createdAt)} />
                <DetailField label="Last updated" value={date(r.updatedAt)} />
              </Box>
              <Button
                component={Link}
                to={`/payments/${r.paymentId}`}
                sx={{ mt: 3 }}
              >
                Open original payment
              </Button>
            </Card>
          </>
        )
      )}
    </>
  );
}

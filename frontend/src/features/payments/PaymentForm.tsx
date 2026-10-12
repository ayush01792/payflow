import { useState, type FormEvent } from "react";
import {
  Alert,
  Box,
  Button,
  Card,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import type { CreatePayment, Payment } from "../../types/api";
import { methodLabels } from "../../utils/format";
import { validatePayment } from "../../utils/validation";
import { useCreatePayment } from "../../hooks/usePayflow";
import { useLogicalSubmission } from "../../hooks/useLogicalSubmission";
import { ErrorState } from "../../components/ErrorState";
import { useToast } from "../../hooks/ToastProvider";
const initial: CreatePayment = {
  userId: "",
  amount: "",
  currency: "INR",
  paymentMethod: "UPI",
};
export function PaymentForm({
  onCreated,
}: {
  onCreated: (payment: Payment) => void;
}) {
  const logical = useLogicalSubmission<CreatePayment>("payment");
  const [body, setBody] = useState<CreatePayment>(
    logical.attempt?.body || initial,
  );
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [confirm, setConfirm] = useState(false);
  const mutation = useCreatePayment();
  const toast = useToast();
  async function submit(e: FormEvent) {
    e.preventDefault();
    const validated = validatePayment(body);
    setErrors(validated);
    if (Object.keys(validated).length) return;
    const attempt = logical.begin(body);
    try {
      const payment = await mutation.mutateAsync(attempt);
      logical.clear();
      toast("Payment submitted. Processing continues asynchronously.");
      onCreated(payment);
    } catch {
      toast(
        "Payment request could not be confirmed. Retry the same submission.",
        "error",
      );
    }
  }
  const frozen = Boolean(logical.attempt);
  return (
    <Card sx={{ p: { xs: 2.5, sm: 4 }, maxWidth: 720 }}>
      <Typography variant="h6" sx={{ mb: 1 }}>
        Payment information
      </Typography>
      <Typography color="text.secondary" variant="body2" sx={{ mb: 3 }}>
        Create a payment intent. No card numbers or real payment credentials are
        required.
      </Typography>
      <Box component="form" onSubmit={submit} noValidate>
        <Stack spacing={3}>
          <TextField
            label="User ID"
            value={body.userId}
            disabled={frozen || mutation.isPending}
            onChange={(e) => setBody({ ...body, userId: e.target.value })}
            error={Boolean(errors.userId)}
            helperText={
              errors.userId || "Your reference for the customer or user."
            }
            slotProps={{ htmlInput: { maxLength: 100 } }}
          />
          <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
            <TextField
              label="Amount"
              value={body.amount}
              disabled={frozen || mutation.isPending}
              onChange={(e) => setBody({ ...body, amount: e.target.value })}
              error={Boolean(errors.amount)}
              helperText={errors.amount || "Up to 4 decimal places."}
              slotProps={{ htmlInput: { inputMode: "decimal" } }}
            />
            <TextField
              label="Currency"
              value={body.currency}
              disabled={frozen || mutation.isPending}
              onChange={(e) =>
                setBody({ ...body, currency: e.target.value.toUpperCase() })
              }
              error={Boolean(errors.currency)}
              helperText={errors.currency || "ISO code, e.g. INR or USD."}
              slotProps={{ htmlInput: { maxLength: 3 } }}
              sx={{ maxWidth: { sm: 200 } }}
            />
          </Stack>
          <TextField
            select
            label="Payment method"
            value={body.paymentMethod}
            disabled={frozen || mutation.isPending}
            onChange={(e) =>
              setBody({
                ...body,
                paymentMethod: e.target.value as CreatePayment["paymentMethod"],
              })
            }
          >
            {Object.entries(methodLabels).map(([value, label]) => (
              <MenuItem key={value} value={value}>
                {label}
              </MenuItem>
            ))}
          </TextField>
          {logical.attempt && !mutation.isPending && (
            <Alert severity="info">
              This submission is saved in this tab. Retry uses the same request
              and idempotency key; its earlier result may still be processing.
            </Alert>
          )}
          {mutation.isError && <ErrorState error={mutation.error} />}
          <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
            <Button
              type="submit"
              variant="contained"
              disabled={mutation.isPending}
            >
              {mutation.isPending
                ? "Submitting…"
                : logical.attempt
                  ? "Retry same payment"
                  : "Submit payment"}
            </Button>
            {logical.attempt && (
              <Button
                disabled={mutation.isPending}
                onClick={() => setConfirm(true)}
              >
                Start a different payment
              </Button>
            )}
          </Stack>
        </Stack>
      </Box>
      <Dialog open={confirm} onClose={() => setConfirm(false)}>
        <DialogTitle>Start a separate payment?</DialogTitle>
        <DialogContent>
          An earlier request may have been accepted. Retrying with the same key
          is safest. A new submission creates an independent payment and could
          duplicate your intended business action.
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirm(false)}>
            Keep existing submission
          </Button>
          <Button
            onClick={() => {
              logical.clear();
              mutation.reset();
              setBody(initial);
              setErrors({});
              setConfirm(false);
            }}
          >
            Start separate payment
          </Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}

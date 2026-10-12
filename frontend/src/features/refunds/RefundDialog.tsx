import { useState, type FormEvent } from "react";
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
} from "@mui/material";
import { useNavigate } from "react-router-dom";
import type { CreateRefund } from "../../types/api";
import { validateRefund } from "../../utils/validation";
import { useCreateRefund } from "../../hooks/usePayflow";
import { useLogicalSubmission } from "../../hooks/useLogicalSubmission";
import { ErrorState } from "../../components/ErrorState";
import { useToast } from "../../hooks/ToastProvider";
export function RefundDialog({
  paymentId,
  currency,
  open,
  close,
}: {
  paymentId: string;
  currency: string;
  open: boolean;
  close: () => void;
}) {
  const logical = useLogicalSubmission<CreateRefund>("refund." + paymentId);
  const [body, setBody] = useState<CreateRefund>(
    logical.attempt?.body || { amount: "", merchantRefundReference: "" },
  );
  const [errors, setErrors] = useState<Record<string, string>>({});
  const mutation = useCreateRefund(paymentId);
  const [restart, setRestart] = useState(false);
  const navigate = useNavigate();
  const toast = useToast();
  async function submit(e: FormEvent) {
    e.preventDefault();
    const errors = validateRefund(body);
    setErrors(errors);
    if (Object.keys(errors).length) return;
    const attempt = logical.begin(body);
    try {
      const refund = await mutation.mutateAsync(attempt);
      logical.clear();
      toast("Refund submitted. Follow its status for completion.");
      close();
      navigate(`/refunds/${refund.id}`);
    } catch {
      toast(
        "Refund request could not be confirmed. Retry with the same key.",
        "error",
      );
    }
  }
  return (
    <>
      <Dialog
        open={open}
        onClose={mutation.isPending ? undefined : close}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>Submit a refund</DialogTitle>
        <DialogContent>
          <Stack
            component="form"
            id="refund-form"
            onSubmit={submit}
            noValidate
            spacing={2.5}
            sx={{ pt: 1 }}
          >
            <Alert severity="info">
              PayFlow validates the available balance. The payment API does not
              expose a complete refund ledger, so a maximum is not estimated
              here.
            </Alert>
            <TextField
              label={`Refund amount (${currency})`}
              value={body.amount}
              disabled={Boolean(logical.attempt) || mutation.isPending}
              onChange={(e) => setBody({ ...body, amount: e.target.value })}
              error={Boolean(errors.amount)}
              helperText={
                errors.amount || "Positive amount, up to 4 decimal places."
              }
              slotProps={{ htmlInput: { inputMode: "decimal" } }}
            />
            <TextField
              label="Merchant refund reference"
              value={body.merchantRefundReference}
              disabled={Boolean(logical.attempt) || mutation.isPending}
              onChange={(e) =>
                setBody({ ...body, merchantRefundReference: e.target.value })
              }
              error={Boolean(errors.merchantRefundReference)}
              helperText={
                errors.merchantRefundReference ||
                "A unique reference for this payment."
              }
              slotProps={{ htmlInput: { maxLength: 100 } }}
            />
            {logical.attempt && (
              <Alert severity="info">
                Retrying preserves the original payload and key. Closing this
                dialog keeps the submission in this tab.
              </Alert>
            )}
            {mutation.isError && <ErrorState error={mutation.error} />}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 3 }}>
          <Button onClick={close} disabled={mutation.isPending}>
            Close
          </Button>
          {logical.attempt && (
            <Button
              disabled={mutation.isPending}
              onClick={() => setRestart(true)}
            >
              Different refund
            </Button>
          )}
          <Button
            form="refund-form"
            type="submit"
            variant="contained"
            disabled={mutation.isPending}
          >
            {mutation.isPending
              ? "Submitting…"
              : logical.attempt
                ? "Retry same refund"
                : "Submit refund"}
          </Button>
        </DialogActions>
      </Dialog>
      <Dialog open={restart} onClose={() => setRestart(false)}>
        <DialogTitle>Start a separate refund?</DialogTitle>
        <DialogContent>
          The earlier request may have been accepted. Retry with its existing
          key to recover that result. A new refund is an independent action; use
          a new reference and amount only when you intend another refund.
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRestart(false)}>
            Keep existing request
          </Button>
          <Button
            onClick={() => {
              logical.clear();
              mutation.reset();
              setBody({ amount: "", merchantRefundReference: "" });
              setErrors({});
              setRestart(false);
            }}
          >
            Start separate refund
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

import { Card, Box, Stack, Typography, Link as MuiLink } from "@mui/material";
import { Link } from "react-router-dom";
import { useSession } from "../../hooks/SessionProvider";
import { amount, date, shortId } from "../../utils/format";
import { EmptyState } from "../../components/EmptyState";
export function RecentRefunds({ paymentId }: { paymentId?: string }) {
  const { refunds } = useSession();
  const items = refunds.filter((r) => !paymentId || r.paymentId === paymentId);
  return (
    <Card>
      <Box sx={{ px: 3, pt: 2.5, pb: 1 }}>
        <Typography variant="h6">Recent refund submissions</Typography>
        <Typography color="text.secondary" variant="body2" sx={{ mt: 0.6 }}>
          This browser session only · not a complete refund history.
        </Typography>
      </Box>
      {items.length ? (
        <Stack
          divider={
            <Box sx={{ borderTop: "1px solid", borderColor: "divider" }} />
          }
          sx={{ px: 3, pb: 1 }}
        >
          {items.slice(0, 8).map((r) => (
            <Stack
              key={r.id}
              direction="row"
              justifyContent="space-between"
              alignItems="center"
              spacing={1}
              sx={{ py: 1.7 }}
            >
              <Box sx={{ minWidth: 0 }}>
                <MuiLink
                  component={Link}
                  to={`/refunds/${r.id}`}
                  sx={{ fontFamily: "monospace", fontWeight: 600 }}
                >
                  {shortId(r.id)}
                </MuiLink>
                <Typography
                  variant="caption"
                  display="block"
                  color="text.secondary"
                >
                  {date(r.createdAt)}
                </Typography>
              </Box>
              <Typography variant="body2" fontWeight={600}>
                {amount(r.amount, r.currency)}
              </Typography>
            </Stack>
          ))}
        </Stack>
      ) : (
        <EmptyState
          title="No refunds in this session"
          message="Submit a refund from an eligible payment to see it here."
        />
      )}
    </Card>
  );
}

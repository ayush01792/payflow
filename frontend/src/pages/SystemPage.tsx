import {
  Alert,
  Box,
  Button,
  Card,
  Chip,
  Stack,
  Typography,
} from "@mui/material";
import { Refresh, DnsOutlined } from "@mui/icons-material";
import { useHealth } from "../hooks/usePayflow";
import { PageHeading } from "../components/PageHeading";
import { LoadingState } from "../components/LoadingState";
import { ErrorState } from "../components/ErrorState";
import { date } from "../utils/format";
export function SystemPage() {
  const q = useHealth();
  const up = !q.isError && q.data?.status === "UP";
  return (
    <>
      <PageHeading
        title="System status"
        subtitle="Public API availability from this browser."
        action={
          <Button
            startIcon={<Refresh />}
            disabled={q.isFetching}
            onClick={() => void q.refetch()}
          >
            Refresh health
          </Button>
        }
      />
      {q.isPending ? (
        <LoadingState />
      ) : (
        <Card sx={{ p: { xs: 3, sm: 4 }, maxWidth: 800 }}>
          <Stack direction="row" alignItems="center" spacing={2}>
            <Box
              sx={{
                p: 1.5,
                bgcolor: "primary.light",
                color: "primary.main",
                borderRadius: 3,
              }}
            >
              <DnsOutlined />
            </Box>
            <Box sx={{ flex: 1 }}>
              <Typography variant="h6">PayFlow API</Typography>
              <Typography variant="body2" color="text.secondary">
                Public health endpoint
              </Typography>
            </Box>
            <Chip
              label={up ? "UP" : "DOWN"}
              color={up ? "success" : "error"}
              variant="outlined"
            />
          </Stack>
          {q.isError ? (
            <ErrorState error={q.error} retry={() => void q.refetch()} />
          ) : (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 3 }}>
              Last response received:{" "}
              {date(new Date(q.dataUpdatedAt).toISOString())}
            </Typography>
          )}
          <Alert severity="info" sx={{ mt: 3 }}>
            This checks API responsiveness, not the health of every dependency
            or provider. Internal management endpoints are not accessed by the
            dashboard.
          </Alert>
        </Card>
      )}
    </>
  );
}

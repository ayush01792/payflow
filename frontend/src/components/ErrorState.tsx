import { Alert, AlertTitle, Button, Stack } from "@mui/material";
import { Link } from "react-router-dom";
import { friendlyError } from "../api/client";
export function ErrorState({
  error,
  retry,
}: {
  error: unknown;
  retry?: () => void;
}) {
  const e = friendlyError(error);
  return (
    <Alert severity={e.status === 404 ? "info" : "error"} sx={{ my: 2 }}>
      <AlertTitle>{e.title}</AlertTitle>
      {e.message}
      {e.retryAfter ? ` Retry after ${Math.ceil(e.retryAfter)} seconds.` : ""}
      <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
        {e.status === 401 || e.status === 403 ? (
          <Button component={Link} to="/connect" color="inherit" size="small">
            Connect API key
          </Button>
        ) : (
          retry && (
            <Button color="inherit" size="small" onClick={retry}>
              Try again
            </Button>
          )
        )}
      </Stack>
    </Alert>
  );
}

import { useState, type FormEvent } from "react";
import {
  Alert,
  Box,
  Button,
  Card,
  IconButton,
  InputAdornment,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { KeyOutlined, Visibility, VisibilityOff } from "@mui/icons-material";
import { useLocation, useNavigate } from "react-router-dom";
import { verifyKey } from "../api/payflow";
import { useSession } from "../hooks/SessionProvider";
import { useToast } from "../hooks/ToastProvider";
import { ErrorState } from "../components/ErrorState";
export function ConnectPage() {
  const [key, setKey] = useState("");
  const [visible, setVisible] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [fieldError, setFieldError] = useState("");
  const [pending, setPending] = useState(false);
  const session = useSession();
  const toast = useToast();
  const navigate = useNavigate();
  const location = useLocation();
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!key.trim()) {
      setFieldError("Enter your PayFlow API key.");
      return;
    }
    setPending(true);
    setError(null);
    try {
      await verifyKey(key.trim());
      await session.connect(key.trim());
      setKey("");
      toast("Merchant API key connected for this tab.");
      const next = (location.state as { from?: string } | null)?.from;
      navigate(
        next?.startsWith("/") && !next.startsWith("//") && next !== "/connect"
          ? next
          : "/",
        { replace: true },
      );
    } catch (e) {
      setError(e);
    } finally {
      setPending(false);
    }
  }
  return (
    <Box sx={{ maxWidth: 640, mx: "auto", py: { xs: 2, md: 5 } }}>
      <Card sx={{ p: { xs: 3, sm: 5 } }}>
        <Box
          sx={{
            width: 52,
            height: 52,
            borderRadius: 3,
            bgcolor: "primary.light",
            color: "primary.main",
            display: "grid",
            placeItems: "center",
            mb: 3,
          }}
        >
          <KeyOutlined />
        </Box>
        <Typography variant="h4" component="h1">
          Connect your workspace
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 1.5, mb: 3 }}>
          Use a local demo merchant key to explore your payments and refunds.
        </Typography>
        <Alert severity="warning" sx={{ mb: 3 }}>
          Local portfolio demonstration only. Merchant API keys are intended for
          server-to-server use. This tab stores the key in session storage until
          you disconnect or close the tab.
        </Alert>
        <Stack component="form" onSubmit={submit} noValidate spacing={2.5}>
          <TextField
            label="PayFlow API Key"
            type={visible ? "text" : "password"}
            value={key}
            disabled={pending}
            onChange={(e) => {
              setKey(e.target.value);
              setFieldError("");
            }}
            error={Boolean(fieldError)}
            helperText={
              fieldError ||
              "Use the owner-only key file created by merchant bootstrap."
            }
            autoComplete="off"
            slotProps={{
              input: {
                endAdornment: (
                  <InputAdornment position="end">
                    <IconButton
                      aria-label={visible ? "Hide API key" : "Show API key"}
                      onClick={() => setVisible(!visible)}
                      edge="end"
                    >
                      {visible ? <VisibilityOff /> : <Visibility />}
                    </IconButton>
                  </InputAdornment>
                ),
              },
            }}
          />
          {error !== null && <ErrorState error={error} />}
          <Button type="submit" variant="contained" disabled={pending}>
            {pending ? "Checking credential…" : "Connect merchant"}
          </Button>
        </Stack>
        <Typography
          variant="caption"
          display="block"
          color="text.secondary"
          sx={{ mt: 3 }}
        >
          Switching credentials clears cached merchant data, saved submission
          retries and recent refund history. No key is stored in URLs, source
          code or local storage.
        </Typography>
      </Card>
    </Box>
  );
}

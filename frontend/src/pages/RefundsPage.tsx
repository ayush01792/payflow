import { useState } from "react";
import {
  Alert,
  Button,
  Card,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useNavigate } from "react-router-dom";
import { PageHeading } from "../components/PageHeading";
import { RecentRefunds } from "../features/refunds/RecentRefunds";
export function RefundsPage() {
  const [id, setId] = useState("");
  const [error, setError] = useState("");
  const navigate = useNavigate();
  return (
    <>
      <PageHeading
        title="Refunds"
        subtitle="Open a refund by ID, or revisit submissions from this browser session."
      />
      <Alert severity="info" sx={{ mb: 3 }}>
        PayFlow has no global refund-list endpoint. Recent submissions below are
        local session history, not all merchant refunds. Open a refund to
        retrieve its current state.
      </Alert>
      <Card sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" sx={{ mb: 2 }}>
          Find a refund
        </Typography>
        <Stack
          component="form"
          direction={{ xs: "column", sm: "row" }}
          spacing={2}
          onSubmit={(e) => {
            e.preventDefault();
            if (
              !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
                id.trim(),
              )
            ) {
              setError("Enter a full refund UUID.");
              return;
            }
            navigate(`/refunds/${id.trim()}`);
          }}
        >
          <TextField
            label="Refund ID"
            value={id}
            onChange={(e) => {
              setId(e.target.value);
              setError("");
            }}
            error={Boolean(error)}
            helperText={error}
          />
          <Button
            type="submit"
            variant="outlined"
            sx={{ minWidth: 130, alignSelf: { sm: "flex-start" }, height: 56 }}
          >
            Open refund
          </Button>
        </Stack>
      </Card>
      <RecentRefunds />
    </>
  );
}

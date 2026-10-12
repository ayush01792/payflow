import { Box, Typography, Button } from "@mui/material";
import { InboxOutlined } from "@mui/icons-material";
import { Link } from "react-router-dom";
export function EmptyState({
  title = "No payments yet",
  message = "Your payment activity will appear here.",
  action,
  to,
}: {
  title?: string;
  message?: string;
  action?: string;
  to?: string;
}) {
  return (
    <Box sx={{ py: 6, px: 3, textAlign: "center" }}>
      <InboxOutlined sx={{ fontSize: 40, color: "text.secondary", mb: 1 }} />
      <Typography variant="h6">{title}</Typography>
      <Typography color="text.secondary" sx={{ mt: 1, mb: 2 }}>
        {message}
      </Typography>
      {action && to && (
        <Button component={Link} to={to} variant="contained">
          {action}
        </Button>
      )}
    </Box>
  );
}

import { Box, Stack, Typography } from "@mui/material";
import { CopyButton } from "./CopyButton";
export function DetailField({
  label,
  value,
  copy = false,
}: {
  label: string;
  value: string;
  copy?: boolean;
}) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 0.8 }}>
        {label}
      </Typography>
      <Stack direction="row" alignItems="flex-start" spacing={0.5}>
        <Typography
          sx={{
            overflowWrap: "anywhere",
            fontFamily: copy ? "monospace" : "inherit",
            fontSize: copy ? ".85rem" : ".95rem",
            fontWeight: 500,
          }}
        >
          {value}
        </Typography>
        {copy && <CopyButton value={value} label={label.toLowerCase()} />}
      </Stack>
    </Box>
  );
}

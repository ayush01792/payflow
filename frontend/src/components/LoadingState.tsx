import { Card, Skeleton, Stack } from "@mui/material";
export function LoadingState() {
  return (
    <Stack spacing={2} aria-label="Loading data" aria-busy="true">
      <Skeleton variant="rounded" height={90} />
      <Card sx={{ p: 3 }}>
        {[1, 2, 3].map((x) => (
          <Skeleton key={x} height={55} />
        ))}
      </Card>
    </Stack>
  );
}

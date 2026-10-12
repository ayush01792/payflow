import { Box, Stack, Typography } from "@mui/material";
import { Cell, Pie, PieChart, ResponsiveContainer } from "recharts";
import type { Payment } from "../../types/api";
import { statusLabels } from "../../components/StatusChip";
const colors: Record<string, string> = {
  SUCCESS: "#2d7255",
  PROCESSING: "#5d8aa3",
  CREATED: "#b0b8ad",
  FAILED: "#bc6055",
  PENDING_CONFIRMATION: "#c5a15b",
  PARTIALLY_REFUNDED: "#8382b0",
  REFUNDED: "#697c74",
};
export function StatusBreakdown({ payments }: { payments: Payment[] }) {
  const data = Object.entries(colors)
    .map(([status, fill]) => ({
      name: statusLabels[status as keyof typeof statusLabels],
      count: payments.filter((p) => p.status === status).length,
      fill,
    }))
    .filter((x) => x.count);
  return (
    <>
      <Box sx={{ height: 210, position: "relative" }} aria-hidden="true">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={data}
              dataKey="count"
              innerRadius={68}
              outerRadius={88}
              paddingAngle={3}
              stroke="none"
              isAnimationActive={false}
            >
              {data.map((d) => (
                <Cell key={d.name} fill={d.fill} />
              ))}
            </Pie>
          </PieChart>
        </ResponsiveContainer>
        <Box
          sx={{
            position: "absolute",
            inset: 0,
            display: "grid",
            placeContent: "center",
            textAlign: "center",
            pointerEvents: "none",
          }}
        >
          <Typography variant="h4">{payments.length}</Typography>
          <Typography variant="caption" color="text.secondary">
            loaded
          </Typography>
        </Box>
      </Box>
      <Stack spacing={1} aria-label="Current page status breakdown">
        {data.map((d) => (
          <Stack key={d.name} direction="row" alignItems="center" spacing={1}>
            <Box
              sx={{ width: 9, height: 9, borderRadius: "50%", bgcolor: d.fill }}
            />
            <Typography variant="body2" sx={{ flex: 1 }}>
              {d.name}
            </Typography>
            <Typography variant="body2" fontWeight={600}>
              {d.count}
            </Typography>
          </Stack>
        ))}
      </Stack>
    </>
  );
}

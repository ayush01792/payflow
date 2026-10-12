import {
  Box,
  Link as MuiLink,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from "@mui/material";
import { Link, useNavigate } from "react-router-dom";
import type { Payment } from "../../types/api";
import { amount, date, methodLabels, shortId } from "../../utils/format";
import { CopyButton } from "../../components/CopyButton";
import { PaymentStatusChip } from "../../components/StatusChip";
import { EmptyState } from "../../components/EmptyState";
export function PaymentTable({ items }: { items: Payment[] }) {
  const navigate = useNavigate();
  if (!items.length)
    return <EmptyState action="Create your first payment" to="/payments/new" />;
  return (
    <TableContainer sx={{ width: "100%", overflowX: "auto" }}>
      <Table aria-label="Payments" sx={{ minWidth: 760 }}>
        <TableHead>
          <TableRow>
            {[
              "Payment ID",
              "User",
              "Amount",
              "Method",
              "Status",
              "Created",
            ].map((h) => (
              <TableCell key={h}>{h}</TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {items.map((p) => (
            <TableRow
              key={p.id}
              hover
              sx={{ cursor: "pointer" }}
              onClick={() => navigate(`/payments/${p.id}`)}
            >
              <TableCell>
                <Stack direction="row" alignItems="center" spacing={0.5}>
                  <Tooltip title={p.id}>
                    <MuiLink
                      component={Link}
                      onClick={(e) => e.stopPropagation()}
                      to={`/payments/${p.id}`}
                      underline="hover"
                      sx={{ fontFamily: "monospace", fontWeight: 600 }}
                    >
                      {shortId(p.id)}
                    </MuiLink>
                  </Tooltip>
                  <CopyButton value={p.id} label="payment ID" />
                </Stack>
              </TableCell>
              <TableCell>
                <Typography
                  variant="body2"
                  sx={{
                    maxWidth: 140,
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                  }}
                  title={p.userId}
                >
                  {p.userId}
                </Typography>
              </TableCell>
              <TableCell sx={{ whiteSpace: "nowrap", fontWeight: 600 }}>
                <Box>{amount(p.amount, p.currency)}</Box>
                <Typography variant="caption" color="text.secondary">
                  {p.currency}
                </Typography>
              </TableCell>
              <TableCell>{methodLabels[p.paymentMethod]}</TableCell>
              <TableCell>
                <PaymentStatusChip status={p.status} />
              </TableCell>
              <TableCell
                sx={{
                  whiteSpace: "nowrap",
                  color: "text.secondary",
                  fontSize: ".8rem",
                }}
              >
                {date(p.createdAt)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

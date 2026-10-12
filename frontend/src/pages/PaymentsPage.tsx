import { useState } from "react";
import {
  Button,
  Card,
  MenuItem,
  Stack,
  TablePagination,
  TextField,
  LinearProgress,
} from "@mui/material";
import { Add, Refresh } from "@mui/icons-material";
import { Link } from "react-router-dom";
import { usePayments } from "../hooks/usePayflow";
import { PaymentTable } from "../features/payments/PaymentTable";
import { PageHeading } from "../components/PageHeading";
import { ErrorState } from "../components/ErrorState";
import { LoadingState } from "../components/LoadingState";
export function PaymentsPage() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [sort, setSort] = useState("createdAt,desc");
  const q = usePayments(page, size, sort);
  return (
    <>
      <PageHeading
        title="Payments"
        subtitle="Browse your merchant’s payments. Pages and sorting come from PayFlow."
        action={
          <Button
            component={Link}
            to="/payments/new"
            startIcon={<Add />}
            variant="contained"
          >
            Create payment
          </Button>
        }
      />
      <Stack
        direction={{ xs: "column", sm: "row" }}
        spacing={2}
        justifyContent="space-between"
        sx={{ mb: 2 }}
      >
        <TextField
          select
          label="Sort payments"
          value={sort}
          onChange={(e) => {
            setSort(e.target.value);
            setPage(0);
          }}
          size="small"
          sx={{ maxWidth: { sm: 270 } }}
        >
          {[
            ["createdAt,desc", "Newest first"],
            ["createdAt,asc", "Oldest first"],
            ["amount,desc", "Amount: high to low"],
            ["amount,asc", "Amount: low to high"],
          ].map(([value, label]) => (
            <MenuItem key={value} value={value}>
              {label}
            </MenuItem>
          ))}
        </TextField>
        <Button
          startIcon={<Refresh />}
          disabled={q.isFetching}
          onClick={() => void q.refetch()}
        >
          Refresh payments
        </Button>
      </Stack>
      {q.isPending ? (
        <LoadingState />
      ) : q.isError ? (
        <ErrorState error={q.error} retry={() => void q.refetch()} />
      ) : (
        <Card>
          {q.isFetching && <LinearProgress aria-label="Refreshing payments" />}
          <PaymentTable items={q.data.content} />
          <TablePagination
            component="div"
            count={q.data.totalElements}
            page={page}
            rowsPerPage={size}
            rowsPerPageOptions={[10, 20, 50, 100]}
            onPageChange={(_, next) => setPage(next)}
            onRowsPerPageChange={(e) => {
              setSize(Number(e.target.value));
              setPage(0);
            }}
            slotProps={{
              actions: {
                nextButton: {
                  disabled:
                    q.isPlaceholderData ||
                    (page + 1) * size >= q.data.totalElements,
                },
                previousButton: { disabled: page === 0 || q.isPlaceholderData },
              },
            }}
            sx={{
              "& .MuiTablePagination-toolbar": {
                flexWrap: "wrap",
                justifyContent: "flex-end",
              },
              "& .MuiTablePagination-spacer": {
                display: { xs: "none", sm: "block" },
              },
            }}
          />
        </Card>
      )}
    </>
  );
}

import { createContext, useContext, useState, type ReactNode } from "react";
import { Alert, Snackbar, type AlertColor } from "@mui/material";
const Context = createContext<(message: string, severity?: AlertColor) => void>(
  () => {},
);
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<{
    message: string;
    severity: AlertColor;
  } | null>(null);
  return (
    <Context.Provider
      value={(message, severity = "success") => setToast({ message, severity })}
    >
      {children}
      <Snackbar
        open={Boolean(toast)}
        autoHideDuration={5000}
        onClose={() => setToast(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "center" }}
      >
        <Alert
          severity={toast?.severity || "success"}
          onClose={() => setToast(null)}
          variant="filled"
        >
          {toast?.message}
        </Alert>
      </Snackbar>
    </Context.Provider>
  );
}
export const useToast = () => useContext(Context);

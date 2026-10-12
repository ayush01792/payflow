import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider, CssBaseline } from "@mui/material";
import { SessionProvider } from "./hooks/SessionProvider";
import { ToastProvider } from "./hooks/ToastProvider";
import { theme } from "./theme/theme";
import { friendlyError } from "./api/client";
import App from "./App";
const query = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10000,
      gcTime: 300000,
      refetchOnWindowFocus: false,
      retry: (count, error) => {
        const status = friendlyError(error).status;
        return count < 1 && (!status || status >= 500);
      },
    },
    mutations: { retry: false },
  },
});
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <QueryClientProvider client={query}>
        <SessionProvider>
          <ToastProvider>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </ToastProvider>
        </SessionProvider>
      </QueryClientProvider>
    </ThemeProvider>
  </StrictMode>,
);

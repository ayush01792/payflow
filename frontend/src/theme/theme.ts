import { createTheme } from "@mui/material/styles";
export const theme = createTheme({
  palette: {
    primary: { main: "#245d50", dark: "#173b36", light: "#e8f1ed" },
    secondary: { main: "#766b43" },
    background: { default: "#f6f7f5", paper: "#ffffff" },
    text: { primary: "#20352f", secondary: "#64736c" },
    success: { main: "#297653" },
    warning: { main: "#936414" },
    error: { main: "#b74740" },
    info: { main: "#326b91" },
  },
  shape: { borderRadius: 14 },
  typography: {
    fontFamily: '"Inter", "Segoe UI", system-ui, sans-serif',
    h4: { fontSize: "1.9rem", fontWeight: 700, letterSpacing: "-0.04em" },
    h5: { fontWeight: 650, letterSpacing: "-0.025em" },
    h6: { fontWeight: 650, fontSize: "1.08rem" },
    button: { textTransform: "none", fontWeight: 650 },
  },
  components: {
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: { borderRadius: 9, minHeight: 40, paddingInline: 18 },
      },
    },
    MuiPaper: { styleOverrides: { root: { backgroundImage: "none" } } },
    MuiCard: {
      defaultProps: { variant: "outlined" },
      styleOverrides: {
        root: {
          borderColor: "#e4e8e1",
          minWidth: 0,
          boxShadow: "0 3px 15px rgba(31,53,46,.025)",
        },
      },
    },
    MuiTextField: { defaultProps: { variant: "outlined", fullWidth: true } },
    MuiTableCell: {
      styleOverrides: {
        head: {
          color: "#64736c",
          backgroundColor: "#fafbf9",
          fontWeight: 600,
          fontSize: "0.75rem",
        },
        root: { borderColor: "#eef0ec", padding: "15px 18px" },
      },
    },
    MuiCssBaseline: {
      styleOverrides: {
        body: { margin: 0 },
        a: { color: "inherit" },
        "*": { boxSizing: "border-box" },
        ":focus-visible": { outline: "3px solid #709585", outlineOffset: 3 },
      },
    },
  },
});

import { useState } from "react";
import {
  AppBar,
  Avatar,
  Box,
  Button,
  Chip,
  Drawer,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Stack,
  Toolbar,
  Typography,
  useMediaQuery,
} from "@mui/material";
import {
  DashboardOutlined,
  ReceiptLongOutlined,
  UndoOutlined,
  DnsOutlined,
  Menu,
  ChevronRight,
  Logout,
  ArrowOutward,
} from "@mui/icons-material";
import {
  NavLink,
  Outlet,
  useLocation,
  useNavigate,
  Link,
} from "react-router-dom";
import { useTheme } from "@mui/material/styles";
import { useSession } from "../hooks/SessionProvider";
const nav = [
  { label: "Overview", path: "/", icon: <DashboardOutlined /> },
  { label: "Payments", path: "/payments", icon: <ReceiptLongOutlined /> },
  { label: "Refunds", path: "/refunds", icon: <UndoOutlined /> },
  { label: "System", path: "/system", icon: <DnsOutlined /> },
];
export function DashboardLayout() {
  const [open, setOpen] = useState(false);
  const theme = useTheme();
  const desktop = useMediaQuery(theme.breakpoints.up("lg"));
  const { connected, disconnect } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const active = location.pathname.startsWith("/payments")
    ? "Payments"
    : location.pathname.startsWith("/refunds")
      ? "Refunds"
      : location.pathname === "/system"
        ? "System"
        : location.pathname === "/connect"
          ? "Connect"
          : "Overview";
  const drawer = (
    <Box
      component="nav"
      aria-label="Main navigation"
      sx={{ height: "100%", display: "flex", flexDirection: "column", p: 2.5 }}
    >
      <Stack
        direction="row"
        spacing={1.5}
        alignItems="center"
        sx={{ mb: 5, mt: 1 }}
      >
        <Box
          sx={{
            width: 38,
            height: 38,
            borderRadius: 2,
            bgcolor: "#dceab8",
            color: "#173b36",
            display: "grid",
            placeItems: "center",
            fontSize: 26,
            fontWeight: 800,
          }}
        >
          P
        </Box>
        <Box>
          <Typography fontWeight={750} fontSize={23} letterSpacing="-.04em">
            PayFlow
          </Typography>
          <Typography variant="caption" sx={{ color: "#bfd0c7" }}>
            Merchant workspace
          </Typography>
        </Box>
      </Stack>
      <Typography variant="overline" sx={{ color: "#a6bdb1", mb: 1, ml: 1 }}>
        WORKSPACE
      </Typography>
      <List>
        {nav.map((n) => (
          <ListItemButton
            key={n.path}
            component={NavLink}
            end={n.path === "/"}
            to={n.path}
            onClick={() => setOpen(false)}
            sx={{
              borderRadius: 2,
              mb: 0.7,
              color: "#c6d7ce",
              "&.active": { bgcolor: "#2a5145", color: "#ffffff" },
              "&:hover": { bgcolor: "#244a40" },
            }}
          >
            <ListItemIcon sx={{ minWidth: 37, color: "inherit" }}>
              {n.icon}
            </ListItemIcon>
            <ListItemText
              primary={n.label}
              slotProps={{ primary: { fontWeight: 600, fontSize: ".9rem" } }}
            />
          </ListItemButton>
        ))}
      </List>
      <Box
        sx={{ mt: "auto", border: "1px solid #35574b", p: 2, borderRadius: 3 }}
      >
        <Chip
          size="small"
          label="LOCAL DEMO"
          sx={{
            color: "#dceab8",
            bgcolor: "#2a5145",
            fontSize: 10,
            fontWeight: 700,
            mb: 1,
          }}
        />
        <Typography variant="body2" sx={{ color: "#d8e3dc" }}>
          Built for safe exploration.
        </Typography>
        <Typography
          variant="caption"
          sx={{ display: "block", color: "#a6bdb1", mt: 1 }}
        >
          Mock providers. No real funds.
        </Typography>
        <Button
          component={Link}
          to="/system"
          endIcon={<ArrowOutward fontSize="small" />}
          size="small"
          sx={{ color: "#dceab8", mt: 1, px: 0 }}
        >
          Check system status
        </Button>
      </Box>
    </Box>
  );
  return (
    <Box sx={{ display: "flex", minHeight: "100dvh" }}>
      <Box
        component="a"
        href="#main-content"
        sx={{
          position: "fixed",
          top: -60,
          left: 8,
          bgcolor: "background.paper",
          zIndex: 2000,
          p: 1,
          "&:focus": { top: 8 },
        }}
      >
        Skip to content
      </Box>
      <Box sx={{ width: { lg: 244 }, flexShrink: { lg: 0 } }}>
        <Drawer
          variant={desktop ? "permanent" : "temporary"}
          open={desktop || open}
          onClose={() => setOpen(false)}
          ModalProps={{ keepMounted: true }}
          slotProps={{
            paper: {
              sx: { width: 244, bgcolor: "#173b36", color: "#fff", border: 0 },
            },
          }}
        >
          {drawer}
        </Drawer>
      </Box>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <AppBar
          position="sticky"
          elevation={0}
          color="inherit"
          sx={{ borderBottom: "1px solid #e4e8e1" }}
        >
          <Toolbar
            sx={{ justifyContent: "space-between", gap: 1, minHeight: 74 }}
          >
            <Stack direction="row" alignItems="center" spacing={1}>
              {!desktop && (
                <IconButton
                  aria-label="Open navigation"
                  onClick={() => setOpen(true)}
                >
                  <Menu />
                </IconButton>
              )}
              <Typography
                color="text.secondary"
                variant="body2"
                sx={{ display: { xs: "none", sm: "block" } }}
              >
                Workspace
              </Typography>
              <ChevronRight
                sx={{
                  fontSize: 17,
                  color: "text.secondary",
                  display: { xs: "none", sm: "block" },
                }}
              />
              <Typography fontWeight={650}>{active}</Typography>
            </Stack>
            <Stack direction="row" alignItems="center" spacing={1}>
              <Avatar
                sx={{
                  width: 32,
                  height: 32,
                  bgcolor: "#edf1e9",
                  color: "primary.main",
                  fontSize: 14,
                }}
              >
                M
              </Avatar>
              {connected ? (
                <>
                  <Typography
                    variant="body2"
                    sx={{ display: { xs: "none", sm: "block" } }}
                  >
                    Merchant connected
                  </Typography>
                  <IconButton
                    aria-label="Disconnect merchant"
                    onClick={async () => {
                      await disconnect();
                      navigate("/connect");
                    }}
                  >
                    <Logout fontSize="small" />
                  </IconButton>
                </>
              ) : (
                <Button component={Link} to="/connect" size="small">
                  Connect
                </Button>
              )}
            </Stack>
          </Toolbar>
        </AppBar>
        <Box
          component="main"
          id="main-content"
          tabIndex={-1}
          sx={{
            p: { xs: 2, sm: 3, md: 4 },
            maxWidth: 1560,
            mx: "auto",
            outline: "none",
          }}
        >
          <Outlet />
        </Box>
        <Box
          component="footer"
          sx={{
            px: { xs: 2, md: 4 },
            py: 3,
            color: "text.secondary",
            fontSize: ".75rem",
          }}
        >
          PayFlow · Payment orchestration demo · No real money movement
        </Box>
      </Box>
    </Box>
  );
}

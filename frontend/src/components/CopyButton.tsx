import { useState } from "react";
import { IconButton, Tooltip } from "@mui/material";
import { ContentCopy, Check } from "@mui/icons-material";
import { useToast } from "../hooks/ToastProvider";
export function CopyButton({
  value,
  label = "ID",
}: {
  value: string;
  label?: string;
}) {
  const [copied, setCopied] = useState(false);
  const toast = useToast();
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1800);
    } catch {
      toast(
        "Copy is unavailable. Select the full value to copy it.",
        "warning",
      );
    }
  }
  return (
    <Tooltip title={copied ? "Copied" : `Copy ${label}`}>
      <IconButton
        size="small"
        aria-label={copied ? "Copied" : `Copy ${label}`}
        onClick={(e) => {
          e.stopPropagation();
          void copy();
        }}
      >
        {copied ? (
          <Check fontSize="small" />
        ) : (
          <ContentCopy sx={{ fontSize: 15 }} />
        )}
      </IconButton>
    </Tooltip>
  );
}

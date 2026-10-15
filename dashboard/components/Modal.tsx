import type { ReactNode } from "react";
import { Dialog, DialogBackdrop, DialogPanel, DialogTitle } from "@headlessui/react";

/**
 * A centered dialog on desktop, a bottom sheet on phones. Headless UI supplies the focus trap,
 * Esc / outside-click → onClose, and the scroll lock; the caller decides whether onClose actually
 * closes (the manual-entry modal ignores it while an order is being submitted).
 */
export function Modal({
  open,
  onClose,
  title,
  subtitle,
  children,
}: {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  subtitle?: ReactNode;
  children: ReactNode;
}) {
  return (
    <Dialog open={open} onClose={onClose} className="relative z-50">
      <DialogBackdrop className="fixed inset-0 bg-slate-950/75" />
      <div className="fixed inset-0 flex items-end justify-center sm:items-center sm:p-4">
        <DialogPanel className="max-h-[90vh] w-full overflow-y-auto rounded-t-xl border border-slate-700 bg-slate-900 pb-[env(safe-area-inset-bottom)] shadow-2xl sm:max-w-lg sm:rounded-lg">
          <div className="flex items-start justify-between gap-3 border-b border-slate-800 px-4 py-3">
            <div className="min-w-0">
              <DialogTitle className="text-sm font-semibold text-slate-100">{title}</DialogTitle>
              {subtitle && <div className="mt-0.5 text-xs text-slate-400">{subtitle}</div>}
            </div>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="rounded px-2 py-0.5 text-slate-400 hover:bg-slate-800 hover:text-slate-200"
            >
              ✕
            </button>
          </div>
          {children}
        </DialogPanel>
      </div>
    </Dialog>
  );
}

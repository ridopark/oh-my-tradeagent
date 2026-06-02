"use client";

import { useFormStatus } from "react-dom";

// Submit button for the sign-in server-action forms. useFormStatus reports the parent form's
// pending state, so the spinner appears the instant the form is submitted and stays until the
// action's redirect lands — covering the slow portfolio render that otherwise looks frozen.
export function SubmitButton({
  className,
  children,
}: {
  className: string;
  children: React.ReactNode;
}) {
  const { pending } = useFormStatus();
  return (
    <button
      type="submit"
      disabled={pending}
      aria-busy={pending}
      className={`${className} inline-flex items-center justify-center gap-2 disabled:opacity-60`}
    >
      {pending && (
        <span
          className="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent"
          aria-hidden="true"
        />
      )}
      {children}
    </button>
  );
}

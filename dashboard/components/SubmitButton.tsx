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
        <svg
          className="h-4 w-4 animate-spin"
          viewBox="0 0 24 24"
          fill="none"
          aria-hidden="true"
        >
          <circle
            className="opacity-25"
            cx="12"
            cy="12"
            r="10"
            stroke="currentColor"
            strokeWidth="4"
          />
          <path
            className="opacity-75"
            fill="currentColor"
            d="M4 12a8 8 0 018-8V0C5.4 0 0 5.4 0 12h4z"
          />
        </svg>
      )}
      {children}
    </button>
  );
}

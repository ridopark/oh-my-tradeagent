import { signIn, auth } from "@/auth";
import { devLoginEnabled } from "@/auth.config";
import { redirect } from "next/navigation";
import { SubmitButton } from "@/components/SubmitButton";

// Social sign-in. An already-authenticated user is bounced to the portfolio. The signIn callback
// (auth.ts) denies any identity without a dashboard_user row, so a successful provider login can
// still land back here with ?error=AccessDenied — surfaced below.
export default async function SignInPage({
  searchParams,
}: {
  searchParams: { error?: string };
}) {
  const session = await auth();
  if (session?.tenantId) {
    redirect("/portfolio");
  }
  const denied = searchParams.error === "AccessDenied";

  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-6">
      <h1 className="mb-2 text-2xl font-semibold text-slate-100">Tenant Dashboard</h1>
      <p className="mb-6 text-sm text-slate-400">
        Sign in to view your positions, trades, order history, and portfolio.
      </p>

      {denied && (
        <div className="mb-4 rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-300">
          This account is not provisioned for any tenant. Contact your operator.
        </div>
      )}

      <div className="flex flex-col gap-3">
        <form
          action={async () => {
            "use server";
            await signIn("google", { redirectTo: "/portfolio" });
          }}
        >
          <SubmitButton className="w-full rounded border border-slate-700 bg-slate-900 px-4 py-2 text-sm font-medium text-slate-100 hover:bg-slate-800">
            Continue with Google
          </SubmitButton>
        </form>
        <form
          action={async () => {
            "use server";
            await signIn("facebook", { redirectTo: "/portfolio" });
          }}
        >
          <SubmitButton className="w-full rounded border border-slate-700 bg-slate-900 px-4 py-2 text-sm font-medium text-slate-100 hover:bg-slate-800">
            Continue with Facebook
          </SubmitButton>
        </form>

        {devLoginEnabled && (
          <form
            action={async () => {
              "use server";
              await signIn("dev-login", { redirectTo: "/portfolio" });
            }}
          >
            <SubmitButton className="w-full rounded border border-amber-500/50 bg-amber-500/10 px-4 py-2 text-sm font-medium text-amber-300 hover:bg-amber-500/20">
              Dev login (local only)
            </SubmitButton>
          </form>
        )}
      </div>
    </main>
  );
}

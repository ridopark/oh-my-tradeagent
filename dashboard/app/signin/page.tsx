import { signIn, auth } from "@/auth";
import { devLoginEnabled } from "@/auth.config";
import { redirect } from "next/navigation";

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
      <h1 className="mb-2 text-2xl font-semibold text-slate-800">Tenant Dashboard</h1>
      <p className="mb-6 text-sm text-slate-500">
        Sign in to view your positions, trades, order history, and portfolio.
      </p>

      {denied && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
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
          <button className="w-full rounded border border-slate-300 bg-white px-4 py-2 text-sm font-medium hover:bg-slate-50">
            Continue with Google
          </button>
        </form>
        <form
          action={async () => {
            "use server";
            await signIn("facebook", { redirectTo: "/portfolio" });
          }}
        >
          <button className="w-full rounded border border-slate-300 bg-white px-4 py-2 text-sm font-medium hover:bg-slate-50">
            Continue with Facebook
          </button>
        </form>

        {devLoginEnabled && (
          <form
            action={async () => {
              "use server";
              await signIn("dev-login", { redirectTo: "/portfolio" });
            }}
          >
            <button className="w-full rounded border border-amber-400 bg-amber-50 px-4 py-2 text-sm font-medium text-amber-800 hover:bg-amber-100">
              Dev login (local only)
            </button>
          </form>
        )}
      </div>
    </main>
  );
}

import { notFound } from "next/navigation";
import { auth } from "@/auth";

// Operator-only gate for every /admin/* page. The base middleware only requires *a* valid session
// (any provisioned tenant user); the admin pages additionally require an OPERATOR identity — an
// email in the OPERATOR_EMAILS allowlist, stamped onto session.isOperator by the auth callbacks. A
// non-operator gets a 404 (notFound), not a redirect: the admin surface is invisible to non-
// operators rather than advertising its existence. This is defense-in-depth on top of the server-
// side dark flags on the BFF/api-gateway routes themselves.
export default async function AdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const session = await auth();
  if (!session?.isOperator) {
    notFound();
  }
  return <>{children}</>;
}

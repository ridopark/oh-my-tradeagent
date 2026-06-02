import { redirect } from "next/navigation";

// The dashboard root just lands the user on the portfolio overview (middleware guarantees a
// session by the time this renders).
export default function Home() {
  redirect("/portfolio");
}

import { redirect } from "next/navigation";

// The dashboard root lands the user on the status overview (middleware guarantees a session by the
// time this renders) — the at-a-glance paper/live + broker/account view is the operational home.
export default function Home() {
  redirect("/status");
}

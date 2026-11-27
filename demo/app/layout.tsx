import type { Metadata, Viewport } from "next";
import "../../dashboard/app/globals.css";

export const metadata: Metadata = {
  title: "Trade agent demo",
  description: "A live, read-only view of a paper-trading account.",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-screen pb-20 sm:pb-0">{children}</body>
    </html>
  );
}

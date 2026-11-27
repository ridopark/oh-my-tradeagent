import type { Config } from "tailwindcss";
import dashboard from "../dashboard/tailwind.config";

// The dashboard's theme, scanned over the dashboard components the demo renders plus its own files.
const config: Config = {
  ...dashboard,
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "../dashboard/components/**/*.{ts,tsx}"],
};

export default config;

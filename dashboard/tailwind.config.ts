import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
  ],
  theme: {
    extend: {
      keyframes: {
        // Danger pulse for the tripped account-guard bar: animates border-glow / box-shadow ONLY
        // (constant background) at ~0.67 Hz — well under the WCAG 2.3.1/2.3.2 3-flash/sec threshold.
        "danger-pulse": {
          "0%, 100%": {
            boxShadow: "0 0 0 0 rgba(239,68,68,0)",
            borderColor: "rgba(239,68,68,.55)",
          },
          "50%": {
            boxShadow: "0 2px 24px -2px rgba(239,68,68,.55)",
            borderColor: "rgba(239,68,68,.90)",
          },
        },
        // Amber equivalent for the Phase 2 'unprotected' (cap-OFF) bar — defined now so Phase 2
        // needs no tailwind config change.
        "unprotected-pulse": {
          "0%, 100%": {
            boxShadow: "0 0 0 0 rgba(245,158,11,0)",
            borderColor: "rgba(245,158,11,.55)",
          },
          "50%": {
            boxShadow: "0 2px 24px -2px rgba(245,158,11,.55)",
            borderColor: "rgba(245,158,11,.90)",
          },
        },
      },
      animation: {
        "danger-pulse": "danger-pulse 1.5s ease-in-out infinite",
        "unprotected-pulse": "unprotected-pulse 2.4s ease-in-out infinite",
      },
    },
  },
  plugins: [],
};

export default config;

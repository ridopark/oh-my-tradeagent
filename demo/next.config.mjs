import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

/**
 * The public demo: a STATIC export (plain files for nginx; no server, no route handlers, no
 * middleware, no auth). It renders the dashboard's own view components, imported from
 * ../dashboard, so the two cannot drift apart.
 *
 * @type {import('next').NextConfig}
 */
const nextConfig = {
  output: "export",
  // /status -> /status/index.html, so a plain file server needs no rewrite rules.
  trailingSlash: true,
  reactStrictMode: true,
  images: { unoptimized: true },
  experimental: {
    // Allow importing source from ../dashboard.
    externalDir: true,
  },
  webpack: (config) => {
    // Files under ../dashboard resolve bare imports (react, next/link) from THIS project's
    // node_modules first, so there is exactly one copy of React in the bundle.
    config.resolve.modules = [path.join(here, "node_modules"), ...(config.resolve.modules ?? [])];
    return config;
  },
};

export default nextConfig;

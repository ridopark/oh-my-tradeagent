/** @type {import('next').NextConfig} */
const nextConfig = {
  // Standalone output so the Docker image ships a minimal self-contained server bundle.
  output: "standalone",
  reactStrictMode: true,
};

export default nextConfig;

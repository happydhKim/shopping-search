import type { NextConfig } from "next";

const SEARCH_API = process.env.SEARCH_API_URL ?? "http://localhost:8084";

const nextConfig: NextConfig = {
  async rewrites() {
    return [
      {
        source: "/api/search",
        destination: `${SEARCH_API}/search`,
      },
    ];
  },
};

export default nextConfig;

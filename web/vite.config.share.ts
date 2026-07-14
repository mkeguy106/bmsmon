import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The public share page is its own build with base "/share/" so every asset URL stays
// inside the unauthenticated /share/ Traefik zone — the main bundle's /assets/ sit
// behind Authentik and would 302 an anonymous guest to SSO.
export default defineConfig({
  plugins: [react()],
  root: "share",
  base: "/share/",
  build: { outDir: "../dist/share", emptyOutDir: true },
});

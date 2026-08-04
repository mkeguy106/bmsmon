import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  // v2 is the default UI and builds to dist/index.html; v1 is kept at dist/v1/index.html.
  // Each input key names its emitted entry chunk (assets/v2-*.js, assets/v1-*.js), so the
  // chunk is named for the UI it holds rather than "main" quietly meaning v2. Both shells
  // reference their entry root-absolutely and both bundles use base "/", so neither cares
  // which directory its HTML sits in; they share one dist/assets pool.
  build: { rollupOptions: { input: { v2: "index.html", v1: "v1/index.html" } } },
  server: {
    proxy: {
      "/web": "http://localhost:8000",
      "/api": "http://localhost:8000",
      "/ws": { target: "ws://localhost:8000", ws: true },
    },
  },
});

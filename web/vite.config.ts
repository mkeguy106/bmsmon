import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  build: { rollupOptions: { input: { main: "index.html", v2: "v2/index.html" } } },
  server: {
    proxy: {
      "/web": "http://localhost:8000",
      "/api": "http://localhost:8000",
      "/ws": { target: "ws://localhost:8000", ws: true },
    },
  },
});

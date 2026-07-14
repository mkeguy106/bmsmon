import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "../../src/v2/tokens.css";

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);

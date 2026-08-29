import { build } from "esbuild";
import { mkdir, readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const directory = dirname(fileURLToPath(import.meta.url));
const projectDirectory = resolve(directory, "..");
const outputFile = resolve(
  projectDirectory,
  "../src/main/assets/youtubei/youtubei.bundle.js",
);
const bootstrap = await readFile(resolve(projectDirectory, "src/bootstrap.js"), "utf8");

await mkdir(dirname(outputFile), { recursive: true });
await build({
  entryPoints: [resolve(projectDirectory, "src/index.js")],
  outfile: outputFile,
  bundle: true,
  platform: "browser",
  format: "iife",
  target: "es2020",
  conditions: ["module", "browser"],
  define: {
    global: "globalThis",
  },
  banner: {
    js: bootstrap,
  },
  keepNames: true,
  minify: true,
  legalComments: "eof",
  sourcemap: false,
  charset: "utf8",
  logLevel: "info",
});

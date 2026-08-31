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
const analyzerRangeConfiguration = "ranges: true,\n            loc: false,\n            module: false";
const optimizedAnalyzerRangeConfiguration =
  "ranges: { start: true, end: true, range: false },\n            loc: false,\n            module: false";
const optimizeYoutubeiAnalyzerMemory = {
  name: "optimize-youtubei-analyzer-memory",
  setup(buildContext) {
    buildContext.onLoad(
      {
        filter: /[\\/]youtubei\.js[\\/]dist[\\/]src[\\/]utils[\\/]javascript[\\/]JsAnalyzer\.js$/,
      },
      async ({ path }) => {
        const source = await readFile(path, "utf8");
        const occurrenceCount = source.split(analyzerRangeConfiguration).length - 1;
        if (occurrenceCount !== 1) {
          throw new Error(
            `Expected one YouTube.js analyzer range configuration, found ${occurrenceCount}`,
          );
        }
        return {
          contents: source.replace(
            analyzerRangeConfiguration,
            optimizedAnalyzerRangeConfiguration,
          ),
          loader: "js",
          resolveDir: dirname(path),
          watchFiles: [path],
        };
      },
    );
  },
};

await mkdir(dirname(outputFile), { recursive: true });
await build({
  entryPoints: [resolve(projectDirectory, "src/index.js")],
  outfile: outputFile,
  bundle: true,
  platform: "browser",
  format: "iife",
  target: "es2020",
  conditions: ["module", "browser"],
  plugins: [optimizeYoutubeiAnalyzerMemory],
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

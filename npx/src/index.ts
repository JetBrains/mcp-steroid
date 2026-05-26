import { spawnSync } from "node:child_process";

const launcher = process.env.DEVRIG_KOTLIN_LAUNCHER;

if (!launcher) {
  process.stderr.write(
    "devrig npm package is a thin launcher stub. Set DEVRIG_KOTLIN_LAUNCHER to the Kotlin devrig executable.\n",
  );
  process.exit(64);
}

const result = spawnSync(launcher, process.argv.slice(2), {
  stdio: "inherit",
});

if (result.error) {
  process.stderr.write(`Failed to start devrig launcher ${launcher}: ${result.error.message}\n`);
  process.exit(64);
}

process.exit(result.status ?? 1);

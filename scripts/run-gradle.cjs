const { spawnSync } = require('node:child_process');
const path = require('node:path');

const task = process.argv[2];
if (!task) {
  console.error('Usage: node scripts/run-gradle.cjs <gradle-task> [...args]');
  process.exit(2);
}

const repoRoot = path.resolve(__dirname, '..');
const javaProjectDir = path.join(repoRoot, 'packages', 'java');
const wrapper = path.join(javaProjectDir, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
const gradleArgs = ['-p', javaProjectDir, task, ...process.argv.slice(3)];
const command = process.platform === 'win32' ? process.env.ComSpec || 'cmd.exe' : wrapper;
const args = process.platform === 'win32'
  ? ['/d', '/c', wrapper, ...gradleArgs]
  : gradleArgs;

// Avoid package-script path parsing. On Windows, cmd.exe is required to launch .bat files.
const result = spawnSync(command, args, {
  cwd: repoRoot,
  stdio: 'inherit',
  shell: false
});

if (result.error) {
  console.error(result.error.message);
  process.exit(1);
}

process.exit(result.status ?? 1);

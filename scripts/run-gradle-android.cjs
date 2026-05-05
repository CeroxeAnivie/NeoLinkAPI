const {spawnSync} = require('node:child_process');
const path = require('node:path');

const task = process.argv[2];
if (!task) {
    console.error('Usage: node scripts/run-gradle-android.cjs <gradle-task> [...args]');
    process.exit(2);
}

const repoRoot = path.resolve(__dirname, '..');
const androidProjectDir = path.join(repoRoot, 'packages', 'java', 'android', 'neolinkapi-android');
const wrapper = path.join(androidProjectDir, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
const userArgs = process.argv.slice(3);
const gradleArgs = [
    '-p',
    androidProjectDir,
    '--daemon',
    '--build-cache',
    '--configuration-cache',
    task,
    ...userArgs
];
const command = process.platform === 'win32' ? process.env.ComSpec || 'cmd.exe' : wrapper;
const args = process.platform === 'win32'
    ? ['/d', '/c', wrapper, ...gradleArgs]
    : gradleArgs;

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

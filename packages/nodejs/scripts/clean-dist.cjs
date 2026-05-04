'use strict';

const fs = require('node:fs');
const path = require('node:path');

const packageRoot = path.resolve(__dirname, '..');

for (const directoryName of ['dist', 'dist-cjs']) {
  fs.rmSync(path.join(packageRoot, directoryName), { recursive: true, force: true });
}

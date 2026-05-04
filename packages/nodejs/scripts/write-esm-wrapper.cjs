'use strict';

const fs = require('node:fs');
const path = require('node:path');

const distDir = path.resolve(__dirname, '..', 'dist');
const cjsEntryPath = path.join(distDir, 'index.js');
const esmEntryPath = path.join(distDir, 'index.mjs');

if (!fs.existsSync(cjsEntryPath)) {
  throw new Error(`Cannot generate ESM wrapper because the CommonJS entry does not exist: ${cjsEntryPath}`);
}

const cjsExports = require(cjsEntryPath);
const exportNames = Object.keys(cjsExports).filter((name) => name !== 'default').sort();

const lines = [
  "import cjs from './index.js';",
  ''
];

for (const exportName of exportNames) {
  lines.push(`export const ${exportName} = cjs.${exportName};`);
}

lines.push('');
lines.push('export default cjs;');
lines.push('');

fs.writeFileSync(esmEntryPath, lines.join('\n'), 'utf8');

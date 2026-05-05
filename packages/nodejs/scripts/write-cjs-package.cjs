'use strict';

const fs = require('node:fs');
const path = require('node:path');

const packageRoot = path.resolve(__dirname, '..');
const distCjsDir = path.join(packageRoot, 'dist-cjs');

if (!fs.existsSync(path.join(distCjsDir, 'index.js'))) {
    throw new Error(`Cannot mark CommonJS output because the entry file is missing: ${path.join(distCjsDir, 'index.js')}`);
}

fs.writeFileSync(
    path.join(distCjsDir, 'package.json'),
    `${JSON.stringify({type: 'commonjs'}, null, 2)}\n`,
    'utf8'
);

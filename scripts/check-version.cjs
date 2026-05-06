'use strict';

const fs = require('node:fs');
const path = require('node:path');

const rootDir = path.resolve(__dirname, '..');
const version = JSON.parse(fs.readFileSync(path.join(rootDir, 'shared', 'version.json'), 'utf8')).apiVersion;

if (!/^\d+\.\d+\.\d+$/.test(String(version))) {
    throw new Error(`shared/version.json contains an invalid apiVersion: ${version}`);
}

const checks = [
    ['package.json', new RegExp(`"version": "${version}"`)],
    ['packages/nodejs/package.json', new RegExp(`"version": "${version}"`)],
    ['package-lock.json', new RegExp(`"version": "${version}"`)],
    ['packages/nodejs/src/version-info.ts', new RegExp(`VERSION = '${version}'`)],
    ['packages/java/build.gradle.kts', new RegExp(`version = "${version}"`)],
    ['packages/java/android/neolinkapi-android/build.gradle', new RegExp(`version = '${version}'`)],
    ['shared/protocol/neolink-protocol.contract.json', new RegExp(`"protocolVersion": "${version}"`)],
    ['shared/fixtures/handshake/startup-responses.json', new RegExp(`It should be :${version}`)],
    ['README.md', new RegExp(version.replace(/\./g, '\\.'))],
    ['docs/Java.md', new RegExp(version.replace(/\./g, '\\.'))],
    ['docs/Android.md', new RegExp(version.replace(/\./g, '\\.'))],
    ['packages/java/desktop/src/test/java/top/ceroxe/api/neolink/NeoLinkCfgTest.java', new RegExp(`"${version}"`)],
    ['packages/java/desktop/src/test/java/top/ceroxe/api/neolink/HandshakeProtocolMirrorTest.java', new RegExp(`:${version}`)],
    ['packages/nodejs/src/test/core.test.ts', new RegExp(`'${version}'`)],
    ['packages/nodejs/src/test/lifecycle.test.ts', new RegExp(`zh;${version};key;`)]
];

const failures = [];
for (const [relativePath, matcher] of checks) {
    const filePath = path.join(rootDir, relativePath);
    if (!fs.existsSync(filePath)) {
        failures.push(`${relativePath}: missing file`);
        continue;
    }
    const source = fs.readFileSync(filePath, 'utf8');
    if (!matcher.test(source)) {
        failures.push(`${relativePath}: expected version ${version}`);
    }
}

if (failures.length > 0) {
    throw new Error(`Version check failed:\n${failures.join('\n')}`);
}

console.log(`Version check passed: ${version}`);

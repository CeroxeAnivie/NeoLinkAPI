'use strict';

const fs = require('node:fs');
const path = require('node:path');

const rootDir = path.resolve(__dirname, '..');
const version = JSON.parse(fs.readFileSync(path.join(rootDir, 'shared', 'version.json'), 'utf8')).apiVersion;

if (!/^\d+\.\d+\.\d+$/.test(String(version))) {
    throw new Error(`shared/version.json contains an invalid apiVersion: ${version}`);
}

const checks = [
    ['packages/java/build.gradle.kts', new RegExp(`version = "${version}"`)],
    ['packages/java/android/neolinkapi-android/build.gradle', new RegExp(`version = '${version}'`)],
    ['shared/protocol/neolink-protocol.contract.json', new RegExp(`"protocolVersion": "${version}"`)],
    ['shared/fixtures/handshake/startup-responses.json', new RegExp(`It should be :${version}`)],
    ['README.md', new RegExp(`neolinkapi-desktop:${version.replace(/\./g, '\\.')}`)],
    ['README.md', new RegExp(`neolinkapi-shared[\\s\\S]*?<version>${version.replace(/\./g, '\\.')}</version>`)],
    ['docs/Java.md', new RegExp(`neolinkapi-desktop:${version.replace(/\./g, '\\.')}`)],
    ['docs/Android.md', new RegExp(`neolinkapi-android:${version.replace(/\./g, '\\.')}`)],
    ['packages/java/desktop/src/test/java/top/ceroxe/api/neolink/NeoLinkCfgTest.java', new RegExp(`"${version}"`)],
    ['packages/java/desktop/src/test/java/top/ceroxe/api/neolink/HandshakeProtocolMirrorTest.java', new RegExp(`:${version}`)]
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
        failures.push(`${relativePath}: expected Java/API version ${version}`);
    }
}

if (failures.length > 0) {
    throw new Error(`Version check failed:\n${failures.join('\n')}`);
}

console.log(`Java/API version check passed: ${version}`);

'use strict';

const fs = require('node:fs');
const path = require('node:path');

const rootDir = path.resolve(__dirname, '..');
const versionFilePath = path.join(rootDir, 'shared', 'version.json');
const versionData = JSON.parse(fs.readFileSync(versionFilePath, 'utf8'));
const apiVersion = String(versionData.apiVersion ?? '').trim();

if (!/^\d+\.\d+\.\d+$/.test(apiVersion)) {
    throw new Error(`shared/version.json must define a semantic version like 7.2.1. Received: ${apiVersion || '<empty>'}`);
}

function replaceInFile(relativePath, matcher, replacement) {
    const filePath = path.join(rootDir, relativePath);
    const source = fs.readFileSync(filePath, 'utf8');
    if (!matcher.test(source)) {
        throw new Error(`Pattern was not found while syncing version in ${relativePath}`);
    }
    fs.writeFileSync(filePath, source.replace(matcher, replacement), 'utf8');
}

replaceInFile(
    'packages/java/build.gradle.kts',
    /version = "[^"]+"/,
    `version = "${apiVersion}"`
);

replaceInFile(
    'packages/java/android/neolinkapi-android/build.gradle',
    /version = '[^']+'/,
    `version = '${apiVersion}'`
);

replaceInFile(
    'shared/protocol/neolink-protocol.contract.json',
    /"protocolVersion": "[^"]+"/,
    `"protocolVersion": "${apiVersion}"`
);

replaceInFile(
    'shared/fixtures/handshake/startup-responses.json',
    /Unsupported version ! It should be :[0-9.]+/,
    `Unsupported version ! It should be :${apiVersion}`
);

replaceInFile(
    'README.md',
    /(top\.ceroxe\.api:neolinkapi-(?:desktop|shared|android):)\d+\.\d+\.\d+/g,
    `$1${apiVersion}`
);

replaceInFile(
    'README.md',
    /(<artifactId>neolinkapi-(?:desktop|shared|android)<\/artifactId>\r?\n\s*<version>)\d+\.\d+\.\d+(<\/version>)/g,
    `$1${apiVersion}$2`
);

for (const relativePath of ['docs/Java.md', 'docs/Android.md']) {
    replaceInFile(
        relativePath,
        /(top\.ceroxe\.api:neolinkapi-(?:desktop|shared|android):)\d+\.\d+\.\d+/g,
        `$1${apiVersion}`
    );
}

replaceInFile(
    'packages/java/desktop/src/test/java/top/ceroxe/api/neolink/HandshakeProtocolMirrorTest.java',
    /不受支持的版本，应该为:[0-9.]+/,
    `不受支持的版本，应该为:${apiVersion}`
);

replaceInFile(
    'packages/java/desktop/src/test/java/top/ceroxe/api/neolink/NeoLinkCfgTest.java',
    /assertEquals\("[0-9.]+", NeoLinkAPI\.version\(\)\);/,
    `assertEquals("${apiVersion}", NeoLinkAPI.version());`
);

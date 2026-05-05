'use strict';

const fs = require('node:fs');
const path = require('node:path');

const rootDir = path.resolve(__dirname, '..');
const versionFilePath = path.join(rootDir, 'shared', 'version.json');
const versionData = JSON.parse(fs.readFileSync(versionFilePath, 'utf8'));
const apiVersion = String(versionData.apiVersion ?? '').trim();

if (!/^\d+\.\d+\.\d+$/.test(apiVersion)) {
    throw new Error(`shared/version.json must define a semantic version like 7.1.7. Received: ${apiVersion || '<empty>'}`);
}

function writeJson(relativePath, updater) {
    const filePath = path.join(rootDir, relativePath);
    const json = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    updater(json);
    fs.writeFileSync(filePath, `${JSON.stringify(json, null, 2)}\n`, 'utf8');
}

function replaceInFile(relativePath, matcher, replacement) {
    const filePath = path.join(rootDir, relativePath);
    const source = fs.readFileSync(filePath, 'utf8');
    if (!matcher.test(source)) {
        throw new Error(`Pattern was not found while syncing version in ${relativePath}`);
    }
    const updated = source.replace(matcher, replacement);
    fs.writeFileSync(filePath, updated, 'utf8');
}

writeJson('package.json', (json) => {
    json.version = apiVersion;
});

writeJson('packages/nodejs/package.json', (json) => {
    json.version = apiVersion;
});

writeJson('package-lock.json', (json) => {
    json.version = apiVersion;
    if (json.packages?.['']) {
        json.packages[''].version = apiVersion;
    }
    if (json.packages?.['packages/nodejs']) {
        json.packages['packages/nodejs'].version = apiVersion;
    }
});

replaceInFile(
    'packages/nodejs/src/version-info.ts',
    /export const VERSION = '[^']+';/,
    `export const VERSION = '${apiVersion}';`
);

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
    /7\.1\.\d+/g,
    apiVersion
);

for (const relativePath of ['docs/Java.md', 'docs/Android.md']) {
    replaceInFile(
        relativePath,
        /7\.1\.\d+/g,
        apiVersion
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

replaceInFile(
    'packages/nodejs/src/test/core.test.ts',
    /assert\.equal\(NeoLinkAPI\.version\(\), '[0-9.]+'\);/,
    `assert.equal(NeoLinkAPI.version(), '${apiVersion}');`
);

replaceInFile(
    'packages/nodejs/src/test/core.test.ts',
    /assert\.equal\(esmModule\.VERSION, '[0-9.]+'\);/,
    `assert.equal(esmModule.VERSION, '${apiVersion}');`
);

replaceInFile(
    'packages/nodejs/src/test/core.test.ts',
    /assert\.equal\(cjsModule\.VERSION, '[0-9.]+'\);/,
    `assert.equal(cjsModule.VERSION, '${apiVersion}');`
);

replaceInFile(
    'packages/nodejs/src/test/lifecycle.test.ts',
    /assert\.equal\(handshake, 'zh;[0-9.]+;key;'\);/,
    `assert.equal(handshake, 'zh;${apiVersion};key;');`
);

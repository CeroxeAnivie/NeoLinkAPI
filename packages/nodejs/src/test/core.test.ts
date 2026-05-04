import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { NeoLinkAPI, NeoLinkCfg, NeoNode, NodeFetcher } from '../index';

test('NeoLinkCfg keeps Java defaults and fluent setters', () => {
  const cfg = new NeoLinkCfg('top.ceroxe.example', 44801, 44802, 'key', 25565);
  assert.equal(cfg.getLocalDomainName(), 'localhost');
  assert.equal(cfg.getProxyIPToLocalServer(), '');
  assert.equal(cfg.getProxyIPToNeoServer(), '');
  assert.equal(cfg.getHeartBeatPacketDelay(), 1000);
  assert.equal(cfg.isTCPEnabled(), true);
  assert.equal(cfg.isUDPEnabled(), true);
  assert.equal(cfg.isPPV2Enabled(), false);
  assert.equal(cfg.isDebugMsg(), false);
  assert.equal(cfg.getLanguage(), NeoLinkCfg.ZH_CH);
  assert.equal(NeoLinkAPI.version(), '7.1.7');

  cfg.setRemoteDomainName('nps.example.com')
    .setHookPort(30001)
    .setHostConnectPort(30002)
    .setLocalDomainName('127.0.0.1')
    .setLocalPort(19132)
    .setKey('secret-key')
    .setProxyIPToLocalServer('socks->127.0.0.1:7890')
    .setProxyIPToNeoServer('http->127.0.0.1:8080')
    .setHeartBeatPacketDelay(1500)
    .setTCPEnabled(false)
    .setUDPEnabled(true)
    .setPPV2Enabled()
    .setLanguage('en-us')
    .setClientVersion('6.0.2-test')
    .setDebugMsg();

  assert.equal(cfg.getRemoteDomainName(), 'nps.example.com');
  assert.equal(cfg.getHookPort(), 30001);
  assert.equal(cfg.getHostConnectPort(), 30002);
  assert.equal(cfg.getLocalDomainName(), '127.0.0.1');
  assert.equal(cfg.getLocalPort(), 19132);
  assert.equal(cfg.getKey(), 'secret-key');
  assert.equal(cfg.getProxyIPToLocalServer(), 'socks->127.0.0.1:7890');
  assert.equal(cfg.getProxyIPToNeoServer(), 'http->127.0.0.1:8080');
  assert.equal(cfg.getHeartBeatPacketDelay(), 1500);
  assert.equal(cfg.isTCPEnabled(), false);
  assert.equal(cfg.isUDPEnabled(), true);
  assert.equal(cfg.isPPV2Enabled(), true);
  assert.equal(cfg.getLanguage(), NeoLinkCfg.EN_US);
  assert.equal(cfg.getClientVersion(), '6.0.2-test');
  assert.equal(cfg.isDebugMsg(), true);
});

test('NodeFetcher parses NKM payload with Java-compatible defaults', () => {
  const nodes = NodeFetcher.parseNodeMap(JSON.stringify([
    {
      realId: 'node-suqian',
      name: '中国 - 宿迁官方',
      address: 'p.ceroxe.top',
      icon: "<svg viewBox='0 0 1 1'></svg>"
    }
  ]));
  assert.equal(nodes.size, 1);
  const node = nodes.get('node-suqian')!;
  assert.equal(node.getName(), '中国 - 宿迁官方');
  assert.equal(node.getRealId(), 'node-suqian');
  assert.equal(node.getAddress(), 'p.ceroxe.top');
  assert.equal(node.getHookPort(), NodeFetcher.DEFAULT_HOST_HOOK_PORT);
  assert.equal(node.getConnectPort(), NodeFetcher.DEFAULT_HOST_CONNECT_PORT);
});

test('NeoNode converts public NKM metadata into startup config', () => {
  const node = new NeoNode('Node', 'id', 'host.example.com', null, 44801, 44802);
  const cfg = node.toCfg('key', 25565);
  assert.equal(cfg.getRemoteDomainName(), 'host.example.com');
  assert.equal(cfg.getHookPort(), 44801);
  assert.equal(cfg.getHostConnectPort(), 44802);
  assert.equal(cfg.getKey(), 'key');
  assert.equal(cfg.getLocalPort(), 25565);
});

test('tunnel address parser mirrors Java English and Chinese messages', () => {
  assert.equal(
    NeoLinkAPI.parseTunAddrMessage('Use the address: edge.example.test:45678 to start up connections.'),
    'edge.example.test:45678'
  );
  assert.equal(
    NeoLinkAPI.parseTunAddrMessage('使用链接地址： cn.example.test:45679 来从公网连接。'),
    'cn.example.test:45679'
  );
});

test('package exposes an ESM-compatible entry point', async () => {
  const esmModule = await import(pathToFileURL(path.resolve(__dirname, '../index.mjs')).href);
  assert.equal(typeof esmModule.NeoLinkAPI, 'function');
  assert.equal(typeof esmModule.NeoLinkCfg, 'function');
  assert.equal(esmModule.VERSION, '7.1.7');
});

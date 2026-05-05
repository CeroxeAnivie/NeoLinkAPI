import test from 'node:test';
import assert from 'node:assert/strict';
import {NeoLinkAPI, NeoLinkCfg, NeoLinkState, SecureServerSocket} from '../index.js';

test('NeoLinkAPI performs startup handshake and captures tunnel address', async () => {
    const server = await SecureServerSocket.listen(0);
    const states: NeoLinkState[] = [];
    const serverTask = (async () => {
        const socket = await server.accept();
        try {
            const handshake = await socket.receiveStr(2000);
            assert.equal(handshake, 'zh;7.1.9;key;');
            await socket.sendStr('Connection build up successfully');
            await socket.sendStr('Use the address: tunnel.example.test:45678 to start up connections.');
            await new Promise((resolve) => setTimeout(resolve, 100));
        } finally {
            socket.close();
        }
    })();

    const cfg = new NeoLinkCfg('127.0.0.1', server.getLocalPort(), server.getLocalPort(), 'key', 25565)
        .setTCPEnabled(false)
        .setUDPEnabled(false);
    const tunnel = new NeoLinkAPI(cfg).setOnStateChanged((state) => states.push(state));
    const startTask = tunnel.start();

    try {
        assert.equal(await tunnel.getTunAddr(), 'tunnel.example.test:45678');
        tunnel.close();
        await startTask;
        await serverTask;
        assert.ok(states.includes(NeoLinkState.STARTING));
        assert.ok(states.includes(NeoLinkState.RUNNING));
        assert.equal(tunnel.getState(), NeoLinkState.STOPPED);
    } finally {
        tunnel.close();
        server.close();
    }
});

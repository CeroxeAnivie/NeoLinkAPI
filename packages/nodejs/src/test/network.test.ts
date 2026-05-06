import test from 'node:test';
import assert from 'node:assert/strict';
import type {AddressInfo} from 'node:net';
import {createUdpSocket, deserializeToDatagramPacket, serializeDatagramPacket} from '../network/udp-transformer.js';
import {ipv6ToBytes, parseProxySettings} from '../network/proxy-operator.js';

test('UDP datagram serialization rejects malformed packets with diagnostics', () => {
    const diagnostics: string[] = [];

    assert.equal(deserializeToDatagramPacket(Buffer.from([0x00]), (message) => {
        if (message) {
            diagnostics.push(message);
        }
    }), null);

    assert.deepEqual(diagnostics, ['Serialized UDP packet is too short']);
});

test('UDP datagram serialization preserves IPv4 and IPv6 endpoints', () => {
    const ipv4 = deserializeToDatagramPacket(serializeDatagramPacket(Buffer.from('hello'), {
        address: '127.0.0.1',
        port: 19132
    }));
    assert.deepEqual(ipv4, {data: Buffer.from('hello'), address: '127.0.0.1', port: 19132});

    const ipv6 = deserializeToDatagramPacket(serializeDatagramPacket(Buffer.from('world'), {
        address: '2001:db8::1',
        port: 25565
    }));
    assert.deepEqual(ipv6, {data: Buffer.from('world'), address: '2001:db8:0:0:0:0:0:1', port: 25565});
});

test('Node UDP socket factory intentionally creates IPv4 sockets', async () => {
    const socket = await createUdpSocket();
    try {
        const address = socket.address() as AddressInfo;
        assert.equal(address.family, 'IPv4');
    } finally {
        socket.close();
    }
});

test('proxy parser handles direct, authenticated proxy and IPv6 proxy addresses', () => {
    assert.deepEqual(parseProxySettings('', 'target.example'), {
        proxyType: 'direct',
        proxyHost: '',
        proxyPort: 0,
        targetHost: 'target.example',
        username: null,
        password: null
    });

    assert.deepEqual(parseProxySettings('socks->127.0.0.1:1080@user;pass', 'target.example'), {
        proxyType: 'socks',
        proxyHost: '127.0.0.1',
        proxyPort: 1080,
        targetHost: 'target.example',
        username: 'user',
        password: 'pass'
    });

    assert.deepEqual(parseProxySettings('http->[2001:db8::1]:8080', 'target.example'), {
        proxyType: 'http',
        proxyHost: '2001:db8::1',
        proxyPort: 8080,
        targetHost: 'target.example',
        username: null,
        password: null
    });
});

test('proxy parser rejects malformed proxy configuration', () => {
    assert.throws(() => parseProxySettings('ftp->127.0.0.1:21', 'target.example'), /Unsupported proxy type/);
    assert.throws(() => parseProxySettings('socks->127.0.0.1:not-a-port', 'target.example'), /Proxy port must be an integer/);
    assert.throws(() => parseProxySettings('socks->127.0.0.1:1080@;pass', 'target.example'), /Invalid proxy authentication format/);
});

test('proxy IPv6 encoder rejects invalid IPv6 segments', () => {
    assert.throws(() => ipv6ToBytes('2001:db8::zzzz'), /Invalid IPv6 address/);
});

import {Buffer} from 'node:buffer';
import net from 'node:net';
import {SecureSocket} from '../secure-socket.js';
import {closeQuietly, connectTcp, SocketReader, writeAll} from './socket-utils.js';

export const TO_NEO = 0;
export const TO_LOCAL = 1;
const SOCKS_VERSION = 0x05;
const SOCKS_AUTH_VERSION = 0x01;
const SOCKS_NO_AUTH = 0x00;
const SOCKS_USER_PASS = 0x02;
const SOCKS_CONNECT = 0x01;
const SOCKS_ADDRESS_IPV4 = 0x01;
const SOCKS_ADDRESS_DOMAIN = 0x03;
const SOCKS_ADDRESS_IPV6 = 0x04;
const MAX_SOCKS_FIELD_LENGTH = 255;

interface ProxySettings {
    proxyType: 'direct' | 'socks' | 'http';
    proxyHost: string;
    proxyPort: number;
    targetHost: string;
    username: string | null;
    password: string | null;
}

function parseProxyPort(value: string): number {
    const port = Number(value.trim());
    if (!Number.isInteger(port)) {
        throw new TypeError('Proxy port must be an integer.');
    }
    if (port < 1 || port > 65535) {
        throw new RangeError('Proxy port must be between 1 and 65535.');
    }
    return port;
}

function parseHostPort(value: string): { host: string; port: number } {
    if (value == null || String(value).trim() === '') {
        throw new TypeError('Proxy address must not be blank.');
    }
    const trimmed = String(value).trim();
    if (trimmed.startsWith('[')) {
        const closingBracket = trimmed.indexOf(']');
        if (closingBracket <= 1 || closingBracket + 2 > trimmed.length || trimmed[closingBracket + 1] !== ':') {
            throw new TypeError(`Invalid IPv6 proxy address: ${value}`);
        }
        return {host: trimmed.slice(1, closingBracket), port: parseProxyPort(trimmed.slice(closingBracket + 2))};
    }
    const colon = trimmed.indexOf(':');
    if (colon <= 0) {
        throw new TypeError(`Invalid proxy address: ${value}`);
    }
    return {host: trimmed.slice(0, colon), port: parseProxyPort(trimmed.slice(colon + 1))};
}

export function parseProxySettings(proxyConfig: string | null | undefined, targetHost: string): ProxySettings {
    if (proxyConfig == null || String(proxyConfig).trim() === '') {
        return {proxyType: 'direct', proxyHost: '', proxyPort: 0, targetHost, username: null, password: null};
    }
    const [rawType, rawProperty] = String(proxyConfig).trim().split('->', 2);
    if (!rawType || !rawProperty || rawProperty.trim() === '') {
        throw new TypeError('Invalid proxy format. Expected type->host:port[@user;password].');
    }
    const proxyType = rawType.trim().toLowerCase();
    if (proxyType !== 'socks' && proxyType !== 'http' && proxyType !== 'direct') {
        throw new TypeError(`Unsupported proxy type: ${rawType}`);
    }
    const [addressPart, authPart] = rawProperty.split('@', 2);
    const hostPort = parseHostPort(addressPart!);
    let username: string | null = null;
    let password: string | null = null;
    if (authPart != null) {
        const [user, pass] = authPart.split(';', 2);
        if (pass == null || user!.trim() === '') {
            throw new TypeError('Invalid proxy authentication format. Expected user;password.');
        }
        username = user!;
        password = pass;
    }
    return {proxyType, proxyHost: hostPort.host, proxyPort: hostPort.port, targetHost, username, password};
}

function hasProxy(settings: ProxySettings): boolean {
    return settings.proxyType !== 'direct';
}

function hasCredentials(settings: ProxySettings): boolean {
    return hasProxy(settings) && settings.username != null && settings.password != null;
}

function unbracketIpv6(host: string): string {
    const trimmed = host.trim();
    return trimmed.startsWith('[') && trimmed.endsWith(']') ? trimmed.slice(1, -1) : trimmed;
}

function isIPv4Literal(host: string): boolean {
    return /^\d{1,3}(\.\d{1,3}){3}$/.test(host)
        && host.split('.').every((part) => Number(part) >= 0 && Number(part) <= 255);
}

function isIPv6Literal(host: string): boolean {
    return host.includes(':');
}

export function ipv4ToBytes(host: string): Buffer {
    return Buffer.from(host.split('.').map((part) => Number(part)));
}

export function ipv6ToBytes(host: string): Buffer {
    let value = unbracketIpv6(host);
    const zoneIndex = value.indexOf('%');
    if (zoneIndex >= 0) {
        value = value.slice(0, zoneIndex);
    }
    if (value.includes('.')) {
        const lastColon = value.lastIndexOf(':');
        const ipv4 = ipv4ToBytes(value.slice(lastColon + 1));
        value = `${value.slice(0, lastColon)}:${ipv4.readUInt16BE(0).toString(16)}:${ipv4.readUInt16BE(2).toString(16)}`;
    }
    const hasCompression = value.includes('::');
    const [headRaw, tailRaw = ''] = value.split('::');
    const head = headRaw ? headRaw.split(':').filter(Boolean) : [];
    const tail = tailRaw ? tailRaw.split(':').filter(Boolean) : [];
    const missing = hasCompression ? 8 - head.length - tail.length : 0;
    const parts = hasCompression ? [...head, ...Array(missing).fill('0'), ...tail] : head;
    if (parts.length !== 8 || missing < 0) {
        throw new Error(`Invalid IPv6 address: ${host}`);
    }
    const out = Buffer.alloc(16);
    parts.forEach((part, index) => out.writeUInt16BE(parseInt(part, 16), index * 2));
    return out;
}

function writePort(port: number): Buffer {
    const out = Buffer.alloc(2);
    out.writeUInt16BE(port, 0);
    return out;
}

function socksAddressBytes(host: string): Buffer {
    const unbracketed = unbracketIpv6(host);
    if (isIPv4Literal(unbracketed)) {
        return Buffer.concat([Buffer.from([SOCKS_ADDRESS_IPV4]), ipv4ToBytes(unbracketed)]);
    }
    if (isIPv6Literal(unbracketed)) {
        return Buffer.concat([Buffer.from([SOCKS_ADDRESS_IPV6]), ipv6ToBytes(unbracketed)]);
    }
    const hostBytes = Buffer.from(unbracketed, 'utf8');
    if (hostBytes.length > MAX_SOCKS_FIELD_LENGTH) {
        throw new Error(`SOCKS5 target host is too long: ${unbracketed}`);
    }
    return Buffer.concat([Buffer.from([SOCKS_ADDRESS_DOMAIN, hostBytes.length]), hostBytes]);
}

async function authenticateSocks5(reader: SocketReader, socket: net.Socket, settings: ProxySettings): Promise<void> {
    const username = Buffer.from(settings.username!, 'utf8');
    const password = Buffer.from(settings.password!, 'utf8');
    if (username.length > MAX_SOCKS_FIELD_LENGTH || password.length > MAX_SOCKS_FIELD_LENGTH) {
        throw new Error('SOCKS5 username and password must be at most 255 bytes.');
    }
    await writeAll(socket, Buffer.concat([
        Buffer.from([SOCKS_AUTH_VERSION, username.length]),
        username,
        Buffer.from([password.length]),
        password
    ]));
    const response = await reader.readExact(2);
    if (response[0] !== SOCKS_AUTH_VERSION || response[1] !== 0x00) {
        throw new Error('SOCKS5 username/password authentication failed.');
    }
}

async function consumeSocksBoundAddress(reader: SocketReader, addressType: number): Promise<void> {
    switch (addressType) {
        case SOCKS_ADDRESS_IPV4:
            await reader.readExact(4);
            break;
        case SOCKS_ADDRESS_IPV6:
            await reader.readExact(16);
            break;
        case SOCKS_ADDRESS_DOMAIN: {
            const length = (await reader.readExact(1))[0]!;
            await reader.readExact(length);
            break;
        }
        default:
            throw new Error(`Unsupported SOCKS5 bound address type: ${addressType}`);
    }
    await reader.readExact(2);
}

async function connectViaSocks5(socket: net.Socket, settings: ProxySettings, targetPort: number): Promise<void> {
    const reader = new SocketReader(socket, 'Proxy closed the connection during handshake.');
    try {
        const methods = hasCredentials(settings)
            ? Buffer.from([SOCKS_VERSION, 0x02, SOCKS_NO_AUTH, SOCKS_USER_PASS])
            : Buffer.from([SOCKS_VERSION, 0x01, SOCKS_NO_AUTH]);
        await writeAll(socket, methods);
        const negotiation = await reader.readExact(2);
        if (negotiation[0] !== SOCKS_VERSION) {
            throw new Error('Invalid SOCKS5 negotiation response.');
        }
        const selectedMethod = negotiation[1]!;
        if (selectedMethod === 0xFF) {
            throw new Error('SOCKS5 proxy rejected all authentication methods.');
        }
        if (selectedMethod === SOCKS_USER_PASS) {
            await authenticateSocks5(reader, socket, settings);
        } else if (selectedMethod !== SOCKS_NO_AUTH) {
            throw new Error(`Unsupported SOCKS5 authentication method: ${selectedMethod}`);
        }
        await writeAll(socket, Buffer.concat([
            Buffer.from([SOCKS_VERSION, SOCKS_CONNECT, 0x00]),
            socksAddressBytes(settings.targetHost),
            writePort(targetPort)
        ]));
        const response = await reader.readExact(4);
        if (response[0] !== SOCKS_VERSION) {
            throw new Error('Invalid SOCKS5 connect response.');
        }
        if (response[1] !== 0x00) {
            throw new Error(`SOCKS5 connect failed with status: ${response[1]}`);
        }
        await consumeSocksBoundAddress(reader, response[3]!);
    } finally {
        const leftover = reader.releaseBuffered();
        if (leftover.length > 0) {
            socket.unshift(leftover);
        }
        reader.dispose();
    }
}

function formatAuthority(host: string, port: number): string {
    const unbracketed = unbracketIpv6(host);
    return isIPv6Literal(unbracketed) ? `[${unbracketed}]:${port}` : `${unbracketed}:${port}`;
}

async function readHttpHeader(reader: SocketReader): Promise<string> {
    const chunks: number[] = [];
    let state = 0;
    while (true) {
        const next = (await reader.readExact(1))[0]!;
        chunks.push(next);
        state = state === 0 ? (next === 13 ? 1 : 0)
            : state === 1 ? (next === 10 ? 2 : 0)
                : state === 2 ? (next === 13 ? 3 : 0)
                    : state === 3 ? (next === 10 ? 4 : 0)
                        : state;
        if (state === 4) {
            return Buffer.from(chunks).toString('latin1');
        }
        if (chunks.length > 16 * 1024) {
            throw new Error('HTTP proxy response header is too large.');
        }
    }
}

async function connectViaHttp(socket: net.Socket, settings: ProxySettings, targetPort: number): Promise<void> {
    const reader = new SocketReader(socket, 'Proxy closed the connection during handshake.');
    try {
        const authority = formatAuthority(settings.targetHost, targetPort);
        const headers = [
            `CONNECT ${authority} HTTP/1.1`,
            `Host: ${authority}`,
            'Proxy-Connection: Keep-Alive'
        ];
        if (hasCredentials(settings)) {
            headers.push(`Proxy-Authorization: Basic ${Buffer.from(`${settings.username}:${settings.password}`, 'utf8').toString('base64')}`);
        }
        await writeAll(socket, Buffer.from(`${headers.join('\r\n')}\r\n\r\n`, 'latin1'));
        const header = await readHttpHeader(reader);
        const statusLine = header.split(/\r?\n/, 1)[0] ?? '';
        if (!statusLine.startsWith('HTTP/')) {
            throw new Error(`Invalid HTTP proxy response: ${statusLine}`);
        }
        const parts = statusLine.split(' ');
        if (parts.length < 2 || parts[1] !== '200') {
            throw new Error(`HTTP proxy CONNECT failed: ${statusLine}`);
        }
    } finally {
        const leftover = reader.releaseBuffered();
        if (leftover.length > 0) {
            socket.unshift(leftover);
        }
        reader.dispose();
    }
}

export class ProxyOperator {
    static readonly TO_NEO = TO_NEO;
    static readonly TO_LOCAL = TO_LOCAL;

    private readonly toNeo: ProxySettings;
    private readonly toLocal: ProxySettings;

    constructor(remoteHost: string, localHost: string, proxyToNeoServer: string, proxyToLocalServer: string) {
        this.toNeo = parseProxySettings(proxyToNeoServer, remoteHost);
        this.toLocal = parseProxySettings(proxyToLocalServer, localHost);
    }

    async getHandledSocket(socketType: number, targetPort: number, connectTimeoutMillis: number): Promise<net.Socket> {
        const settings = this.settingsFor(socketType);
        if (!hasProxy(settings)) {
            return connectTcp(settings.targetHost, targetPort, connectTimeoutMillis);
        }
        const socket = await connectTcp(settings.proxyHost, settings.proxyPort, connectTimeoutMillis);
        try {
            if (settings.proxyType === 'socks') {
                await connectViaSocks5(socket, settings, targetPort);
            } else if (settings.proxyType === 'http') {
                await connectViaHttp(socket, settings, targetPort);
            } else {
                throw new Error(`Unsupported proxy type: ${settings.proxyType}`);
            }
            socket.setTimeout(0);
            return socket;
        } catch (error) {
            closeQuietly(socket);
            throw error;
        }
    }

    async getHandledSecureSocket(socketType: number, targetPort: number, connectTimeoutMillis: number): Promise<SecureSocket> {
        return new SecureSocket(await this.getHandledSocket(socketType, targetPort, connectTimeoutMillis));
    }

    hasProxy(socketType: number): boolean {
        return hasProxy(this.settingsFor(socketType));
    }

    private settingsFor(socketType: number): ProxySettings {
        return socketType === TO_NEO ? this.toNeo : this.toLocal;
    }
}

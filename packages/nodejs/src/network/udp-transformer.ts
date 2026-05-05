import dgram from 'node:dgram';
import net from 'node:net';
import {SecureSocket} from '../secure-socket.js';
import type {DebugSink} from '../types.js';

export const MAGIC = 0xDEADBEEF;
export const SERIALIZED_HEADER_FIXED_LENGTH = 4 + 4 + 4 + 2;
export const IPV4_LENGTH = 4;
export const IPV6_LENGTH = 16;
export const BUFFER_LENGTH = 65535;

export interface DatagramPacket {
    data: Buffer;
    address: string;
    port: number;
}

export function ipv4ToBytes(address: string): Buffer {
    const parts = address.split('.').map((part) => Number(part));
    if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
        throw new Error(`Invalid IPv4 address: ${address}`);
    }
    return Buffer.from(parts);
}

export function ipv6ToBytes(address: string): Buffer {
    let value = address;
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
        throw new Error(`Invalid IPv6 address: ${address}`);
    }
    const out = Buffer.alloc(16);
    parts.forEach((part, index) => {
        const value16 = parseInt(part, 16);
        if (!Number.isInteger(value16) || value16 < 0 || value16 > 0xFFFF) {
            throw new Error(`Invalid IPv6 address: ${address}`);
        }
        out.writeUInt16BE(value16, index * 2);
    });
    return out;
}

export function ipToBytes(address: string): Buffer {
    const family = net.isIP(address);
    if (family === 4) {
        return ipv4ToBytes(address);
    }
    if (family === 6) {
        return ipv6ToBytes(address);
    }
    throw new Error(`Invalid IP address: ${address}`);
}

export function bytesToIp(bytes: Buffer): string {
    if (bytes.length === IPV4_LENGTH) {
        return Array.from(bytes).join('.');
    }
    if (bytes.length === IPV6_LENGTH) {
        const parts: string[] = [];
        for (let offset = 0; offset < bytes.length; offset += 2) {
            parts.push(bytes.readUInt16BE(offset).toString(16));
        }
        return parts.join(':');
    }
    throw new Error(`Invalid IP address length: ${bytes.length}`);
}

export function serializeDatagramPacket(message: Buffer | Uint8Array, rinfo: {
    address: string;
    port: number
}): Buffer {
    const payload = Buffer.from(message);
    const ipBytes = ipToBytes(rinfo.address);
    const totalLength = 4 + 4 + 4 + ipBytes.length + 2 + payload.length;
    if (totalLength > BUFFER_LENGTH) {
        throw new RangeError(`UDP packet too large for serialization buffer: ${totalLength}`);
    }
    const out = Buffer.alloc(totalLength);
    let offset = 0;
    out.writeUInt32BE(MAGIC, offset);
    offset += 4;
    out.writeInt32BE(payload.length, offset);
    offset += 4;
    out.writeInt32BE(ipBytes.length, offset);
    offset += 4;
    ipBytes.copy(out, offset);
    offset += ipBytes.length;
    out.writeUInt16BE(rinfo.port, offset);
    offset += 2;
    payload.copy(out, offset);
    return out;
}

export function deserializeToDatagramPacket(serializedData: Buffer | Uint8Array, debug: DebugSink = () => undefined): DatagramPacket | null {
    const data = Buffer.from(serializedData);
    if (data.length < SERIALIZED_HEADER_FIXED_LENGTH + IPV4_LENGTH) {
        debug('Serialized UDP packet is too short');
        return null;
    }
    let offset = 0;
    const magic = data.readUInt32BE(offset);
    offset += 4;
    if (magic !== MAGIC) {
        debug('Invalid magic number in serialized data');
        return null;
    }
    const dataLen = data.readInt32BE(offset);
    offset += 4;
    const ipLen = data.readInt32BE(offset);
    offset += 4;
    if (dataLen < 0 || dataLen > BUFFER_LENGTH) {
        debug(`Invalid UDP data length: ${dataLen}`);
        return null;
    }
    if (ipLen !== IPV4_LENGTH && ipLen !== IPV6_LENGTH) {
        debug(`Invalid IP address length: ${ipLen}`);
        return null;
    }
    const expectedLength = SERIALIZED_HEADER_FIXED_LENGTH + ipLen + dataLen;
    if (data.length !== expectedLength) {
        debug('Serialized UDP packet length mismatch');
        return null;
    }
    const ipBytes = data.subarray(offset, offset + ipLen);
    offset += ipLen;
    const port = data.readUInt16BE(offset);
    offset += 2;
    const payload = Buffer.from(data.subarray(offset, offset + dataLen));
    return {data: payload, address: bytesToIp(ipBytes), port};
}

interface DatagramQueue {
    next(): Promise<{ message: Buffer; rinfo: dgram.RemoteInfo } | null>;
}

function createDatagramQueue(socket: dgram.Socket): DatagramQueue {
    const queue: Array<{ message: Buffer; rinfo: dgram.RemoteInfo }> = [];
    const waiters: Array<{
        resolve: (value: { message: Buffer; rinfo: dgram.RemoteInfo } | null) => void;
        reject: (error: Error) => void
    }> = [];
    let closed = false;
    let failure: Error | null = null;
    socket.on('message', (message, rinfo) => {
        const item = {message, rinfo};
        const waiter = waiters.shift();
        if (waiter) {
            waiter.resolve(item);
        } else {
            queue.push(item);
        }
    });
    socket.once('close', () => {
        closed = true;
        for (const waiter of waiters.splice(0)) {
            waiter.resolve(null);
        }
    });
    socket.once('error', (error) => {
        failure = error;
        for (const waiter of waiters.splice(0)) {
            waiter.reject(error);
        }
    });
    return {
        next() {
            if (queue.length > 0) {
                return Promise.resolve(queue.shift()!);
            }
            if (failure) {
                return Promise.reject(failure);
            }
            if (closed) {
                return Promise.resolve(null);
            }
            return new Promise((resolve, reject) => waiters.push({resolve, reject}));
        }
    };
}

export function sendDatagram(socket: dgram.Socket, message: Buffer | Uint8Array, port: number, host: string): Promise<void> {
    return new Promise((resolve, reject) => {
        socket.send(message, port, host, (error) => error ? reject(error) : resolve());
    });
}

export async function transferLocalToNeo(datagramSocket: dgram.Socket, secureSocket: SecureSocket, debug: DebugSink = () => undefined): Promise<void> {
    const queue = createDatagramQueue(datagramSocket);
    try {
        while (true) {
            const item = await queue.next();
            if (item == null) {
                break;
            }
            const serialized = serializeDatagramPacket(item.message, item.rinfo);
            debug(`Forwarding UDP packet from local service to NeoProxyServer. source=${item.rinfo.address}:${item.rinfo.port}, payloadBytes=${item.message.length}, serializedBytes=${serialized.length}`);
            await secureSocket.sendBytes(serialized);
        }
    } catch (error) {
        debug(null, error);
    }
}

export async function transferNeoToLocal(
    secureSocket: SecureSocket,
    datagramSocket: dgram.Socket,
    localHost: string,
    localPort: number,
    debug: DebugSink = () => undefined
): Promise<void> {
    try {
        while (true) {
            const data = await secureSocket.receiveBytes();
            if (data == null) {
                break;
            }
            debug(`Received UDP frame from NeoProxyServer. serializedBytes=${data.length}`);
            const packet = deserializeToDatagramPacket(data, debug);
            if (packet) {
                await sendDatagram(datagramSocket, packet.data, localPort, localHost);
                debug(`Forwarded UDP packet to local service. target=${localHost}:${localPort}, payloadBytes=${packet.data.length}`);
            }
        }
    } catch (error) {
        debug(null, error);
    }
}

export function createUdpSocket(): Promise<dgram.Socket> {
    const socket = dgram.createSocket('udp4');
    return new Promise((resolve, reject) => {
        socket.once('error', reject);
        socket.bind(0, '0.0.0.0', () => {
            socket.off('error', reject);
            resolve(socket);
        });
    });
}

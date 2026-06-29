import net from 'node:net';
import {SecureSocket} from '../secure-socket.js';
import type {DebugSink} from '../types.js';
import {writeAll} from './socket-utils.js';

export const MODE_NEO_TO_LOCAL = 0;
export const MODE_LOCAL_TO_NEO = 1;
export const PPV2_SIG = Buffer.from([0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A]);
export const PPV2_MIN_HEADER_LENGTH = 16;

function shutdownOutput(socket: net.Socket): void {
    try {
        if (!socket.destroyed) {
            socket.end();
        }
    } catch {
        // 半关闭只能尽力而为，因为对向方向有自己的生命周期。
    }
}

export class ProxyProtocolStripper {
    private pendingFirstFrame: Buffer | null = Buffer.alloc(0);
    private decided = false;

    constructor(private readonly passThroughProxyProtocol: boolean, private readonly debug: DebugSink = () => undefined) {
    }

    accept(data: Buffer): Buffer | null {
        if (this.decided) {
            return data;
        }
        this.pendingFirstFrame = Buffer.concat([this.pendingFirstFrame!, data]);
        if (this.pendingFirstFrame.length < PPV2_SIG.length) {
            return null;
        }
        if (!this.pendingFirstFrame.subarray(0, PPV2_SIG.length).equals(PPV2_SIG)) {
            this.decided = true;
            const out = this.pendingFirstFrame;
            this.pendingFirstFrame = null;
            return out;
        }
        if (this.pendingFirstFrame.length < PPV2_MIN_HEADER_LENGTH) {
            return null;
        }
        const headerLength = PPV2_MIN_HEADER_LENGTH + this.pendingFirstFrame.readUInt16BE(14);
        if (this.pendingFirstFrame.length < headerLength) {
            return null;
        }
        this.decided = true;
        const buffered = this.pendingFirstFrame;
        this.pendingFirstFrame = null;
        if (this.passThroughProxyProtocol) {
            this.debug('Proxy Protocol v2 header detected and passed through to local service.');
            return buffered;
        }
        this.debug(`Proxy Protocol v2 header detected and stripped. headerBytes=${headerLength}`);
        return buffered.subarray(headerLength);
    }

    finish(): Buffer {
        if (this.decided || !this.pendingFirstFrame || this.pendingFirstFrame.length === 0) {
            return Buffer.alloc(0);
        }
        const out = this.pendingFirstFrame;
        this.pendingFirstFrame = null;
        this.decided = true;
        return out;
    }
}

export async function transferLocalToNeo(localSocket: net.Socket, secureSocket: SecureSocket, debug: DebugSink = () => undefined): Promise<void> {
    try {
        for await (const chunk of localSocket) {
            const data = Buffer.from(chunk as Buffer);
            if (data.length > 0) {
                debug(`Forwarding TCP bytes from local service to NeoProxyServer. bytes=${data.length}`);
                await secureSocket.sendBytes(data);
            }
        }
        debug('Local TCP stream reached EOF. Sending NeoProxyServer EOF frame.');
        await secureSocket.sendBytes(null);
    } catch (error) {
        debug(null, error);
    }
}

export async function transferNeoToLocal(
    secureSocket: SecureSocket,
    localSocket: net.Socket,
    enableProxyProtocol: boolean,
    debug: DebugSink = () => undefined
): Promise<void> {
    const stripper = new ProxyProtocolStripper(enableProxyProtocol, debug);
    try {
        while (true) {
            const data = await secureSocket.receiveBytes();
            if (data == null) {
                break;
            }
            if (data.length === 0) {
                continue;
            }
            debug(`Received TCP frame from NeoProxyServer. bytes=${data.length}`);
            const forwardData = stripper.accept(data);
            if (forwardData && forwardData.length > 0) {
                await writeAll(localSocket, forwardData);
            }
        }
        const trailing = stripper.finish();
        if (trailing.length > 0) {
            await writeAll(localSocket, trailing);
        }
        debug('NeoProxyServer TCP stream reached EOF.');
        shutdownOutput(localSocket);
    } catch (error) {
        debug(null, error);
        shutdownOutput(localSocket);
    }
}

import crypto from 'node:crypto';
import net from 'node:net';
import { SocketReader, closeQuietly, connectTcp, writeAll } from './network/socket-utils.js';
import type { Endpoint } from './types.js';

const KEY_EXCHANGE_ALGO = 'x25519';
export const FRAME_TYPE_STRING = 0x01;
export const FRAME_TYPE_BYTES = 0x02;
export const FRAME_TYPE_INT = 0x03;
export const FRAME_TYPE_EOF = 0x04;
const INT_PAYLOAD_LENGTH = 4;
const GCM_IV_LENGTH = 12;
const GCM_TAG_LENGTH = 16;

let maxAllowedPacketSize = 64 * 1024 * 1024;
let defaultConnectTimeoutMillis = 5000;

function makeIoError(message: string, cause?: unknown): Error {
  return new Error(message, { cause });
}

function deriveAesKey(privateKey: crypto.KeyObject, otherPublicKeyDer: Buffer): Buffer {
  const publicKey = crypto.createPublicKey({
    key: otherPublicKeyDer,
    format: 'der',
    type: 'spki'
  });
  const secret = crypto.diffieHellman({ privateKey, publicKey });
  return crypto.createHash('sha256').update(secret).digest();
}

function encryptAesGcm(key: Buffer, plaintext: Buffer): Buffer {
  const iv = crypto.randomBytes(GCM_IV_LENGTH);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  return Buffer.concat([iv, cipher.update(plaintext), cipher.final(), cipher.getAuthTag()]);
}

function decryptAesGcm(key: Buffer, encrypted: Buffer): Buffer {
  if (encrypted.length <= GCM_IV_LENGTH) {
    throw new Error('Encrypted data too short');
  }
  const iv = encrypted.subarray(0, GCM_IV_LENGTH);
  const tag = encrypted.subarray(encrypted.length - GCM_TAG_LENGTH);
  const ciphertext = encrypted.subarray(GCM_IV_LENGTH, encrypted.length - GCM_TAG_LENGTH);
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(ciphertext), decipher.final()]);
}

function buildFrame(frameType: number, payload: Buffer): Buffer {
  return Buffer.concat([Buffer.from([frameType]), payload]);
}

interface EncryptedFrame {
  type: number;
  payload: Buffer;
}

function decodeFrame(decrypted: Buffer): EncryptedFrame {
  if (decrypted.length === 0) {
    throw makeIoError('Invalid frame: missing frame type');
  }
  const frameType = decrypted[0]!;
  const payload = decrypted.subarray(1);
  if (frameType === FRAME_TYPE_EOF && payload.length !== 0) {
    throw makeIoError(`Invalid EOF frame payload length: ${payload.length}`);
  }
  if (frameType === FRAME_TYPE_INT && payload.length !== INT_PAYLOAD_LENGTH) {
    throw makeIoError(`Invalid int frame payload length: ${payload.length}`);
  }
  if (![FRAME_TYPE_STRING, FRAME_TYPE_BYTES, FRAME_TYPE_INT, FRAME_TYPE_EOF].includes(frameType)) {
    throw makeIoError(`Unknown frame type: ${frameType}`);
  }
  return { type: frameType, payload };
}

export class SecureSocket {
  private socket: net.Socket | null;
  private reader: SocketReader | null;
  private aesKey: Buffer | null = null;
  private handshakeCompleted = false;
  private serverMode = false;
  private closed = false;
  private broken = false;
  private writeQueue: Promise<unknown> = Promise.resolve();
  private readQueue: Promise<unknown> = Promise.resolve();
  private handshakePromise: Promise<void> | null = null;

  constructor(socket: net.Socket | null = null) {
    this.socket = socket;
    this.reader = socket ? new SocketReader(socket) : null;
    if (socket) {
      socket.setKeepAlive(true);
      socket.setNoDelay(true);
      socket.once('close', () => {
        this.closed = true;
      });
      socket.once('error', () => {
        this.broken = true;
      });
    }
  }

  static getMaxAllowedPacketSize(): number { return maxAllowedPacketSize; }

  static setMaxAllowedPacketSize(size: number): void {
    if (!Number.isInteger(Number(size)) || Number(size) <= 0) {
      throw new RangeError('Size must be positive');
    }
    maxAllowedPacketSize = Number(size);
  }

  static getDefaultConnectTimeoutMillis(): number { return defaultConnectTimeoutMillis; }

  static setDefaultConnectTimeoutMillis(timeoutMillis: number): void {
    if (!Number.isInteger(Number(timeoutMillis)) || Number(timeoutMillis) < 0) {
      throw new RangeError('Timeout must be zero or positive');
    }
    defaultConnectTimeoutMillis = Number(timeoutMillis);
  }

  static async connect(host: string, port: number, connectTimeoutMillis = defaultConnectTimeoutMillis): Promise<SecureSocket> {
    const secureSocket = new SecureSocket();
    await secureSocket.connect(host, port, connectTimeoutMillis);
    await secureSocket.performClientHandshake();
    return secureSocket;
  }

  initServerMode(): void {
    this.serverMode = true;
    this.handshakeCompleted = false;
  }

  async connect(host: string, port: number, connectTimeoutMillis = defaultConnectTimeoutMillis): Promise<void> {
    if (this.closed) {
      throw makeIoError('Socket is closed');
    }
    if (this.socket && !this.socket.destroyed) {
      throw makeIoError('Socket is already connected');
    }
    if (connectTimeoutMillis < 0) {
      throw new RangeError('Timeout must be zero or positive');
    }
    this.socket = await connectTcp(host, port, connectTimeoutMillis);
    this.reader = new SocketReader(this.socket);
    this.handshakeCompleted = false;
    this.serverMode = false;
    this.aesKey = null;
  }

  async ensureHandshake(): Promise<void> {
    if (this.handshakeCompleted) {
      return;
    }
    if (this.closed || this.broken) {
      throw makeIoError('Connection closed');
    }
    if (!this.handshakePromise) {
      this.handshakePromise = this.serverMode ? this.performServerHandshake() : this.performClientHandshake();
    }
    await this.handshakePromise;
  }

  async performServerHandshake(): Promise<void> {
    const { publicKey, privateKey } = crypto.generateKeyPairSync(KEY_EXCHANGE_ALGO);
    const publicDer = publicKey.export({ format: 'der', type: 'spki' }) as Buffer;
    await this.writeRawPacketInternal(publicDer);
    const otherPublicDer = await this.readRawPacketInternal();
    this.aesKey = deriveAesKey(privateKey, otherPublicDer);
    this.handshakeCompleted = true;
  }

  async performClientHandshake(): Promise<void> {
    const serverPublicDer = await this.readRawPacketInternal();
    const { publicKey, privateKey } = crypto.generateKeyPairSync(KEY_EXCHANGE_ALGO);
    const publicDer = publicKey.export({ format: 'der', type: 'spki' }) as Buffer;
    await this.writeRawPacketInternal(publicDer);
    this.aesKey = deriveAesKey(privateKey, serverPublicDer);
    this.handshakeCompleted = true;
  }

  async writeRawPacketInternal(data: Buffer): Promise<void> {
    if (this.broken) {
      throw makeIoError('Connection broken');
    }
    const payload = Buffer.from(data);
    const header = Buffer.alloc(4);
    header.writeInt32BE(payload.length, 0);
    await this.serializeWrite(Buffer.concat([header, payload]));
  }

  async readRawPacketInternal(timeoutMillis = 0): Promise<Buffer> {
    if (this.broken) {
      throw makeIoError('Connection broken');
    }
    if (!this.reader) {
      throw makeIoError('Socket is not connected');
    }
    const header = await this.reader.readExact(4, timeoutMillis);
    const length = header.readInt32BE(0);
    if (length < 0) {
      throw makeIoError(`Negative packet length: ${length}`);
    }
    if (length > maxAllowedPacketSize) {
      this.markConnectionBroken();
      throw makeIoError(`Packet too large: ${length} (Max: ${maxAllowedPacketSize})`);
    }
    if (length === 0) {
      return Buffer.alloc(0);
    }
    return this.reader.readExact(length, timeoutMillis);
  }

  async sendStr(message: string | null): Promise<number> {
    await this.ensureHandshake();
    const payload = message == null ? Buffer.alloc(0) : Buffer.from(String(message), 'utf8');
    return this.sendEncryptedFrame(message == null ? FRAME_TYPE_EOF : FRAME_TYPE_STRING, payload);
  }

  async receiveStr(timeoutMillis = 0): Promise<string | null> {
    await this.ensureHandshake();
    const frame = await this.receiveFrame(timeoutMillis);
    if (frame.type === FRAME_TYPE_EOF) {
      return null;
    }
    if (frame.type !== FRAME_TYPE_STRING) {
      throw makeIoError(`Unexpected frame type for string receive: ${frame.type}`);
    }
    return frame.payload.toString('utf8');
  }

  async sendBytes(data: Buffer | Uint8Array | null, offset = 0, length?: number): Promise<number> {
    await this.ensureHandshake();
    if (data == null) {
      return this.sendEncryptedFrame(FRAME_TYPE_EOF, Buffer.alloc(0));
    }
    const source = Buffer.from(data);
    const end = length == null ? source.length : offset + length;
    if (offset < 0 || end < offset || end > source.length) {
      throw new RangeError('Invalid offset or length');
    }
    return this.sendEncryptedFrame(FRAME_TYPE_BYTES, source.subarray(offset, end));
  }

  async receiveBytes(timeoutMillis = 0): Promise<Buffer | null> {
    await this.ensureHandshake();
    const frame = await this.receiveFrame(timeoutMillis);
    if (frame.type === FRAME_TYPE_EOF) {
      return null;
    }
    if (frame.type !== FRAME_TYPE_BYTES) {
      throw makeIoError(`Unexpected frame type for byte receive: ${frame.type}`);
    }
    return Buffer.from(frame.payload);
  }

  async sendInt(value: number): Promise<number> {
    await this.ensureHandshake();
    const payload = Buffer.alloc(4);
    payload.writeInt32BE(Number(value), 0);
    return this.sendEncryptedFrame(FRAME_TYPE_INT, payload);
  }

  async receiveInt(timeoutMillis = 0): Promise<number> {
    await this.ensureHandshake();
    const frame = await this.receiveFrame(timeoutMillis);
    if (frame.type !== FRAME_TYPE_INT) {
      throw makeIoError(`Unexpected frame type for int receive: ${frame.type}`);
    }
    if (frame.payload.length !== 4) {
      throw makeIoError(`Invalid int payload length: ${frame.payload.length}`);
    }
    return frame.payload.readInt32BE(0);
  }

  close(): void {
    if (this.closed) {
      return;
    }
    this.closed = true;
    this.broken = true;
    closeQuietly(this.socket);
  }

  shutdownInput(): void { this.close(); }

  shutdownOutput(): void {
    if (this.socket && !this.socket.destroyed) {
      this.socket.end();
    }
  }

  isClosed(): boolean {
    return this.closed || this.broken || !this.socket || this.socket.destroyed;
  }

  isConnected(): boolean {
    return Boolean(this.socket && !this.isClosed());
  }

  isConnectionBroken(): boolean { return this.broken; }
  getPort(): number { return this.socket?.remotePort ?? 0; }
  getLocalPort(): number { return this.socket?.localPort ?? 0; }
  getRemoteSocketAddress(): Endpoint { return { host: this.socket?.remoteAddress ?? 'unknown', port: this.socket?.remotePort ?? 0 }; }
  getLocalSocketAddress(): Endpoint { return { host: this.socket?.localAddress ?? 'unknown', port: this.socket?.localPort ?? 0 }; }

  private async sendEncryptedFrame(frameType: number, payload: Buffer): Promise<number> {
    if (!this.aesKey) {
      throw makeIoError('Handshake is not complete');
    }
    const encrypted = encryptAesGcm(this.aesKey, buildFrame(frameType, payload));
    await this.writeRawPacketInternal(encrypted);
    return 4 + encrypted.length;
  }

  private async receiveFrame(timeoutMillis = 0): Promise<EncryptedFrame> {
    const encrypted = await this.serializeRead(() => this.readRawPacketInternal(timeoutMillis));
    if (encrypted.length === 0) {
      this.markConnectionBroken();
      throw makeIoError('Invalid encrypted packet: empty raw packet');
    }
    try {
      if (!this.aesKey) {
        throw makeIoError('Handshake is not complete');
      }
      return decodeFrame(decryptAesGcm(this.aesKey, encrypted));
    } catch (cause) {
      this.markConnectionBroken();
      throw makeIoError('Encrypted frame authentication failed', cause);
    }
  }

  private markConnectionBroken(): void {
    this.broken = true;
    this.close();
  }

  private serializeWrite(buffer: Buffer): Promise<void> {
    if (!this.socket) {
      return Promise.reject(makeIoError('Socket is not connected'));
    }
    const write = this.writeQueue.then(() => writeAll(this.socket!, buffer));
    this.writeQueue = write.catch(() => undefined);
    return write;
  }

  private serializeRead(task: () => Promise<Buffer>): Promise<Buffer> {
    const read = this.readQueue.then(task);
    this.readQueue = read.catch(() => undefined);
    return read;
  }
}

export class SecureServerSocket {
  private readonly ignoreIPs = new Set<string>();
  private readonly pending: SecureSocket[] = [];
  private readonly waiters: Array<{ resolve: (socket: SecureSocket) => void; reject: (error: Error) => void; timer: NodeJS.Timeout | null }> = [];

  private constructor(private readonly server: net.Server, private readonly port: number) {
    server.on('connection', (socket) => {
      socket.setKeepAlive(true);
      socket.setNoDelay(true);
      const address = socket.remoteAddress;
      if (address && this.ignoreIPs.has(address)) {
        socket.destroy();
        return;
      }
      const secureSocket = new SecureSocket(socket);
      secureSocket.initServerMode();
      const waiter = this.waiters.shift();
      if (waiter) {
        if (waiter.timer) {
          clearTimeout(waiter.timer);
        }
        waiter.resolve(secureSocket);
      } else {
        this.pending.push(secureSocket);
      }
    });
    server.on('error', (error) => {
      for (const waiter of this.waiters.splice(0)) {
        if (waiter.timer) {
          clearTimeout(waiter.timer);
        }
        waiter.reject(error);
      }
    });
  }

  static listen(port = 0, host = '127.0.0.1'): Promise<SecureServerSocket> {
    return new Promise((resolve, reject) => {
      const server = net.createServer();
      server.once('error', reject);
      server.listen(port, host, () => {
        server.off('error', reject);
        const address = server.address();
        const localPort = typeof address === 'object' && address ? address.port : port;
        resolve(new SecureServerSocket(server, localPort));
      });
    });
  }

  accept(timeoutMillis = 0): Promise<SecureSocket> {
    if (this.pending.length > 0) {
      return Promise.resolve(this.pending.shift()!);
    }
    return new Promise((resolve, reject) => {
      const waiter = { resolve, reject, timer: null as NodeJS.Timeout | null };
      if (timeoutMillis > 0) {
        waiter.timer = setTimeout(() => {
          const index = this.waiters.indexOf(waiter);
          if (index >= 0) {
            this.waiters.splice(index, 1);
          }
          reject(new Error('Accept timed out'));
        }, timeoutMillis);
      }
      this.waiters.push(waiter);
    });
  }

  addIgnoreIP(ip: string): void { this.ignoreIPs.add(ip); }
  removeIgnoreIP(ip: string): boolean { return this.ignoreIPs.delete(ip); }
  getIgnoreIPs(): string[] { return Array.from(this.ignoreIPs); }

  close(): void {
    for (const socket of this.pending.splice(0)) {
      socket.close();
    }
    this.server.close();
  }

  isClosed(): boolean { return !this.server.listening; }
  getLocalPort(): number { return this.port; }
}

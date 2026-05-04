import net from 'node:net';

interface Waiter {
  length: number;
  resolve: (value: Buffer) => void;
  reject: (reason?: unknown) => void;
  timer: NodeJS.Timeout | null;
}

export class SocketReader {
  private buffer = Buffer.alloc(0);
  private closed = false;
  private error: Error | null = null;
  private readonly waiters: Waiter[] = [];
  private readonly onData: (chunk: Buffer) => void;
  private readonly onEnd: () => void;
  private readonly onClose: () => void;
  private readonly onError: (error: Error) => void;

  constructor(private readonly socket: net.Socket, private readonly closedMessage = 'Connection closed by peer') {
    this.onData = (chunk: Buffer) => {
      const normalizedChunk = Buffer.from(chunk);
      this.buffer = this.buffer.length === 0 ? normalizedChunk : Buffer.concat([this.buffer, normalizedChunk]);
      this.drain();
    };
    this.onEnd = () => this.closeReader(null);
    this.onClose = () => this.closeReader(null);
    this.onError = (error: Error) => this.closeReader(error);
    socket.on('data', this.onData);
    socket.once('end', this.onEnd);
    socket.once('close', this.onClose);
    socket.once('error', this.onError);
  }

  readExact(length: number, timeoutMillis = 0): Promise<Buffer> {
    if (!Number.isInteger(length) || length < 0) {
      return Promise.reject(new RangeError('length must be zero or positive.'));
    }
    if (length === 0) {
      return Promise.resolve(Buffer.alloc(0));
    }
    if (this.buffer.length >= length) {
      return Promise.resolve(this.consume(length));
    }
    if (this.error) {
      return Promise.reject(this.error);
    }
    if (this.closed) {
      return Promise.reject(new Error(this.closedMessage));
    }
    return new Promise((resolve, reject) => {
      const waiter: Waiter = { length, resolve, reject, timer: null };
      if (timeoutMillis > 0) {
        waiter.timer = setTimeout(() => {
          const index = this.waiters.indexOf(waiter);
          if (index >= 0) {
            this.waiters.splice(index, 1);
          }
          reject(new Error('Read timed out'));
        }, timeoutMillis);
      }
      this.waiters.push(waiter);
      this.drain();
    });
  }

  dispose(): void {
    this.socket.off('data', this.onData);
    this.socket.off('end', this.onEnd);
    this.socket.off('close', this.onClose);
    this.socket.off('error', this.onError);
  }

  releaseBuffered(): Buffer {
    const out = this.buffer;
    this.buffer = Buffer.alloc(0);
    return out;
  }

  private consume(length: number): Buffer {
    const out = this.buffer.subarray(0, length);
    this.buffer = this.buffer.subarray(length);
    return out;
  }

  private drain(): void {
    while (this.waiters.length > 0) {
      const waiter = this.waiters[0]!;
      if (this.buffer.length < waiter.length) {
        break;
      }
      this.waiters.shift();
      if (waiter.timer) {
        clearTimeout(waiter.timer);
      }
      waiter.resolve(this.consume(waiter.length));
    }
  }

  private closeReader(error: Error | null): void {
    if (this.closed && !error) {
      return;
    }
    this.closed = true;
    if (error) {
      this.error = error;
    }
    const waiters = this.waiters.splice(0);
    for (const waiter of waiters) {
      if (waiter.timer) {
        clearTimeout(waiter.timer);
      }
      waiter.reject(error ?? new Error(this.closedMessage));
    }
  }
}

export function writeAll(socket: net.Socket, data: Buffer): Promise<void> {
  if (socket.destroyed) {
    return Promise.reject(new Error('Socket is closed'));
  }
  return new Promise((resolve, reject) => {
    let settled = false;
    const cleanup = () => {
      socket.off('error', onError);
      socket.off('drain', onDrain);
    };
    const finish = (error?: Error | null) => {
      if (settled) {
        return;
      }
      settled = true;
      cleanup();
      if (error) {
        reject(error);
      } else {
        resolve();
      }
    };
    const onError = (error: Error) => finish(error);
    const onDrain = () => finish();
    socket.once('error', onError);
    const flushed = socket.write(data, (error?: Error | null) => finish(error));
    if (!flushed) {
      socket.once('drain', onDrain);
    }
  });
}

export function connectTcp(host: string, port: number, timeoutMillis = 0): Promise<net.Socket> {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port });
    let settled = false;
    let timer: NodeJS.Timeout | null = null;
    const cleanup = () => {
      socket.off('connect', onConnect);
      socket.off('error', onError);
      if (timer) {
        clearTimeout(timer);
      }
    };
    const fail = (error: Error) => {
      if (settled) {
        return;
      }
      settled = true;
      cleanup();
      socket.destroy();
      reject(error);
    };
    const onConnect = () => {
      if (settled) {
        return;
      }
      settled = true;
      cleanup();
      socket.setKeepAlive(true);
      socket.setNoDelay(true);
      resolve(socket);
    };
    const onError = (error: Error) => fail(error);
    socket.once('connect', onConnect);
    socket.once('error', onError);
    if (timeoutMillis > 0) {
      timer = setTimeout(() => fail(new Error(`Connect timed out after ${timeoutMillis} ms`)), timeoutMillis);
    }
  });
}

export function closeQuietly(...resources: Array<{ close?: () => void; destroy?: () => void; end?: () => void } | null | undefined>): void {
  for (const resource of resources) {
    if (!resource) {
      continue;
    }
    try {
      if (typeof resource.close === 'function') {
        resource.close();
      } else if (typeof resource.destroy === 'function') {
        resource.destroy();
      } else if (typeof resource.end === 'function') {
        resource.end();
      }
    } catch {
      // 清理逻辑不能掩盖主要的网络失败。
    }
  }
}

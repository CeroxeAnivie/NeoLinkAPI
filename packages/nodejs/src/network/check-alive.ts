import { SecureSocket } from '../secure-socket.js';
import type { DebugSink, ErrorHandler } from '../types.js';

export const HEARTBEAT_PACKET = 'PING';
export const MAX_CONSECUTIVE_FAILURES = 5;
export const HEARTBEAT_IDLE_MILLIS = 2000;

export class CheckAliveThread {
  private running = false;
  private timer: NodeJS.Timeout | null = null;
  private failureCount = 0;

  constructor(
    private readonly hookSocketSupplier: () => SecureSocket | null,
    private readonly lastReceivedTimeSupplier: () => number,
    private readonly heartbeatPacketDelay: number,
    private readonly errorHandler: ErrorHandler,
    private readonly debugEnabled = false,
    private readonly debugSink: DebugSink = () => undefined
  ) {
    if (!Number.isInteger(Number(heartbeatPacketDelay)) || Number(heartbeatPacketDelay) < 1) {
      throw new RangeError('heartbeatPacketDelay must be greater than 0.');
    }
  }

  startThread(): void {
    if (this.running) {
      return;
    }
    this.running = true;
    this.debug('Heartbeat loop entered.');
    this.schedule();
  }

  stopThread(): void {
    this.running = false;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private schedule(): void {
    if (!this.running) {
      return;
    }
    this.timer = setTimeout(() => void this.tick(), this.heartbeatPacketDelay);
  }

  private async tick(): Promise<void> {
    if (!this.running) {
      return;
    }
    const hookSocket = this.hookSocketSupplier();
    if (!hookSocket || hookSocket.isClosed()) {
      this.debug('Heartbeat loop stopped because hook socket is not available.');
      this.running = false;
      return;
    }
    const idle = Date.now() - this.lastReceivedTimeSupplier();
    if (idle > HEARTBEAT_IDLE_MILLIS) {
      try {
        await hookSocket.sendStr(HEARTBEAT_PACKET);
        this.debug(`Heartbeat PING sent after ${idle} ms idle.`);
        this.failureCount = 0;
      } catch (error) {
        this.failureCount += 1;
        this.debug(`Heartbeat PING failed. consecutiveFailures=${this.failureCount}`);
        if (this.failureCount >= MAX_CONSECUTIVE_FAILURES) {
          this.debug(null, error);
          this.errorHandler('NeoProxyServer heartbeat failed.', error);
          hookSocket.close();
          this.running = false;
          return;
        }
      }
    } else {
      this.failureCount = 0;
    }
    this.schedule();
  }

  private debug(message: string | null, cause?: unknown): void {
    if (!this.debugEnabled) {
      return;
    }
    try {
      this.debugSink(message, cause);
    } catch {
      // 调试回调仅用于观测，不应干扰心跳。
    }
  }
}

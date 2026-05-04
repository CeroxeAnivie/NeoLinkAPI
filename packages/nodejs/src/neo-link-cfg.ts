import { VERSION } from './version-info';

export const EN_US = 'en';
export const ZH_CH = 'zh';
export const DEFAULT_PROXY_IP = '';
export const DEFAULT_LOCAL_DOMAIN_NAME = 'localhost';
export const DEFAULT_HEARTBEAT_PACKET_DELAY = 1000;

export function requireText(value: unknown, fieldName: string): string {
  if (value == null || String(value).trim() === '') {
    throw new TypeError(`${fieldName} must not be blank.`);
  }
  return String(value).trim();
}

function nullToEmpty(value: unknown): string {
  return value == null ? '' : String(value).trim();
}

export function requirePort(value: unknown, fieldName: string): number {
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new RangeError(`${fieldName} must be between 1 and 65535.`);
  }
  return port;
}

export function requirePositive(value: unknown, fieldName: string): number {
  const number = Number(value);
  if (!Number.isInteger(number) || number < 1) {
    throw new RangeError(`${fieldName} must be greater than 0.`);
  }
  return number;
}

function normalizeLanguage(value: unknown): string {
  const normalized = requireText(value, 'language').toLowerCase().replace(/-/g, '_');
  switch (normalized) {
    case EN_US:
    case 'en_us':
    case 'english':
      return EN_US;
    case ZH_CH:
    case 'zh_ch':
    case 'zh_cn':
    case 'chinese':
      return ZH_CH;
    default:
      throw new TypeError('language must be either NeoLinkCfg.EN_US or NeoLinkCfg.ZH_CH.');
  }
}

export class NeoLinkCfg {
  static readonly EN_US = EN_US;
  static readonly ZH_CH = ZH_CH;
  static readonly DEFAULT_PROXY_IP = DEFAULT_PROXY_IP;
  static readonly DEFAULT_LOCAL_DOMAIN_NAME = DEFAULT_LOCAL_DOMAIN_NAME;
  static readonly DEFAULT_HEARTBEAT_PACKET_DELAY = DEFAULT_HEARTBEAT_PACKET_DELAY;

  private remoteDomainName: string;
  private hookPort: number;
  private hostConnectPort: number;
  private localDomainName = DEFAULT_LOCAL_DOMAIN_NAME;
  private localPort: number;
  private key: string;
  private proxyIPToLocalServer = DEFAULT_PROXY_IP;
  private proxyIPToNeoServer = DEFAULT_PROXY_IP;
  private heartBeatPacketDelay = DEFAULT_HEARTBEAT_PACKET_DELAY;
  private tcpEnabled = true;
  private udpEnabled = true;
  private ppv2Enabled = false;
  private debugMsg = false;
  private language = ZH_CH;
  private clientVersion = VERSION;

  constructor(remoteDomainName: string, hookPort: number, hostConnectPort: number, key: string, localPort: number) {
    this.remoteDomainName = requireText(remoteDomainName, 'remoteDomainName');
    this.hookPort = requirePort(hookPort, 'hookPort');
    this.hostConnectPort = requirePort(hostConnectPort, 'hostConnectPort');
    this.key = requireText(key, 'key');
    this.localPort = requirePort(localPort, 'localPort');
  }

  copy(): NeoLinkCfg {
    const cfg = new NeoLinkCfg(this.remoteDomainName, this.hookPort, this.hostConnectPort, this.key, this.localPort);
    cfg.localDomainName = this.localDomainName;
    cfg.proxyIPToLocalServer = this.proxyIPToLocalServer;
    cfg.proxyIPToNeoServer = this.proxyIPToNeoServer;
    cfg.heartBeatPacketDelay = this.heartBeatPacketDelay;
    cfg.tcpEnabled = this.tcpEnabled;
    cfg.udpEnabled = this.udpEnabled;
    cfg.ppv2Enabled = this.ppv2Enabled;
    cfg.debugMsg = this.debugMsg;
    cfg.language = this.language;
    cfg.clientVersion = this.clientVersion;
    return cfg;
  }

  requireStartReady(): void {
    if (this.key == null || String(this.key).trim() === '') {
      throw new Error('key must be configured before starting a NeoLinkCfg fetched from NKM.');
    }
    requirePort(this.localPort, 'localPort');
  }

  getRemoteDomainName(): string { return this.remoteDomainName; }
  setRemoteDomainName(value: string): this { this.remoteDomainName = requireText(value, 'remoteDomainName'); return this; }
  getHookPort(): number { return this.hookPort; }
  setHookPort(value: number): this { this.hookPort = requirePort(value, 'hookPort'); return this; }
  getHostConnectPort(): number { return this.hostConnectPort; }
  setHostConnectPort(value: number): this { this.hostConnectPort = requirePort(value, 'hostConnectPort'); return this; }
  getLocalDomainName(): string { return this.localDomainName; }
  setLocalDomainName(value: string): this { this.localDomainName = requireText(value, 'localDomainName'); return this; }
  getLocalPort(): number { return this.localPort; }
  setLocalPort(value: number): this { this.localPort = requirePort(value, 'localPort'); return this; }
  getKey(): string { return this.key; }
  setKey(value: string): this { this.key = requireText(value, 'key'); return this; }
  getProxyIPToLocalServer(): string { return this.proxyIPToLocalServer; }
  setProxyIPToLocalServer(value: string = DEFAULT_PROXY_IP): this { this.proxyIPToLocalServer = nullToEmpty(value); return this; }
  getProxyIPToNeoServer(): string { return this.proxyIPToNeoServer; }
  setProxyIPToNeoServer(value: string = DEFAULT_PROXY_IP): this { this.proxyIPToNeoServer = nullToEmpty(value); return this; }
  getHeartBeatPacketDelay(): number { return this.heartBeatPacketDelay; }
  setHeartBeatPacketDelay(value: number): this { this.heartBeatPacketDelay = requirePositive(value, 'heartBeatPacketDelay'); return this; }
  isTCPEnabled(): boolean { return this.tcpEnabled; }
  setTCPEnabled(value: boolean): this { this.tcpEnabled = Boolean(value); return this; }
  isUDPEnabled(): boolean { return this.udpEnabled; }
  setUDPEnabled(value: boolean): this { this.udpEnabled = Boolean(value); return this; }
  isPPV2Enabled(): boolean { return this.ppv2Enabled; }
  setPPV2Enabled(value = true): this { this.ppv2Enabled = Boolean(value); return this; }
  getLanguage(): string { return this.language; }
  setLanguage(value: string): this { this.language = normalizeLanguage(value); return this; }
  getClientVersion(): string { return this.clientVersion; }
  setClientVersion(value: string): this { this.clientVersion = requireText(value, 'clientVersion'); return this; }
  isDebugMsg(): boolean { return this.debugMsg; }
  setDebugMsg(value = true): this { this.debugMsg = Boolean(value); return this; }
}

import * as dns from 'node:dns/promises';
import * as dgram from 'node:dgram';
import * as net from 'node:net';
import * as os from 'node:os';
import {allLanguageData} from './language-data.js';
import {NeoLinkCfg, requirePositive} from './neo-link-cfg.js';
import {NeoLinkState} from './neo-link-state.js';
import {SecureSocket} from './secure-socket.js';
import {
    NoMoreNetworkFlowException,
    NoMorePortException,
    NoSuchKeyException,
    OutDatedKeyException,
    PortOccupiedException,
    UnRecognizedKeyException,
    UnsupportedVersionException,
    UnSupportHostVersionException
} from './errors.js';
import type {DebugSink, Endpoint} from './types.js';
import {CheckAliveThread} from './network/check-alive.js';
import {ProxyOperator, TO_LOCAL, TO_NEO} from './network/proxy-operator.js';
import {closeQuietly, connectTcp} from './network/socket-utils.js';
import {
    transferLocalToNeo as transferTcpLocalToNeo,
    transferNeoToLocal as transferTcpNeoToLocal
} from './network/tcp-transformer.js';
import {
    createUdpSocket,
    transferLocalToNeo as transferUdpLocalToNeo,
    transferNeoToLocal as transferUdpNeoToLocal
} from './network/udp-transformer.js';
import {VERSION} from './version-info.js';

const UNKNOWN_HOST = 'unknown';
const WINDOWS_UPDATE_CLIENT_TYPE = 'exe';
const DEFAULT_UPDATE_CLIENT_TYPE = 'jar';
const NO_UPDATE_URL_RESPONSE = 'false';
const DEFAULT_CONNECT_TIMEOUT_MILLIS = 1000;
const UPDATE_URL_TIMEOUT_MILLIS = 15000;
const EN_TUN_ADDR_PREFIX = 'Use the address: ';
const EN_TUN_ADDR_SUFFIX = ' to start up connections.';
const ZH_TUN_ADDR_PREFIX = '使用链接地址： ';
const ZH_TUN_ADDR_SUFFIX = ' 来从公网连接。';
const PROTOCOL_SWITCH_GRACE_MILLIS = 3000;

export enum TransportProtocol {
    TCP = 'TCP',
    UDP = 'UDP'
}

export type ConnectionEventHandler = (protocol: TransportProtocol, source: Endpoint, target: Endpoint) => void;

interface ProtocolFlags {
    tcpEnabled: boolean;
    udpEnabled: boolean;
}

interface ProtocolSwitch {
    previous: ProtocolFlags;
    requested: ProtocolFlags;
    expiresAtMillis: number;
}

class Deferred<T> {
    promise: Promise<T>;
    settled = false;
    private resolveFn!: (value: T) => void;
    private rejectFn!: (reason?: unknown) => void;

    constructor() {
        this.promise = new Promise<T>((resolve, reject) => {
            this.resolveFn = resolve;
            this.rejectFn = reject;
        });
    }

    resolve(value: T): void {
        if (this.settled) {
            return;
        }
        this.settled = true;
        this.resolveFn(value);
    }

    reject(reason?: unknown): void {
        if (this.settled) {
            return;
        }
        this.settled = true;
        this.rejectFn(reason);
    }
}

function normalizeProtocolText(response: string | null | undefined): string {
    return response == null ? '' : response.trim();
}

function equalsProtocolText(response: string | null | undefined, expected: string): boolean {
    return normalizeProtocolText(response) === normalizeProtocolText(expected);
}

function startsWithProtocolText(response: string | null | undefined, expectedPrefix: string): boolean {
    return normalizeProtocolText(response).startsWith(normalizeProtocolText(expectedPrefix));
}

function isWrappedProtocolText(response: string | null | undefined, prefix: string, suffix: string): boolean {
    const normalized = normalizeProtocolText(response);
    return normalized.startsWith(normalizeProtocolText(prefix)) && normalized.endsWith(normalizeProtocolText(suffix));
}

function flagsFromCfg(cfg: NeoLinkCfg): ProtocolFlags {
    return {tcpEnabled: cfg.isTCPEnabled(), udpEnabled: cfg.isUDPEnabled()};
}

function protocolFlagsEqual(left: ProtocolFlags, right: ProtocolFlags): boolean {
    return left.tcpEnabled === right.tcpEnabled && left.udpEnabled === right.udpEnabled;
}

function enabled(flags: ProtocolFlags, protocol: TransportProtocol): boolean {
    return protocol === TransportProtocol.TCP ? flags.tcpEnabled : flags.udpEnabled;
}

function asProtocolFlags(flags: ProtocolFlags): string {
    let out = '';
    if (flags.tcpEnabled) {
        out += 'T';
    }
    if (flags.udpEnabled) {
        out += 'U';
    }
    return out;
}

function normalizeUpdateURL(response: string | null): string | null {
    if (response == null) {
        return null;
    }
    const normalized = response.trim();
    if (normalized === '' || normalized.toLowerCase() === NO_UPDATE_URL_RESPONSE) {
        return null;
    }
    return normalized;
}

function updateClientType(): string {
    return os.platform() === 'win32' ? WINDOWS_UPDATE_CLIENT_TYPE : DEFAULT_UPDATE_CLIENT_TYPE;
}

function extractBetween(value: string, prefix: string, suffix: string): string | null {
    const start = value.indexOf(prefix);
    if (start < 0) {
        return null;
    }
    const addressStart = start + prefix.length;
    const end = value.indexOf(suffix, addressStart);
    if (end < 0) {
        return null;
    }
    const address = value.slice(addressStart, end).trim();
    return address === '' ? null : address;
}

function parsePort(value: string): number {
    const port = Number(value);
    return Number.isInteger(port) && port >= 0 && port <= 65535 ? port : -1;
}

function parseRemoteAddress(remoteAddress: string | null | undefined): Endpoint {
    if (remoteAddress == null || remoteAddress.trim() === '') {
        return {host: UNKNOWN_HOST, port: 0};
    }
    let value = remoteAddress.trim();
    if (value.startsWith('/')) {
        value = value.slice(1);
    }
    if (value.startsWith('[')) {
        const closingBracket = value.indexOf(']');
        if (closingBracket > 1) {
            const host = value.slice(1, closingBracket);
            const portText = value[closingBracket + 1] === ':' ? value.slice(closingBracket + 2) : '';
            const port = portText ? parsePort(portText) : 0;
            return {host, port: port >= 0 ? port : 0};
        }
    }
    const lastColon = value.lastIndexOf(':');
    if (lastColon > 0 && lastColon + 1 < value.length) {
        const host = value.slice(0, lastColon);
        const port = parsePort(value.slice(lastColon + 1));
        if (port >= 0) {
            return {host, port};
        }
    }
    return {host: value, port: 0};
}

function toError(message: string, cause: unknown): Error {
    return cause instanceof Error ? cause : new Error(message, {cause});
}

export class NeoLinkAPI {
    static readonly TransportProtocol = TransportProtocol;

    private readonly cfg: NeoLinkCfg;
    private running = false;
    private closeRequested = false;
    private lifecycleGeneration = 0;
    private activeConnections = new Set<{ close?: () => void; destroy?: () => void }>();

    private runtimeCfg: NeoLinkCfg | null = null;
    private proxyOperator: ProxyOperator | null = null;
    private checkAliveThread: CheckAliveThread | null = null;
    private startupFuture: Deferred<void> | null = null;
    private runtimePromise: Promise<void> | null = null;
    private hookSocket: SecureSocket | null = null;
    private connectingSocket: net.Socket | null = null;
    private updateUrl: string | null = null;
    private tunAddr: string | null = null;
    private tunAddrFuture = new Deferred<string>();
    private lastReceivedTime = Date.now();
    private runtimeConnectToNpsTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS;
    private state = NeoLinkState.STOPPED;
    private pendingProtocolSwitchRollback: ProtocolFlags | null = null;
    private pendingProtocolSwitch: ProtocolSwitch | null = null;
    private lastServerTerminalMessage: string | null = null;

    constructor(cfg: NeoLinkCfg) {
        if (!cfg) {
            throw new TypeError('cfg');
        }
        this.cfg = cfg;
    }

    static version(): string {
        return VERSION;
    }

    static parseTunAddrMessage(message: string | null | undefined): string | null {
        if (message == null || message.trim() === '') {
            return null;
        }
        return extractBetween(message, EN_TUN_ADDR_PREFIX, EN_TUN_ADDR_SUFFIX)
            ?? extractBetween(message, ZH_TUN_ADDR_PREFIX, ZH_TUN_ADDR_SUFFIX);
    }

    static classifyStartupHandshakeFailure(response: string | null | undefined): Error | null {
        if (response == null || response.trim() === '') {
            return new Error('NeoProxyServer returned an empty startup response.');
        }
        if (NeoLinkAPI.isUnsupportedVersionResponse(response)) {
            return new UnSupportHostVersionException(response, response);
        }
        if (NeoLinkAPI.isNoMoreNetworkFlowResponse(response)) {
            return new NoMoreNetworkFlowException(response);
        }
        if (NeoLinkAPI.isOutdatedKeyResponse(response)) {
            return new OutDatedKeyException(response, response);
        }
        if (NeoLinkAPI.isUnrecognizedKeyResponse(response)) {
            return new UnRecognizedKeyException(response, response);
        }
        if (NeoLinkAPI.isRemotePortOccupiedResponse(response)) {
            return new PortOccupiedException(response, response);
        }
        if (NeoLinkAPI.isNoMorePortResponse(response)) {
            return new NoMorePortException(response, response);
        }
        return null;
    }

    static isSuccessfulHandshakeResponse(response: string): boolean {
        return allLanguageData().some((languageData) => equalsProtocolText(response, languageData.connectionBuildUpSuccessfully));
    }

    private static isUnsupportedVersionResponse(response: string): boolean {
        return allLanguageData().some((languageData) => startsWithProtocolText(response, languageData.unsupportedVersionPrefix));
    }

    private static isNoMoreNetworkFlowResponse(response: string): boolean {
        return equalsProtocolText(response, 'exitNoFlow')
            || allLanguageData().some((languageData) => equalsProtocolText(response, languageData.noNetworkFlowLeft));
    }

    private static isOutdatedKeyResponse(response: string): boolean {
        return allLanguageData().some((languageData) =>
            isWrappedProtocolText(response, languageData.keyPrefix, languageData.keyOutdatedSuffix)
            || isWrappedProtocolText(response, languageData.keyAltPrefix, languageData.keyOutdatedSuffix));
    }

    private static isUnrecognizedKeyResponse(response: string): boolean {
        return allLanguageData().some((languageData) => equalsProtocolText(response, languageData.accessDeniedForceExiting));
    }

    private static isRemotePortOccupiedResponse(response: string): boolean {
        return allLanguageData().some((languageData) => equalsProtocolText(response, languageData.remotePortOccupied));
    }

    private static isNoMorePortResponse(response: string): boolean {
        return allLanguageData().some((languageData) => equalsProtocolText(response, languageData.portAlreadyInUse));
    }

    private static classifyRuntimeTerminalFailure(response: string | null): Error | null {
        const startupFailure = NeoLinkAPI.classifyStartupHandshakeFailure(response);
        if (startupFailure) {
            return startupFailure;
        }
        if (equalsProtocolText(response, 'exitNoFlow')) {
            return new NoMoreNetworkFlowException(response ?? undefined);
        }
        return null;
    }

    async start(connectToNpsTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS): Promise<void> {
        const timeoutMillis = requirePositive(connectToNpsTimeoutMillis, 'connectToNpsTimeoutMillis');
        if (this.isActive()) {
            this.debug('start() ignored because this NeoLinkAPI instance is already active.');
            return;
        }

        const generation = ++this.lifecycleGeneration;
        this.resetTunAddrState();
        this.runtimeCfg = this.cfg.copy();
        this.runtimeCfg.requireStartReady();
        this.proxyOperator = new ProxyOperator(
            this.runtimeCfg.getRemoteDomainName(),
            this.runtimeCfg.getLocalDomainName(),
            this.runtimeCfg.getProxyIPToNeoServer(),
            this.runtimeCfg.getProxyIPToLocalServer()
        );
        this.updateUrl = null;
        this.lastServerTerminalMessage = null;
        this.pendingProtocolSwitch = null;
        this.pendingProtocolSwitchRollback = null;
        this.runtimeConnectToNpsTimeoutMillis = timeoutMillis;
        const notifyStarting = this.moveStateTo(NeoLinkState.STARTING);
        this.debug(`Starting NeoLinkAPI tunnel. ${this.describeRuntimeConfig()}`);
        this.startupFuture = new Deferred<void>();
        this.checkAliveThread = new CheckAliveThread(
            () => this.hookSocket,
            () => this.lastReceivedTime,
            this.runtimeCfg.getHeartBeatPacketDelay(),
            (message, cause) => this.emitError(message, cause),
            this.isDebugEnabled(),
            (message, cause) => this.emitDebug(message, cause)
        );
        this.closeRequested = false;
        this.running = true;
        this.runtimePromise = this.runCore(generation);
        if (notifyStarting) {
            this.emitStateChanged(NeoLinkState.STARTING);
        }

        try {
            await this.startupFuture.promise;
            await this.runtimePromise;
        } catch (error) {
            this.close();
            throw error;
        }
    }

    isActive(): boolean {
        return this.running;
    }

    async getTunAddr(): Promise<string> {
        if (this.tunAddr != null) {
            return this.tunAddr;
        }
        while (true) {
            const future = this.tunAddrFuture;
            try {
                return await future.promise;
            } catch (error) {
                if (future !== this.tunAddrFuture) {
                    continue;
                }
                throw error;
            }
        }
    }

    getHookSocket(): SecureSocket | null {
        return this.hookSocket;
    }

    getUpdateURL(): string | null {
        return this.updateUrl;
    }

    getState(): NeoLinkState {
        return this.state;
    }

    setOnStateChanged(handler: (state: NeoLinkState) => void): this {
        this.onStateChanged = handler;
        return this;
    }

    setOnError(handler: (message: string, cause: unknown) => void): this {
        this.onError = handler;
        return this;
    }

    setOnServerMessage(handler: (message: string) => void): this {
        this.onServerMessage = handler;
        return this;
    }

    setUnsupportedVersionDecision(handler: (response: string) => boolean | Promise<boolean>): this {
        this.unsupportedVersionDecision = handler;
        return this;
    }

    setDebugSink(handler: DebugSink): this {
        this.debugSink = handler;
        return this;
    }

    setOnConnect(handler: ConnectionEventHandler): this {
        this.onConnect = handler;
        return this;
    }

    setOnDisconnect(handler: ConnectionEventHandler): this {
        this.onDisconnect = handler;
        return this;
    }

    setOnConnectNeoFailure(handler: () => void): this {
        this.onConnectNeoFailure = handler;
        return this;
    }

    setOnConnectLocalFailure(handler: () => void): this {
        this.onConnectLocalFailure = handler;
        return this;
    }

    async updateRuntimeProtocolFlags(tcpEnabled: boolean, udpEnabled: boolean): Promise<void> {
        const requestedFlags = {tcpEnabled, udpEnabled};
        const activeHookSocket = this.requireActiveHookSocket();
        const activeCfg = this.requireRuntimeCfg();
        const currentFlags = flagsFromCfg(activeCfg);
        if (protocolFlagsEqual(currentFlags, requestedFlags)) {
            this.debug('Ignored runtime protocol-switch request because the requested flags already match.');
            return;
        }
        this.pendingProtocolSwitchRollback = currentFlags;
        this.pendingProtocolSwitch = {
            previous: currentFlags,
            requested: requestedFlags,
            expiresAtMillis: Date.now() + PROTOCOL_SWITCH_GRACE_MILLIS
        };
        await activeHookSocket.sendStr(asProtocolFlags(requestedFlags));
        activeCfg.setTCPEnabled(tcpEnabled);
        activeCfg.setUDPEnabled(udpEnabled);
        this.debug(`Runtime protocol-switch command sent. flags=${asProtocolFlags(requestedFlags)}`);
    }

    close(): void {
        if (this.state !== NeoLinkState.STOPPED) {
            this.transitionTo(NeoLinkState.STOPPING);
        }
        this.debug('close() requested.');
        this.closeRequested = true;
        this.running = false;
        this.checkAliveThread?.stopThread();
        this.closeActiveConnection();
        this.pendingProtocolSwitch = null;
        this.pendingProtocolSwitchRollback = null;
        this.completeStartup(new Error('NeoLinkAPI 启动已取消。'));
    }

    formatClientInfoString(): string {
        const activeCfg = this.requireRuntimeCfg();
        return `${activeCfg.getLanguage()};${activeCfg.getClientVersion()};${activeCfg.getKey()};${asProtocolFlags(flagsFromCfg(activeCfg))}`;
    }

    private onConnect: ConnectionEventHandler = () => undefined;

    private onDisconnect: ConnectionEventHandler = () => undefined;

    private onConnectNeoFailure: () => void = () => undefined;

    private onConnectLocalFailure: () => void = () => undefined;

    private onStateChanged: (state: NeoLinkState) => void = () => undefined;

    private onError: (message: string, cause: unknown) => void = () => undefined;

    private onServerMessage: (message: string) => void = () => undefined;

    private unsupportedVersionDecision: (response: string) => boolean | Promise<boolean> = () => true;

    private debugSink: DebugSink = (message, cause) => {
        if (message) {
            console.debug(message);
        }
        if (cause) {
            console.debug(cause);
        }
    };

    private async runCore(generation: number): Promise<void> {
        try {
            await this.connectToNeoServer();
            await this.exchangeClientInfoWithServer();
            this.checkAliveThread?.startThread();
            this.transitionTo(NeoLinkState.RUNNING);
            this.completeStartup(null, generation);
            await this.listenForServerCommands();
        } catch (error) {
            const runtimeFailure = this.isStartupComplete();
            const exception = error instanceof Error ? error : new Error('NeoLinkAPI 隧道异常停止。', {cause: error});
            this.completeStartup(exception, generation);
            if (exception instanceof UnsupportedVersionException
                || exception instanceof NoSuchKeyException
                || exception instanceof NoMoreNetworkFlowException) {
                this.closeRequested = true;
                this.transitionTo(NeoLinkState.FAILED);
                this.emitError(exception.message, exception);
                if (runtimeFailure) {
                    throw exception;
                }
                return;
            }
            if (this.running && !this.closeRequested) {
                this.transitionTo(NeoLinkState.FAILED);
                this.emitError('NeoLinkAPI 隧道异常停止。', exception);
                this.debug(null, exception);
                if (runtimeFailure) {
                    throw exception;
                }
            }
        } finally {
            this.cleanupLifecycle(generation);
        }
    }

    private async connectToNeoServer(): Promise<void> {
        const activeCfg = this.requireRuntimeCfg();
        const proxyOperator = this.requireProxyOperator();
        if (proxyOperator.hasProxy(TO_NEO)) {
            this.debug(`Connecting to NeoProxyServer hook through per-instance proxy. target=${activeCfg.getRemoteDomainName()}:${activeCfg.getHookPort()}, timeoutMs=${this.runtimeConnectToNpsTimeoutMillis}`);
            this.hookSocket = await proxyOperator.getHandledSecureSocket(TO_NEO, activeCfg.getHookPort(), this.runtimeConnectToNpsTimeoutMillis);
            this.registerConnection(this.hookSocket);
            return;
        }
        const socket = await connectTcp(activeCfg.getRemoteDomainName(), activeCfg.getHookPort(), this.runtimeConnectToNpsTimeoutMillis);
        this.connectingSocket = socket;
        try {
            this.hookSocket = new SecureSocket(socket);
            this.registerConnection(this.hookSocket);
        } finally {
            this.connectingSocket = null;
        }
    }

    private async exchangeClientInfoWithServer(): Promise<void> {
        const activeHookSocket = this.requireActiveHookSocket();
        const clientInfo = this.formatClientInfoString();
        this.debug(`Sending client handshake. value=${this.maskClientInfo(clientInfo)}`);
        await activeHookSocket.sendStr(clientInfo);
        const serverResponse = await activeHookSocket.receiveStr();
        if (serverResponse == null) {
            throw new Error('NeoProxyServer 在握手阶段关闭了连接。');
        }
        this.debug(`Received server handshake response. value=${serverResponse}`);
        const startupFailure = NeoLinkAPI.classifyStartupHandshakeFailure(serverResponse);
        if (startupFailure instanceof UnSupportHostVersionException) {
            await this.handleUnsupportedVersionResponse(serverResponse);
            throw startupFailure;
        }
        if (startupFailure) {
            throw startupFailure;
        }
        if (!NeoLinkAPI.isSuccessfulHandshakeResponse(serverResponse)) {
            throw new Error(`NeoProxyServer rejected the tunnel startup: ${serverResponse}`);
        }
        this.lastReceivedTime = Date.now();
    }

    private async handleUnsupportedVersionResponse(serverResponse: string): Promise<void> {
        this.updateUrl = null;
        let requestUpdate = false;
        try {
            requestUpdate = Boolean(await this.unsupportedVersionDecision(serverResponse));
        } catch (error) {
            this.debug(null, error);
        }
        try {
            const activeHookSocket = this.requireActiveHookSocket();
            await activeHookSocket.sendStr(String(requestUpdate));
            if (!requestUpdate) {
                return;
            }
            await activeHookSocket.sendStr(updateClientType());
            this.updateUrl = normalizeUpdateURL(await activeHookSocket.receiveStr(UPDATE_URL_TIMEOUT_MILLIS));
            this.debug(`Unsupported-version update URL negotiated. available=${this.updateUrl != null}`);
        } catch (error) {
            this.debug('Unable to finish unsupported-version update URL negotiation.');
            this.debug(null, error);
        }
    }

    private async listenForServerCommands(): Promise<void> {
        const activeHookSocket = this.requireActiveHookSocket();
        while (this.running) {
            const message = await activeHookSocket.receiveStr();
            if (message == null) {
                break;
            }
            this.lastReceivedTime = Date.now();
            this.settlePendingProtocolSwitchIfExpired();
            this.debug(`Received server message. value=${message}`);
            if (message.startsWith(':>')) {
                await this.handleServerCommand(message.slice(2));
            } else {
                this.rollbackRuntimeProtocolSwitchIfRejected(message);
                this.captureTerminalServerMessage(message);
                this.captureTunAddrIfPresent(message);
                this.emitServerMessage(message);
                this.debug('Ignored non-command server message.');
            }
        }
        throw new Error('NeoProxyServer 连接已关闭。');
    }

    private async handleServerCommand(command: string): Promise<void> {
        const parts = command.split(';');
        switch (parts[0]) {
            case 'sendSocketTCP':
                if (this.isDispatchEnabled(TransportProtocol.TCP) && parts.length >= 3) {
                    this.debug(`Scheduling TCP tunnel. socketId=${parts[1]}, remoteAddress=${parts[2]}`);
                    void this.createNewTCPConnection(parts[1]!, parts[2]!);
                } else {
                    this.debug('Ignored TCP tunnel command because TCP is disabled or command is malformed.');
                }
                break;
            case 'sendSocketUDP':
                if (this.isDispatchEnabled(TransportProtocol.UDP) && parts.length >= 3) {
                    this.debug(`Scheduling UDP tunnel. socketId=${parts[1]}, remoteAddress=${parts[2]}`);
                    void this.createNewUDPConnection(parts[1]!, parts[2]!);
                } else {
                    this.debug('Ignored UDP tunnel command because UDP is disabled or command is malformed.');
                }
                break;
            case 'exit': {
                const terminalException = NeoLinkAPI.classifyRuntimeTerminalFailure(this.lastServerTerminalMessage);
                this.closeActiveConnection();
                throw terminalException ?? new Error('NeoProxyServer requested tunnel shutdown.');
            }
            case 'exitNoFlow': {
                this.closeRequested = true;
                this.running = false;
                const exception = new NoMoreNetworkFlowException();
                this.emitError(exception.message, exception);
                this.closeActiveConnection();
                throw exception;
            }
            default:
                this.debug(`Ignored unknown server command. value=${parts[0]}`);
        }
    }

    private async createNewTCPConnection(socketId: string, remoteAddress: string): Promise<void> {
        let localServerSocket: net.Socket | null = null;
        let neoTransferSocket: SecureSocket | null = null;
        try {
            const activeCfg = this.requireRuntimeCfg();
            this.debug(`Creating TCP tunnel. socketId=${socketId}, remoteAddress=${remoteAddress}`);
            localServerSocket = await this.openLocalSocket();
            neoTransferSocket = await this.openTransferSocket();
            this.registerConnection(localServerSocket);
            this.registerConnection(neoTransferSocket);
            await neoTransferSocket.sendStr(`TCP;${socketId}`);
            this.emitConnectionEvent(this.onConnect, TransportProtocol.TCP, remoteAddress);
            const debug = (message: string | null, cause?: unknown) => this.emitDebug(message, cause);
            const first = transferTcpNeoToLocal(neoTransferSocket, localServerSocket, activeCfg.isPPV2Enabled(), debug);
            const second = transferTcpLocalToNeo(localServerSocket, neoTransferSocket, debug);
            await Promise.allSettled([first, second]);
        } catch (error) {
            const activeCfg = this.runtimeCfg;
            let exception: Error;
            if (localServerSocket == null) {
                this.runCallback(this.onConnectLocalFailure);
                exception = toError(`连接本地服务失败：${activeCfg?.getLocalDomainName()}:${activeCfg?.getLocalPort()}`, error);
            } else if (neoTransferSocket == null) {
                this.runCallback(this.onConnectNeoFailure);
                exception = toError(`连接 NeoProxyServer 传输端口失败：${activeCfg?.getRemoteDomainName()}:${activeCfg?.getHostConnectPort()}`, error);
            } else {
                exception = toError('创建 TCP 隧道失败。', error);
            }
            this.emitError(exception.message, exception);
        } finally {
            closeQuietly(localServerSocket, neoTransferSocket);
            this.unregisterConnection(localServerSocket, neoTransferSocket);
            this.emitConnectionEvent(this.onDisconnect, TransportProtocol.TCP, remoteAddress);
        }
    }

    private async createNewUDPConnection(socketId: string, remoteAddress: string): Promise<void> {
        let neoTransferSocket: SecureSocket | null = null;
        let datagramSocket: dgram.Socket | null = null;
        try {
            const activeCfg = this.requireRuntimeCfg();
            this.debug(`Creating UDP tunnel. socketId=${socketId}, remoteAddress=${remoteAddress}`);
            neoTransferSocket = await this.openTransferSocket();
            datagramSocket = await createUdpSocket();
            this.registerConnection(neoTransferSocket);
            this.registerConnection(datagramSocket);
            await neoTransferSocket.sendStr(`UDP;${socketId}`);
            this.emitConnectionEvent(this.onConnect, TransportProtocol.UDP, remoteAddress);
            const debug = (message: string | null, cause?: unknown) => this.emitDebug(message, cause);
            const first = transferUdpLocalToNeo(datagramSocket, neoTransferSocket, debug);
            const second = transferUdpNeoToLocal(neoTransferSocket, datagramSocket, activeCfg.getLocalDomainName(), activeCfg.getLocalPort(), debug);
            await Promise.race([first, second]);
            closeQuietly(datagramSocket, neoTransferSocket);
            await Promise.allSettled([first, second]);
        } catch (error) {
            const activeCfg = this.runtimeCfg;
            const exception = neoTransferSocket == null
                ? toError(`连接 NeoProxyServer 传输端口失败：${activeCfg?.getRemoteDomainName()}:${activeCfg?.getHostConnectPort()}`, error)
                : toError('创建 UDP 隧道失败。', error);
            if (neoTransferSocket == null) {
                this.runCallback(this.onConnectNeoFailure);
            }
            this.emitError(exception.message, exception);
        } finally {
            closeQuietly(datagramSocket, neoTransferSocket);
            this.unregisterConnection(datagramSocket, neoTransferSocket);
            this.emitConnectionEvent(this.onDisconnect, TransportProtocol.UDP, remoteAddress);
        }
    }

    private async openLocalSocket(): Promise<net.Socket> {
        const activeCfg = this.requireRuntimeCfg();
        const proxyOperator = this.requireProxyOperator();
        if (proxyOperator.hasProxy(TO_LOCAL)) {
            return proxyOperator.getHandledSocket(TO_LOCAL, activeCfg.getLocalPort(), DEFAULT_CONNECT_TIMEOUT_MILLIS);
        }
        return this.connectToLocalRobustly(activeCfg.getLocalDomainName(), activeCfg.getLocalPort());
    }

    private async connectToLocalRobustly(host: string, port: number): Promise<net.Socket> {
        const addresses = await dns.lookup(host, {all: true});
        let lastException: unknown = null;
        for (const address of addresses) {
            try {
                this.debug(`Trying local resolved address. address=${address.address}, port=${port}, timeoutMs=${DEFAULT_CONNECT_TIMEOUT_MILLIS}`);
                return await connectTcp(address.address, port, DEFAULT_CONNECT_TIMEOUT_MILLIS);
            } catch (error) {
                lastException = error;
                this.debug(`Local resolved address failed. address=${address.address}, port=${port}, error=${error instanceof Error ? error.message : String(error)}`);
            }
        }
        throw lastException instanceof Error ? lastException : new Error(`解析本地域名失败：${host}`);
    }

    private async openTransferSocket(): Promise<SecureSocket> {
        const activeCfg = this.requireRuntimeCfg();
        const proxyOperator = this.requireProxyOperator();
        if (proxyOperator.hasProxy(TO_NEO)) {
            return proxyOperator.getHandledSecureSocket(TO_NEO, activeCfg.getHostConnectPort(), this.runtimeConnectToNpsTimeoutMillis);
        }
        return new SecureSocket(await connectTcp(activeCfg.getRemoteDomainName(), activeCfg.getHostConnectPort(), this.runtimeConnectToNpsTimeoutMillis));
    }

    private registerConnection(connection: { close?: () => void; destroy?: () => void } | null): void {
        if (connection) {
            this.activeConnections.add(connection);
        }
    }

    private unregisterConnection(...connections: Array<{ close?: () => void; destroy?: () => void } | null>): void {
        for (const connection of connections) {
            if (connection) {
                this.activeConnections.delete(connection);
            }
        }
    }

    private closeAllTrackedConnections(): void {
        for (const connection of Array.from(this.activeConnections)) {
            closeQuietly(connection);
            this.unregisterConnection(connection);
        }
    }

    private closeActiveConnection(): void {
        this.debug('Closing active control and transfer connections.');
        closeQuietly(this.connectingSocket, this.hookSocket);
        this.closeAllTrackedConnections();
        this.connectingSocket = null;
        this.hookSocket = null;
    }

    private cleanupLifecycle(generation: number): void {
        if (!this.isCurrentGeneration(generation)) {
            return;
        }
        this.checkAliveThread?.stopThread();
        this.closeActiveConnection();
        this.running = false;
        this.pendingProtocolSwitch = null;
        this.pendingProtocolSwitchRollback = null;
        this.resetTunAddrState();
        if (this.moveStateTo(NeoLinkState.STOPPED)) {
            this.emitStateChanged(NeoLinkState.STOPPED);
        }
    }

    private completeStartup(failure: Error | null, generation = this.lifecycleGeneration): void {
        if (!this.isCurrentGeneration(generation) || !this.startupFuture || this.startupFuture.settled) {
            return;
        }
        if (failure) {
            this.startupFuture.reject(failure);
        } else {
            this.startupFuture.resolve();
        }
    }

    private isStartupComplete(): boolean {
        return Boolean(this.startupFuture?.settled);
    }

    private isCurrentGeneration(generation: number): boolean {
        return this.lifecycleGeneration === generation;
    }

    private emitConnectionEvent(handler: ConnectionEventHandler, protocol: TransportProtocol, remoteAddress: string): void {
        const activeCfg = this.runtimeCfg;
        if (!activeCfg) {
            return;
        }
        try {
            handler(protocol, parseRemoteAddress(remoteAddress), {
                host: activeCfg.getLocalDomainName(),
                port: activeCfg.getLocalPort()
            });
        } catch (error) {
            this.debug(null, error);
        }
    }

    private runCallback(callback: () => void): void {
        try {
            callback();
        } catch (error) {
            this.debug(null, error);
        }
    }

    private emitError(message: string, cause: unknown): void {
        try {
            this.onError(message, cause);
        } catch (error) {
            this.debug(null, error);
        }
        this.debug(`ERROR: ${message}`);
        this.debug(null, cause);
    }

    private emitServerMessage(message: string): void {
        try {
            this.onServerMessage(message);
        } catch (error) {
            this.debug(null, error);
        }
    }

    private captureTunAddrIfPresent(message: string): void {
        const parsedTunAddr = NeoLinkAPI.parseTunAddrMessage(message);
        if (!parsedTunAddr) {
            return;
        }
        this.tunAddr = parsedTunAddr;
        this.tunAddrFuture.resolve(parsedTunAddr);
        this.debug(`Tunnel address received from NeoProxyServer. value=${parsedTunAddr}`);
    }

    private resetTunAddrState(): void {
        const previous = this.tunAddrFuture;
        this.tunAddr = null;
        this.tunAddrFuture = new Deferred<string>();
        if (!previous.settled) {
            void previous.promise.catch(() => undefined);
            previous.reject(new Error('Tunnel address state has been reset.'));
        }
    }

    private captureTerminalServerMessage(message: string): void {
        if (NeoLinkAPI.classifyRuntimeTerminalFailure(message) != null) {
            this.lastServerTerminalMessage = message;
        }
    }

    private isDispatchEnabled(protocol: TransportProtocol): boolean {
        this.settlePendingProtocolSwitchIfExpired();
        const activeCfg = this.requireRuntimeCfg();
        const configuredEnabled = protocol === TransportProtocol.TCP ? activeCfg.isTCPEnabled() : activeCfg.isUDPEnabled();
        if (!this.pendingProtocolSwitch) {
            return configuredEnabled;
        }
        return enabled(this.pendingProtocolSwitch.previous, protocol) || enabled(this.pendingProtocolSwitch.requested, protocol);
    }

    private settlePendingProtocolSwitchIfExpired(): void {
        if (!this.pendingProtocolSwitch || Date.now() < this.pendingProtocolSwitch.expiresAtMillis) {
            return;
        }
        const committed = this.pendingProtocolSwitch.requested;
        this.pendingProtocolSwitch = null;
        this.pendingProtocolSwitchRollback = null;
        this.debug(`Runtime protocol-switch grace window elapsed. committedFlags=${asProtocolFlags(committed)}`);
    }

    private rollbackRuntimeProtocolSwitchIfRejected(message: string): void {
        if (!NeoLinkAPI.isNoMorePortResponse(message) || !this.pendingProtocolSwitchRollback || !this.runtimeCfg) {
            return;
        }
        this.runtimeCfg.setTCPEnabled(this.pendingProtocolSwitchRollback.tcpEnabled);
        this.runtimeCfg.setUDPEnabled(this.pendingProtocolSwitchRollback.udpEnabled);
        this.debug(`Runtime protocol-switch rejected by NeoProxyServer. Rolled local flags back to ${asProtocolFlags(this.pendingProtocolSwitchRollback)}`);
        this.pendingProtocolSwitchRollback = null;
        this.pendingProtocolSwitch = null;
    }

    private transitionTo(newState: NeoLinkState): void {
        if (this.moveStateTo(newState)) {
            this.emitStateChanged(newState);
        }
    }

    private moveStateTo(newState: NeoLinkState): boolean {
        if (this.state === newState) {
            return false;
        }
        this.state = newState;
        return true;
    }

    private emitStateChanged(newState: NeoLinkState): void {
        try {
            this.onStateChanged(newState);
        } catch (error) {
            this.debug(null, error);
        }
    }

    private requireRuntimeCfg(): NeoLinkCfg {
        if (!this.runtimeCfg) {
            throw new Error('NeoLinkAPI runtime configuration is not available.');
        }
        return this.runtimeCfg;
    }

    private requireProxyOperator(): ProxyOperator {
        if (!this.proxyOperator) {
            throw new Error('NeoLinkAPI proxy operator is not available.');
        }
        return this.proxyOperator;
    }

    private requireActiveHookSocket(): SecureSocket {
        if (!this.running || !this.hookSocket || this.hookSocket.isClosed()) {
            throw new Error('NeoLinkAPI control channel is not active.');
        }
        return this.hookSocket;
    }

    private isDebugEnabled(): boolean {
        return this.runtimeCfg ? this.runtimeCfg.isDebugMsg() : this.cfg.isDebugMsg();
    }

    private debug(message: string | null, cause?: unknown): void {
        if (!this.isDebugEnabled()) {
            return;
        }
        this.emitDebug(message, cause);
    }

    private emitDebug(message: string | null, cause?: unknown): void {
        try {
            this.debugSink(message, cause);
        } catch {
            // 调试回调仅用于观测，不应干扰隧道状态。
        }
    }

    private maskClientInfo(clientInfo: string): string {
        const parts = clientInfo.split(';');
        if (parts.length < 4) {
            return clientInfo;
        }
        parts[2] = this.maskSecret(parts[2]!);
        return parts.join(';');
    }

    private maskSecret(secret: string): string {
        if (secret.trim() === '') {
            return '';
        }
        if (secret.length <= 4) {
            return '****';
        }
        return `${secret.slice(0, 2)}****${secret.slice(-2)}`;
    }

    private describeRuntimeConfig(): string {
        const activeCfg = this.requireRuntimeCfg();
        return `remote=${activeCfg.getRemoteDomainName()}, hookPort=${activeCfg.getHookPort()}, connectPort=${activeCfg.getHostConnectPort()}, local=${activeCfg.getLocalDomainName()}:${activeCfg.getLocalPort()}, language=${activeCfg.getLanguage()}, tcpEnabled=${activeCfg.isTCPEnabled()}, udpEnabled=${activeCfg.isUDPEnabled()}, ppv2Enabled=${activeCfg.isPPV2Enabled()}, heartbeatDelayMs=${activeCfg.getHeartBeatPacketDelay()}, proxyToNeo=${activeCfg.getProxyIPToNeoServer().trim() === '' ? 'direct' : 'configured'}, proxyToLocal=${activeCfg.getProxyIPToLocalServer().trim() === '' ? 'direct' : 'configured'}, connectToNpsTimeoutMs=${this.runtimeConnectToNpsTimeoutMillis}, connectToLocalTimeoutMs=${DEFAULT_CONNECT_TIMEOUT_MILLIS}`;
    }
}

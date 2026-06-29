import {NeoLinkCfg, requirePort, requireText} from './neo-link-cfg.js';

function normalizeOptionalText(value: unknown): string | null {
    if (value == null || String(value).trim() === '') {
        return null;
    }
    return String(value).trim();
}

export class NeoNode {
    private readonly name: string;
    private readonly realId: string | null;
    private readonly address: string;
    private readonly iconSvg: string | null;
    private readonly hookPort: number;
    private readonly connectPort: number;

    constructor(name: string, realId: string | null, address: string, iconSvg: string | null, hookPort: number, connectPort: number) {
        this.name = requireText(name, 'name');
        this.realId = normalizeOptionalText(realId);
        this.address = requireText(address, 'address');
        this.iconSvg = normalizeOptionalText(iconSvg);
        this.hookPort = requirePort(hookPort, 'hookPort');
        this.connectPort = requirePort(connectPort, 'connectPort');
    }

    toCfg(key: string, localPort: number): NeoLinkCfg {
        return new NeoLinkCfg(this.address, this.hookPort, this.connectPort, key, localPort);
    }

    getName(): string {
        return this.name;
    }

    getRealId(): string | null {
        return this.realId;
    }

    getAddress(): string {
        return this.address;
    }

    getIconSvg(): string | null {
        return this.iconSvg;
    }

    getHookPort(): number {
        return this.hookPort;
    }

    getConnectPort(): number {
        return this.connectPort;
    }

    equals(other: unknown): boolean {
        return other instanceof NeoNode
            && this.name === other.name
            && this.realId === other.realId
            && this.address === other.address
            && this.iconSvg === other.iconSvg
            && this.hookPort === other.hookPort
            && this.connectPort === other.connectPort;
    }

    toString(): string {
        return `NeoNode{name='${this.name}', realId='${this.realId}', address='${this.address}', iconSvg=${this.iconSvg == null ? 'null' : '<svg>'}, hookPort=${this.hookPort}, connectPort=${this.connectPort}}`;
    }
}

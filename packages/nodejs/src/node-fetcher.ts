import { NeoNode } from './neo-node';

export const DEFAULT_TIMEOUT_MILLIS = 1000;
export const DEFAULT_HOST_HOOK_PORT = 44801;
export const DEFAULT_HOST_CONNECT_PORT = 44802;

function ioError(message: string, cause?: unknown): Error {
  return new Error(message, { cause });
}

function parseEndpoint(url: string): URL {
  if (url == null || String(url).trim() === '') {
    throw new TypeError('url must not be blank.');
  }
  const endpoint = new URL(String(url).trim());
  if (endpoint.protocol !== 'http:' && endpoint.protocol !== 'https:') {
    throw new TypeError('url must use http or https.');
  }
  return endpoint;
}

function readText(item: Record<string, unknown>, ...aliases: string[]): string | null {
  for (const alias of aliases) {
    const value = item[alias];
    if (typeof value === 'string') {
      return value.trim();
    }
  }
  return null;
}

function parseIntegralPort(rawValue: unknown, fieldName: string): number {
  const normalized = String(rawValue).trim();
  if (!/^[+-]?\d+$/.test(normalized)) {
    throw ioError(`Invalid port value for ${fieldName}: ${rawValue}`);
  }
  const port = Number(normalized);
  if (!Number.isSafeInteger(port)) {
    throw ioError(`Invalid port value for ${fieldName}: ${rawValue}`);
  }
  return port;
}

function readPort(item: Record<string, unknown>, defaultValue: number, ...aliases: string[]): number {
  for (const alias of aliases) {
    const value = item[alias];
    if (value == null) {
      continue;
    }
    if (typeof value !== 'number' && typeof value !== 'string') {
      throw ioError(`Invalid port value type for ${alias}.`);
    }
    const port = parseIntegralPort(value, alias);
    if (port < 1 || port > 65535) {
      throw ioError(`Port out of range for ${alias}: ${port}`);
    }
    return port;
  }
  return defaultValue;
}

function isBlank(value: string | null): boolean {
  return value == null || value.trim() === '';
}

export function parseNodeMap(json: string): Map<string, NeoNode> {
  let root: unknown;
  try {
    if (json == null) {
      throw new TypeError('json');
    }
    root = JSON.parse(String(json));
  } catch (cause) {
    throw ioError('NKM node-list JSON is invalid.', cause);
  }
  if (!Array.isArray(root)) {
    throw ioError('NKM node-list root must be a JSON array.');
  }

  const result = new Map<string, NeoNode>();
  for (const entry of root) {
    if (entry == null || Array.isArray(entry) || typeof entry !== 'object') {
      throw ioError('NKM node-list contains a non-object entry.');
    }
    const item = entry as Record<string, unknown>;
    const realId = readText(item, 'realId', 'realid');
    const name = readText(item, 'name');
    const address = readText(item, 'address');
    if (isBlank(realId) || isBlank(name) || isBlank(address)) {
      throw ioError('NKM node-list entry must contain non-blank realId, name and address.');
    }
    const iconSvg = readText(item, 'icon', 'iconSvg');
    const hookPort = readPort(item, DEFAULT_HOST_HOOK_PORT, 'HOST_HOOK_PORT', 'hookPort');
    const connectPort = readPort(item, DEFAULT_HOST_CONNECT_PORT, 'HOST_CONNECT_PORT', 'connectPort');
    const parsed = new NeoNode(name!, realId, address!, iconSvg, hookPort, connectPort);
    if (result.has(realId!)) {
      throw ioError(`NKM node-list contains duplicate realId: ${realId}`);
    }
    result.set(realId!, parsed);
  }
  return result;
}

export async function getFromNKM(url: string, timeoutMillis = DEFAULT_TIMEOUT_MILLIS): Promise<Map<string, NeoNode>> {
  const endpoint = parseEndpoint(url);
  if (!Number.isInteger(Number(timeoutMillis)) || Number(timeoutMillis) < 1) {
    throw new RangeError('timeoutMillis must be greater than 0.');
  }
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), Number(timeoutMillis));
  let response: Response;
  try {
    response = await fetch(endpoint, {
      method: 'GET',
      redirect: 'follow',
      signal: controller.signal
    });
  } catch (error) {
    throw ioError(`Invalid NKM node-list URL: ${url}`, error);
  } finally {
    clearTimeout(timeout);
  }
  if (response.status !== 200) {
    throw ioError(`NKM node-list request failed with HTTP status ${response.status}.`);
  }
  return parseNodeMap(await response.text());
}

export const NodeFetcher = Object.freeze({
  DEFAULT_TIMEOUT_MILLIS,
  DEFAULT_HOST_HOOK_PORT,
  DEFAULT_HOST_CONNECT_PORT,
  getFromNKM,
  parseNodeMap
});

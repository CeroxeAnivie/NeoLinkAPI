export interface LanguageData {
  readonly connectionBuildUpSuccessfully: string;
  readonly remotePortOccupied: string;
  readonly portAlreadyInUse: string;
  readonly accessDeniedForceExiting: string;
  readonly noNetworkFlowLeft: string;
  readonly unsupportedVersionPrefix: string;
  readonly keyPrefix: string;
  readonly keyAltPrefix: string;
  readonly keyOutdatedSuffix: string;
}

export const ENGLISH: LanguageData = Object.freeze({
  connectionBuildUpSuccessfully: 'Connection build up successfully',
  remotePortOccupied: 'Connection rejected: Port occupied by another node or limit reached.',
  portAlreadyInUse: 'This port is already in use. Please try with a different node.',
  accessDeniedForceExiting: 'Access denied , force exiting...',
  noNetworkFlowLeft: 'This key have no network flow left ! Force exiting...',
  unsupportedVersionPrefix: 'Unsupported version ! It should be :',
  keyPrefix: 'Key ',
  keyAltPrefix: 'The key ',
  keyOutdatedSuffix: ' are out of date.'
});

export const CHINESE: LanguageData = Object.freeze({
  connectionBuildUpSuccessfully: '服务器连接成功',
  remotePortOccupied: '连接被拒绝：该端口已被其他节点占用，或已达到最大允许连接数。',
  portAlreadyInUse: '这个端口已经被占用了，请你更换节点重试。',
  accessDeniedForceExiting: '密钥错误，强制退出。。。',
  noNetworkFlowLeft: '这个密钥已经没有流量了，强制退出。。。',
  unsupportedVersionPrefix: '不受支持的版本，应该为',
  keyPrefix: '密钥 ',
  keyAltPrefix: '这个密钥 ',
  keyOutdatedSuffix: ' 已经过期了。'
});

const ALL = Object.freeze([ENGLISH, CHINESE]);

export function allLanguageData(): LanguageData[] {
  return ALL.slice();
}

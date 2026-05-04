export class NeoLinkError extends Error {
  constructor(message?: string, options?: ErrorOptions) {
    super(message, options);
    this.name = new.target.name;
  }
}

export class ServerResponseError extends NeoLinkError {
  readonly serverResponse: string | null;

  constructor(message: string | null | undefined, serverResponse?: string | null, options?: ErrorOptions) {
    super(message ?? serverResponse ?? undefined, options);
    this.serverResponse = serverResponse ?? null;
  }
}

export class UnsupportedVersionException extends ServerResponseError {}

export class UnSupportHostVersionException extends UnsupportedVersionException {}

export class NoSuchKeyException extends ServerResponseError {}

export class OutDatedKeyException extends NoSuchKeyException {}

export class UnRecognizedKeyException extends NoSuchKeyException {}

export class NoMoreNetworkFlowException extends ServerResponseError {
  constructor(serverResponse = 'exitNoFlow', options?: ErrorOptions) {
    super('No more network flow left.', serverResponse, options);
  }
}

export class NoMorePortException extends ServerResponseError {}

export class PortOccupiedException extends ServerResponseError {}

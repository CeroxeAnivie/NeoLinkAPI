export interface Endpoint {
    host: string;
    port: number;
}

export type DebugSink = (message: string | null, cause?: unknown) => void;

export type ErrorHandler = (message: string, cause: unknown) => void;

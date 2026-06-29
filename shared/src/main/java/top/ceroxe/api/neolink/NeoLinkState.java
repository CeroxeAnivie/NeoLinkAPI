package top.ceroxe.api.neolink;

/**
 * NeoLinkAPI tunnel lifecycle state.
 *
 * <p>The API exposes explicit states so host applications can drive UI,
 * supervision, retry, and cleanup logic from deterministic lifecycle events
 * instead of inferring health from socket side effects.</p>
 */
public enum NeoLinkState {
    /**
     * No tunnel is active and all API-owned resources have been released.
     */
    STOPPED,

    /**
     * The control connection is being established and the startup handshake is
     * not yet complete.
     */
    STARTING,

    /**
     * The startup handshake completed and the tunnel is listening for server
     * commands.
     */
    RUNNING,

    /**
     * A caller requested shutdown and the API is closing sockets, heartbeat
     * checks, and transfer workers.
     */
    STOPPING,

    /**
     * The tunnel hit a terminal runtime failure. A following {@link #STOPPED}
     * event means cleanup has completed.
     */
    FAILED
}

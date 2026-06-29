package top.ceroxe.api.neolink.transparency;

/**
 * 在单个 JVM 中串起 API 透明性校验 server 与 client，保证双击脚本时能直接在同一控制台看到实时输出。
 *
 * <p>之所以单独做这个 runner，而不是继续用批处理管理两个独立 Java 进程，是因为 cmd 在
 * “后台进程实时输出 + 就绪探测 + 捕获 TUNAddr + 最终汇总” 这个场景下非常脆弱，稍有分支调整
 * 就会退化成临时日志文件、轮询等待和肉眼看起来像卡死的体验。</p>
 */
public final class APITransparencyRunner {
    private static final String DEFAULT_REMOTE_DOMAIN = "p.ceroxe.top";
    private static final int DEFAULT_HOOK_PORT = 44801;
    private static final int DEFAULT_CONNECT_PORT = 44802;
    private static final int DEFAULT_LOCAL_PORT = 7777;
    private static final String DEFAULT_LOCAL_BIND_HOST = "127.0.0.1";

    private APITransparencyRunner() {
    }

    public static void main(String[] args) throws Exception {
        RuntimeArgs runtimeArgs = RuntimeArgs.parse(args);

        System.out.println("[启动] 正在启动透明性校验 server...");
        try (APITransparencyServer.RunningServer server = APITransparencyServer.startForCheck(
                runtimeArgs.remoteDomain(),
                runtimeArgs.hookPort(),
                runtimeArgs.connectPort(),
                runtimeArgs.accessKey(),
                runtimeArgs.localPort(),
                runtimeArgs.localBindHost())) {
            System.out.println("[server] 使用的 remote domain = " + runtimeArgs.remoteDomain());
            System.out.println("[server] 使用的 hook port = " + runtimeArgs.hookPort());
            System.out.println("[server] 使用的 connect port = " + runtimeArgs.connectPort());
            System.out.println("[server] 本地 echo 已监听 tcp/udp: " + runtimeArgs.localBindHost() + ":" + runtimeArgs.localPort());
            System.out.println("[结果] TUNAddr = " + server.tunAddr());
            System.out.println("[测试] 正在执行透明性校验，请稍候...");

            int clientExit = APITransparencyClient.runChecks(server.tunAddr());
            System.out.println("[退出码] client exit = " + clientExit);
            if (clientExit != 0) {
                System.exit(clientExit);
            }
        }
    }

    private static IllegalArgumentException usage(String reason) {
        return new IllegalArgumentException(reason + System.lineSeparator()
                + "用法: APITransparencyRunner [remoteDomain] [hookPort] [connectPort] [accessKey] [localPort] [localBindHost]" + System.lineSeparator()
                + "示例: APITransparencyRunner p.ceroxe.top 44801 44802 YOUR_KEY 7777 127.0.0.1");
    }

    private record RuntimeArgs(String remoteDomain, int hookPort, int connectPort, String accessKey, int localPort,
                               String localBindHost) {
        private static RuntimeArgs parse(String[] args) {
            if (args.length > 6) {
                throw usage("too many arguments");
            }

            String remoteDomain = textOrDefault(args, 0, DEFAULT_REMOTE_DOMAIN);
            int hookPort = portOrDefault(args, 1, DEFAULT_HOOK_PORT, "hookPort");
            int connectPort = portOrDefault(args, 2, DEFAULT_CONNECT_PORT, "connectPort");
            String accessKey = requireText(args, 3, "accessKey");
            int localPort = portOrDefault(args, 4, DEFAULT_LOCAL_PORT, "localPort");
            String localBindHost = textOrDefault(args, 5, DEFAULT_LOCAL_BIND_HOST);
            return new RuntimeArgs(remoteDomain, hookPort, connectPort, accessKey, localPort, localBindHost);
        }

        private static String requireText(String[] args, int index, String fieldName) {
            if (args.length <= index || args[index] == null || args[index].isBlank()) {
                throw usage(fieldName + " must not be blank");
            }
            return args[index].trim();
        }

        private static String textOrDefault(String[] args, int index, String defaultValue) {
            if (args.length <= index) {
                return defaultValue;
            }
            String value = args[index];
            return value == null || value.isBlank() ? defaultValue : value.trim();
        }

        private static int portOrDefault(String[] args, int index, int defaultValue, String fieldName) {
            if (args.length <= index || args[index] == null || args[index].isBlank()) {
                return defaultValue;
            }
            try {
                int port = Integer.parseInt(args[index].trim());
                if (port < 1 || port > 65_535) {
                    throw usage(fieldName + " must be in 1..65535");
                }
                return port;
            } catch (NumberFormatException e) {
                throw usage(fieldName + " must be an integer in 1..65535");
            }
        }
    }
}

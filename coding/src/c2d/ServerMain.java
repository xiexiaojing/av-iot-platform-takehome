package c2d;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 指令下发服务入口：内存态设备 + 指令，HTTP/JSON 接口。
 *
 * <p>仅使用 JDK 自带的 com.sun.net.httpserver，保证在没有 Maven/Gradle、没有外网的环境下也能编译运行。
 */
public final class ServerMain {

    private ServerMain() {
    }

    public static void main(String[] args) throws IOException {
        String host = "127.0.0.1";
        int port = 8080;
        long ackMs = 50L;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--ack-ms" -> ackMs = Long.parseLong(args[++i]);
                default -> {
                    // 忽略未知参数
                }
            }
        }

        CommandService service = new CommandService(ackMs);
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 128);
        ExecutorService pool = Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "c2d-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);

        server.createContext("/healthz", ex -> respond(ex, 200, Json.obj("status", "ok")));

        server.createContext("/devices", ex -> handleDevices(ex, service));
        server.createContext("/commands", ex -> handleCommands(ex, service));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            pool.shutdownNow();
            service.close();
        }));

        server.start();
        System.out.println("c2d-server listening on " + host + ":" + server.getAddress().getPort());
    }

    private static void handleDevices(HttpExchange ex, CommandService service) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if (method.equals("PUT") && path.startsWith("/devices/") && path.endsWith("/online")) {
            String deviceId = path.substring("/devices/".length(), path.length() - "/online".length());
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean online = !"false".equalsIgnoreCase(Json.parseFlat(body).getOrDefault("online", "true"));
            service.setDeviceOnline(deviceId, online);
            respond(ex, 200, Json.obj("device_id", deviceId, "online", online));
            return;
        }
        if (method.equals("GET") && path.startsWith("/devices/")) {
            String deviceId = path.substring("/devices/".length());
            Device device = service.getDevice(deviceId);
            if (device == null) {
                respond(ex, 404, Json.obj("error", "device_not_found"));
                return;
            }
            respond(ex, 200, Json.obj("device_id", device.id(), "online", device.online(),
                    "updated_ms", device.updatedMs()));
            return;
        }
        respond(ex, 404, Json.obj("error", "not_found", "path", path));
    }

    private static void handleCommands(HttpExchange ex, CommandService service) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if (method.equals("POST") && path.equals("/commands")) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> in = Json.parseFlat(body);
            String deviceId = in.getOrDefault("device_id", "");
            String action = in.getOrDefault("action", "");
            String idem = in.getOrDefault("idempotency_key", "");
            long timeout = 5000L;
            try {
                timeout = Long.parseLong(in.getOrDefault("timeout_ms", "5000"));
            } catch (NumberFormatException ignored) {
                // 非法 timeout 使用默认值
            }
            CommandService.DispatchResult r = service.dispatch(deviceId, action, idem, timeout);
            if (r.conflict()) {
                respond(ex, 409, Json.obj("error", "idempotency_conflict", "message", r.message()));
                return;
            }
            if (!r.ok()) {
                respond(ex, 400, Json.obj("error", "invalid_request", "message", r.message()));
                return;
            }
            // 受理 != 成功：这里返回受理结果与当前状态，终态需轮询查询。
            respond(ex, 202, commandJson(r.command(), r.replay()));
            return;
        }
        if (method.equals("GET") && path.startsWith("/commands/")) {
            String commandId = path.substring("/commands/".length());
            Command c = service.getCommand(commandId);
            if (c == null) {
                respond(ex, 404, Json.obj("error", "command_not_found"));
                return;
            }
            respond(ex, 200, commandJson(c, false));
            return;
        }
        respond(ex, 404, Json.obj("error", "not_found", "path", path));
    }

    private static String commandJson(Command c, boolean replay) {
        return Json.obj(
                "command_id", c.id(),
                "device_id", c.deviceId(),
                "action", c.action(),
                "status", c.status(),
                "error", c.error(),
                "error_message", c.errorMessage(),
                "idempotent_replay", replay,
                "created_ms", c.createdMs(),
                "updated_ms", c.updatedMs(),
                "deadline_ms", c.deadlineMs());
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}

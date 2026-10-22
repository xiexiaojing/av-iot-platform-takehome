package c2d;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** c2d-cli：命令行客户端，供 smoke.sh 与人工验证使用。 */
public final class CliMain {

    private CliMain() {
    }

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 8080;
        java.util.List<String> rest = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                default -> rest.add(args[i]);
            }
        }
        if (rest.isEmpty()) {
            usage();
            System.exit(2);
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String base = "http://" + host + ":" + port;

        try {
            switch (rest.get(0)) {
                case "device" -> {
                    if (rest.size() < 3) {
                        usage();
                        System.exit(2);
                    }
                    String sub = rest.get(1);
                    String id = rest.get(2);
                    if (sub.equals("online") || sub.equals("offline")) {
                        String body = "{\"online\":" + sub.equals("online") + "}";
                        HttpResponse<String> r = send(client, base + "/devices/" + id + "/online",
                                "PUT", body);
                        check(r);
                    } else if (sub.equals("status")) {
                        HttpResponse<String> r = send(client, base + "/devices/" + id, "GET", null);
                        check(r);
                    } else {
                        usage();
                        System.exit(2);
                    }
                }
                case "cmd" -> {
                    if (rest.size() < 3) {
                        usage();
                        System.exit(2);
                    }
                    String sub = rest.get(1);
                    if (sub.equals("dispatch")) {
                        if (rest.size() < 4) {
                            System.err.println("缺少 device_id 或 action");
                            System.exit(1);
                        }
                        String deviceId = rest.get(2);
                        String action = rest.get(3);
                        String idem = "";
                        long timeout = 5000L;
                        for (int i = 4; i < rest.size(); i++) {
                            if (rest.get(i).equals("--idem") && i + 1 < rest.size()) {
                                idem = rest.get(++i);
                            } else if (rest.get(i).equals("--timeout-ms") && i + 1 < rest.size()) {
                                timeout = Long.parseLong(rest.get(++i));
                            }
                        }
                        if (idem.isBlank()) {
                            System.err.println("必须提供 --idem（幂等键）");
                            System.exit(1);
                        }
                        String body = Json.obj("device_id", deviceId, "action", action,
                                "idempotency_key", idem, "timeout_ms", timeout);
                        HttpResponse<String> r = send(client, base + "/commands", "POST", body);
                        if (r.statusCode() == 409) {
                            System.err.println("幂等键冲突: " + r.body());
                            System.exit(1);
                        }
                        check(r);
                    } else if (sub.equals("status")) {
                        HttpResponse<String> r = send(client, base + "/commands/" + rest.get(2),
                                "GET", null);
                        check(r);
                    } else {
                        usage();
                        System.exit(2);
                    }
                }
                default -> {
                    usage();
                    System.exit(2);
                }
            }
        } catch (Exception e) {
            System.err.println("请求失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static HttpResponse<String> send(HttpClient client, String url, String method, String body)
            throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void check(HttpResponse<String> r) {
        if (r.statusCode() >= 400) {
            System.err.println("HTTP " + r.statusCode() + " " + r.body());
            System.exit(1);
        }
        System.out.println(r.body());
    }

    private static void usage() {
        System.err.println("usage: c2d-cli [--host H] [--port P] <command>\n"
                + "  device online <device_id>\n"
                + "  device offline <device_id>\n"
                + "  device status <device_id>\n"
                + "  cmd dispatch <device_id> <action> --idem <key> [--timeout-ms N]\n"
                + "  cmd status <command_id>");
    }
}

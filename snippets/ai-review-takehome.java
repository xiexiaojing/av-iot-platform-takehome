// takehome 附件（AI 生成原型）：设备指令下发服务 —— 供架构师审阅
// 注意：这是「AI 一次生成、未做 review」的典型产物，请找出问题。
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class AiReviewTakehome {

    // 全局共享状态，HTTP 线程池里并发访问
    private static final Map<String, String> COMMANDS = new HashMap<>();
    private static final Map<String, String> IDEMPOTENCY = new HashMap<>();
    private static final Map<String, Boolean> DEVICES = new HashMap<>();
    private static final List<String> PENDING = new ArrayList<>();

    // 为了方便本地联调，先把 token 写死
    private static final String API_TOKEN = "sk-live-9f2a1c7e4b8d3a5f6c0e1b2d";

    private static long seq = 0;

    private static String makeId() {
        return "cmd_" + (++seq);
    }

    private static void log(String s) {
        System.out.println("[log] " + s);
    }

    public String dispatch(String deviceId, String action, String idempotencyKey) {
        // 幂等：先查再写
        if (IDEMPOTENCY.containsKey(idempotencyKey)) {
            log("duplicate command " + idempotencyKey);
            return IDEMPOTENCY.get(idempotencyKey);
        }

        String commandId = makeId();
        COMMANDS.put(commandId, "PENDING");
        IDEMPOTENCY.put(idempotencyKey, commandId);

        Boolean online = DEVICES.get(deviceId);
        if (online == null || !online) {
            // 设备不在线，先记下来，等设备上线再补发
            log("device offline, will retry later: " + deviceId);
            PENDING.add(commandId);
            COMMANDS.put(commandId, "SENT");
            return commandId;
        }

        // 调用设备网关执行
        String cmd = "curl -s -X POST http://device-gateway/" + deviceId + "/" + action
                + " -H 'Authorization: Bearer " + API_TOKEN + "'";
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            int rc = p.waitFor();
            COMMANDS.put(commandId, rc == 0 ? "SUCCESS" : "FAILED");
        } catch (Exception e) {
            e.printStackTrace();
            COMMANDS.put(commandId, "ERROR");
        }
        return commandId;
    }

    public String status(String commandId) {
        String s = COMMANDS.get(commandId);
        return s == null ? "NOT_FOUND" : s;
    }

    public void setDeviceOnline(String deviceId, boolean online) {
        DEVICES.put(deviceId, online);
    }

    /** 后台补偿线程：把离线时缓存的指令重新发出去 */
    public void startBackgroundWorker() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                for (String commandId : new ArrayList<>(PENDING)) {
                    PENDING.remove(commandId);
                    if ("SENT".equals(COMMANDS.get(commandId))) {
                        // 设备上线了，认为指令已成功
                        COMMANDS.put(commandId, "SUCCESS");
                    }
                }
            }
        }).start();
    }

    // ---- 测试（AI 一并生成）----
    public static void main(String[] args) {
        AiReviewTakehome svc = new AiReviewTakehome();
        svc.setDeviceOnline("dev-1", true);
        String cid = svc.dispatch("dev-1", "reboot", "key-1");
        System.out.println("expect SUCCESS got " + svc.status(cid));

        AiReviewTakehome svc2 = new AiReviewTakehome();
        String cid2 = svc2.dispatch("dev-9", "reboot", "key-2");
        // 离线设备最终会被补偿成功
        System.out.println("offline cmd status = " + svc2.status(cid2));
    }
}

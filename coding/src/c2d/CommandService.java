package c2d;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 设备在线状态 + 指令下发的内存实现。
 *
 * <p>对应架构文档 2.A 的四条硬约束：
 * <ol>
 *   <li><b>幂等</b>：idempotency_key -> command_id 是确定性映射（SHA-256 前 16 位十六进制），
 *       并通过 {@code ConcurrentHashMap.computeIfAbsent} 保证「同键只创建一次」的原子性，
 *       杜绝「先查后写」的 check-then-act 竞态；</li>
 *   <li><b>显式失败</b>：设备离线时指令直接进入 FAILED/DEVICE_OFFLINE，不静默成功、不抛 NPE；</li>
 *   <li><b>不无限挂起</b>：每条 PENDING 指令都有 deadline，由调度线程兜底置为 FAILED/TIMEOUT；</li>
 *   <li><b>线程安全</b>：共享状态全部放在 ConcurrentHashMap，状态流转用整体替换（CAS 语义）。</li>
 * </ol>
 */
public final class CommandService implements AutoCloseable {

    public static final long DEFAULT_TIMEOUT_MS = 5_000L;
    public static final long MIN_TIMEOUT_MS = 100L;
    public static final long MAX_TIMEOUT_MS = 600_000L;

    /** 指令结果。 */
    public record DispatchResult(boolean ok, boolean replay, boolean conflict,
                                 Command command, String message) {
    }

    private final Map<String, Command> commands = new ConcurrentHashMap<>();
    /** 幂等键 -> 该键对应的指令（用 Future 承载，确保同键只创建一次）。 */
    private final Map<String, CompletableFuture<Command>> idempotency = new ConcurrentHashMap<>();
    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final long ackDelayMs;

    public CommandService() {
        this(50L);
    }

    public CommandService(long ackDelayMs) {
        this.ackDelayMs = ackDelayMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "c2d-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleWithFixedDelay(this::sweep, 25L, 25L, TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- 设备

    public void setDeviceOnline(String deviceId, boolean online) {
        devices.put(deviceId, new Device(deviceId, online, System.currentTimeMillis()));
    }

    public Device getDevice(String deviceId) {
        return devices.get(deviceId);
    }

    // ---------------------------------------------------------------- 指令

    public DispatchResult dispatch(String deviceId, String action, String idempotencyKey,
                                   long timeoutMs) {
        if (isBlank(deviceId) || isBlank(action) || isBlank(idempotencyKey)) {
            return new DispatchResult(false, false, false, null,
                    "device_id / action / idempotency_key 均不能为空");
        }
        long timeout = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : Math.min(Math.max(timeoutMs, MIN_TIMEOUT_MS), MAX_TIMEOUT_MS);
        String fingerprint = deviceId + "|" + action;

        // 原子地「占坑」：同一个幂等键只有一个线程能进入 createNewCommand，
        // 其余线程等待同一个 Future，保证不会出现两条命令。
        AtomicBoolean createdHere = new AtomicBoolean(false);
        CompletableFuture<Command> future = idempotency.computeIfAbsent(idempotencyKey, key -> {
            createdHere.set(true);   // 只有「创建者」线程会执行这段映射函数
            CompletableFuture<Command> created = new CompletableFuture<>();
            try {
                created.complete(createNewCommand(deviceId, action, key, fingerprint, timeout));
            } catch (RuntimeException e) {
                created.completeExceptionally(e);
            }
            return created;
        });

        Command cmd;
        try {
            cmd = future.join();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new DispatchResult(false, false, false, null, "指令创建失败: " + cause.getMessage());
        }

        if (!cmd.fingerprint().equals(fingerprint)) {
            // 同一幂等键被用于不同参数：显式拒绝，不能静默复用既有指令。
            return new DispatchResult(false, true, true, cmd, "idempotency_key 已被不同参数占用");
        }
        // 幂等回放：本次没有创建新指令，而是复用了既有指令。
        boolean replay = !createdHere.get();
        return new DispatchResult(true, replay, false, cmd, "");
    }

    private Command createNewCommand(String deviceId, String action, String idempotencyKey,
                                      String fingerprint, long timeoutMs) {
        long now = System.currentTimeMillis();
        String id = makeCommandId(idempotencyKey);
        Device device = devices.get(deviceId);
        boolean online = device != null && device.online();

        Command cmd = new Command(id, deviceId, action, idempotencyKey,
                online ? Command.PENDING : Command.FAILED,
                online ? "" : "DEVICE_OFFLINE",
                online ? "" : "设备不在线，指令未被执行",
                now, now, now + timeoutMs, now + ackDelayMs, fingerprint);
        commands.put(id, cmd);
        return cmd;
    }

    public Command getCommand(String commandId) {
        return commands.get(commandId);
    }

    public int commandCount() {
        return commands.size();
    }

    // ---------------------------------------------------------------- 内部

    /** 定时扫描：把到期的 PENDING 指令置为终态，保证不会无限挂起。 */
    private void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Command> entry : commands.entrySet()) {
            Command c = entry.getValue();
            if (!Command.PENDING.equals(c.status())) {
                continue;
            }
            if (now >= c.deadlineMs()) {
                // 超时优先于回执：宁可失败也不能报成功。
                replace(entry.getKey(), c.withStatus(Command.FAILED, "TIMEOUT",
                        "指令超时未收到设备回执", now));
            } else if (now >= c.readyMs()) {
                Device device = devices.get(c.deviceId());
                if (device != null && device.online()) {
                    replace(entry.getKey(), c.withStatus(Command.SUCCESS, "", "", now));
                } else {
                    replace(entry.getKey(), c.withStatus(Command.FAILED, "DEVICE_OFFLINE",
                            "设备在指令下发后掉线，指令未被执行", now));
                }
            }
        }
    }

    /** 状态流转用整体替换实现 CAS：只有当前状态仍是 PENDING 时才写入。 */
    private void replace(String commandId, Command candidate) {
        commands.compute(commandId, (k, existing) -> {
            if (existing == null || !Command.PENDING.equals(existing.status())) {
                return existing;
            }
            return candidate;
        });
    }

    /** 幂等键 -> command_id 的确定性映射：同一键永远得到同一 id。 */
    public static String makeCommandId(String idempotencyKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            return "cmd_" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}

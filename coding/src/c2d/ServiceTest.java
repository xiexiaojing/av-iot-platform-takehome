package c2d;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自动化测试：不依赖 JUnit，用最朴素的自制断言，方便在离线环境一键跑。
 * 运行：java -cp build/classes c2d.ServiceTest
 */
public final class ServiceTest {

    private static int cases = 0;
    private static int failures = 0;

    private static void check(boolean condition, String what) {
        cases++;
        if (condition) {
            System.out.println("  [ok]   " + what);
        } else {
            failures++;
            System.out.println("  [FAIL] " + what);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 1) 幂等：同一 idempotency_key 重复下发返回同一 command_id。 */
    private static void testIdempotency() {
        System.out.println("[case] 幂等映射");
        try (CommandService svc = new CommandService(10L)) {
            svc.setDeviceOnline("dev-1", true);
            CommandService.DispatchResult r1 = svc.dispatch("dev-1", "reboot", "key-A", 1000L);
            CommandService.DispatchResult r2 = svc.dispatch("dev-1", "reboot", "key-A", 1000L);
            check(r1.ok() && r2.ok(), "两次下发都被受理");
            check(r1.command().id().equals(r2.command().id()), "同一幂等键返回同一 command_id");
            check(!r1.replay() && r2.replay(), "第二次被标记为幂等回放");
            check(svc.commandCount() == 1, "内存中只产生一条指令");
        }
    }

    /** 2) 幂等键冲突：同键不同参数必须显式报错。 */
    private static void testIdempotencyConflict() {
        System.out.println("[case] 幂等键冲突");
        try (CommandService svc = new CommandService(10L)) {
            svc.setDeviceOnline("dev-1", true);
            svc.dispatch("dev-1", "reboot", "key-B", 1000L);
            CommandService.DispatchResult r = svc.dispatch("dev-1", "shutdown", "key-B", 1000L);
            check(r.conflict(), "同键不同参数返回冲突");
            check(svc.commandCount() == 1, "冲突不产生新指令");
        }
    }

    /** 3) 离线设备必须明确失败，而不是静默成功或抛 NPE。 */
    private static void testOfflineFailsExplicitly() {
        System.out.println("[case] 设备离线");
        try (CommandService svc = new CommandService(10L)) {
            CommandService.DispatchResult r = svc.dispatch("dev-x", "reboot", "key-C", 1000L);
            check(r.ok(), "请求被受理（返回 command_id）");
            check(Command.FAILED.equals(r.command().status()), "指令状态为 FAILED");
            check("DEVICE_OFFLINE".equals(r.command().error()), "错误码为 DEVICE_OFFLINE");
            check(!r.command().errorMessage().isBlank(), "带有可读错误信息");
        }
    }

    /** 4) 在线设备最终成功；超时必须兜底，绝不无限 PENDING。 */
    private static void testSuccessAndTimeout() {
        System.out.println("[case] 成功与超时");
        try (CommandService svc = new CommandService(10L)) {
            svc.setDeviceOnline("dev-2", true);
            CommandService.DispatchResult r = svc.dispatch("dev-2", "restart", "key-D", 2000L);
            check(Command.PENDING.equals(r.command().status()), "刚下发时为 PENDING");
            Command c = null;
            for (int i = 0; i < 50; i++) {
                sleep(20);
                c = svc.getCommand(r.command().id());
                if (c != null && c.terminal()) {
                    break;
                }
            }
            check(c != null && Command.SUCCESS.equals(c.status()), "回执到达后变为 SUCCESS");
        }
        // ack 延迟远大于超时时间：必须被 TIMEOUT 兜底。
        try (CommandService slow = new CommandService(100_000L)) {
            slow.setDeviceOnline("dev-3", true);
            CommandService.DispatchResult r = slow.dispatch("dev-3", "reboot", "key-E", 200L);
            Command c = null;
            for (int i = 0; i < 50; i++) {
                sleep(20);
                c = slow.getCommand(r.command().id());
                if (c != null && c.terminal()) {
                    break;
                }
            }
            check(c != null && Command.FAILED.equals(c.status()) && "TIMEOUT".equals(c.error()),
                    "超时被置为 FAILED/TIMEOUT");
        }
    }

    /** 5) 并发：50 个线程用同一幂等键并发下发，只能有一个 command_id。 */
    private static void testConcurrentIdempotency() throws Exception {
        System.out.println("[case] 并发幂等");
        try (CommandService svc = new CommandService(10L)) {
            svc.setDeviceOnline("dev-4", true);
            int threads = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<String> ids = new ArrayList<>();
            AtomicInteger okCount = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        CommandService.DispatchResult r =
                                svc.dispatch("dev-4", "reboot", "key-concurrent", 1000L);
                        if (r.ok()) {
                            okCount.incrementAndGet();
                            synchronized (ids) {
                                ids.add(r.command().id());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            boolean allSame = ids.stream().distinct().count() == 1;
            check(okCount.get() == threads, "全部请求都被受理");
            check(allSame, "并发下 command_id 完全一致");
            check(svc.commandCount() == 1, "并发下只落一条指令");
        }
    }

    public static void main(String[] args) throws Exception {
        testIdempotency();
        testIdempotencyConflict();
        testOfflineFailsExplicitly();
        testSuccessAndTimeout();
        testConcurrentIdempotency();
        System.out.println();
        System.out.println(cases + " 个断言, " + failures + " 个失败");
        System.exit(failures == 0 ? 0 : 1);
    }
}

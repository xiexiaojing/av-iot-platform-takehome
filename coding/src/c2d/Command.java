package c2d;

/**
 * 一条指令的不可变快照。
 *
 * <p>状态流转通过在 Map 中整体替换对象实现（CAS 语义），避免共享可变字段带来的可见性问题。
 */
public record Command(
        String id,
        String deviceId,
        String action,
        String idempotencyKey,
        String status,
        String error,
        String errorMessage,
        long createdMs,
        long updatedMs,
        long deadlineMs,
        long readyMs,
        String fingerprint) {

    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    public boolean terminal() {
        return SUCCESS.equals(status) || FAILED.equals(status);
    }

    public Command withStatus(String newStatus, String newError, String newMessage, long ts) {
        return new Command(id, deviceId, action, idempotencyKey, newStatus, newError, newMessage,
                createdMs, ts, deadlineMs, readyMs, fingerprint);
    }
}

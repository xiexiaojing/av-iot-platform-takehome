package c2d;

/** 设备在线状态快照。 */
public record Device(String id, boolean online, long updatedMs) {
}

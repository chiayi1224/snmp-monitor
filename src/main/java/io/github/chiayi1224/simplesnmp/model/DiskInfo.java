package io.github.chiayi1224.simplesnmp.model;

/**
 * 單一掛載點的磁碟資訊
 */
public class DiskInfo {

    private final String mountPoint;   // 例如 "/", "/home", "/data"
    private final long   totalBytes;
    private final long   usedBytes;

    public DiskInfo(String mountPoint, long totalBytes, long usedBytes) {
        this.mountPoint = mountPoint;
        this.totalBytes = totalBytes;
        this.usedBytes  = usedBytes;
    }

    public String getMountPoint() { return mountPoint; }
    public long   getTotalBytes() { return totalBytes; }
    public long   getUsedBytes()  { return usedBytes; }
    public long   getFreeBytes()  { return totalBytes - usedBytes; }

    /** 使用率 0.0 ~ 100.0 */
    public double getUsedPercent() {
        if (totalBytes == 0) return 0.0;
        return (double) usedBytes / totalBytes * 100.0;
    }

    /** 人類可讀的容量，例如 "234.5 GB" */
    public static String humanReadable(long bytes) {
        if (bytes >= 1_073_741_824L)
            return String.format("%.1f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L)
            return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%.1f KB", bytes / 1_024.0);
    }

    @Override
    public String toString() {
        return String.format("%-20s %8s / %8s  (%.1f%%)",
            mountPoint,
            humanReadable(usedBytes),
            humanReadable(totalBytes),
            getUsedPercent());
    }
}

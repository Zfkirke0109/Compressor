package android.os;

/** Host replacement for the SDK stub, whose clock is a native method: the JVM's monotonic clock. */
public final class SystemClock {
    private SystemClock() { }

    public static long elapsedRealtime() { return System.nanoTime() / 1_000_000L; }

    public static long elapsedRealtimeNanos() { return System.nanoTime(); }

    public static long uptimeMillis() { return System.nanoTime() / 1_000_000L; }

    public static long currentThreadTimeMillis() { return System.nanoTime() / 1_000_000L; }
}

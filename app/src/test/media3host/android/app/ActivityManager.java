package android.app;

/** Host replacement for the SDK stub, which throws: the build machine is not a test harness. */
public class ActivityManager {
    public static boolean isRunningInTestHarness() { return false; }

    public static boolean isRunningInUserTestHarness() { return false; }

    public static boolean isUserAMonkey() { return false; }
}

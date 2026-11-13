package android.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Host replacement for the SDK stub, which throws. Warnings and errors are kept, bounded, for a
 * test to read: Media3 says in its log when it changes a timestamp.
 */
public final class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    private static final int KEPT = 256;
    private static final List<String> kept = new ArrayList<>();

    private Log() { }

    private static synchronized int keep(String tag, String message) {
        if (kept.size() == KEPT) kept.remove(0);
        kept.add(tag + ": " + message);
        return 0;
    }

    /** What was logged as a warning or an error since the last call, oldest first. */
    public static synchronized List<String> drain() {
        List<String> copy = new ArrayList<>(kept);
        kept.clear();
        return copy;
    }

    public static int v(String tag, String message) { return 0; }
    public static int v(String tag, String message, Throwable error) { return 0; }
    public static int d(String tag, String message) { return 0; }
    public static int d(String tag, String message, Throwable error) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int i(String tag, String message, Throwable error) { return 0; }
    public static int w(String tag, String message) { return keep(tag, message); }
    public static int w(String tag, String message, Throwable error) { return keep(tag, message + " (" + error + ")"); }
    public static int w(String tag, Throwable error) { return keep(tag, String.valueOf(error)); }
    public static int e(String tag, String message) { return keep(tag, message); }
    public static int e(String tag, String message, Throwable error) { return keep(tag, message + " (" + error + ")"); }
    public static boolean isLoggable(String tag, int level) { return level >= WARN; }
    public static String getStackTraceString(Throwable error) { return String.valueOf(error); }
}

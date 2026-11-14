package android.os;

/**
 * Host replacement for the SDK stub, whose strings are null (Media3 compares them on first use).
 * The values name the build machine, not a device, and the SDK level is the one the app is
 * compiled against.
 */
public class Build {
    public static final String DEVICE = "host";
    public static final String MODEL = "host";
    public static final String MANUFACTURER = "host";
    public static final String FINGERPRINT = "host";
    public static final String HARDWARE = "host";
    public static final String BRAND = "host";
    public static final String PRODUCT = "host";
    public static final String ID = "host";
    public static final String TAGS = "";
    public static final String TYPE = "user";

    public static class VERSION {
        public static final int SDK_INT = 36;
        public static final String RELEASE = "16";
        public static final String CODENAME = "REL";
    }
}

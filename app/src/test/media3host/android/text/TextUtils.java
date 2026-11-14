package android.text;

/** Host replacement for the SDK stub, which throws: the two helpers the parser path uses. */
public class TextUtils {
    public static boolean isEmpty(CharSequence text) { return text == null || text.length() == 0; }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null || a.length() != b.length()) return false;
        return a.toString().contentEquals(b);
    }
}

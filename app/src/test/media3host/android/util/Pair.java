package android.util;

import java.util.Objects;

/** Host replacement for the SDK stub, which throws. See README.md in src/test/media3host. */
public class Pair<F, S> {
    public final F first;
    public final S second;

    public Pair(F first, S second) {
        this.first = first;
        this.second = second;
    }

    public static <A, B> Pair<A, B> create(A a, B b) { return new Pair<>(a, b); }

    @Override public boolean equals(Object other) {
        return other instanceof Pair && Objects.equals(((Pair<?, ?>) other).first, first) && Objects.equals(((Pair<?, ?>) other).second, second);
    }

    @Override public int hashCode() { return Objects.hashCode(first) ^ Objects.hashCode(second); }
}

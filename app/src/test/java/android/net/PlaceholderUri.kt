package android.net

import android.os.Parcel

/**
 * A Uri for JVM unit tests that only need to carry one: the android.jar stubs leave `Uri.EMPTY`
 * null and give Uri a package-private constructor, so the subclass has to live in this package.
 * Every accessor fails loudly; a test that needs a real Uri belongs on a device.
 */
class PlaceholderUri(private val label: String = "test:placeholder") : Uri() {
    private fun unsupported(): Nothing = throw UnsupportedOperationException("PlaceholderUri($label) is not a real Uri")
    override fun buildUpon(): Builder = unsupported()
    override fun getAuthority(): String? = unsupported()
    override fun getEncodedAuthority(): String? = unsupported()
    override fun getEncodedFragment(): String? = unsupported()
    override fun getEncodedPath(): String? = unsupported()
    override fun getEncodedQuery(): String? = unsupported()
    override fun getEncodedSchemeSpecificPart(): String = unsupported()
    override fun getEncodedUserInfo(): String? = unsupported()
    override fun getFragment(): String? = unsupported()
    override fun getHost(): String? = unsupported()
    override fun getLastPathSegment(): String? = unsupported()
    override fun getPath(): String? = unsupported()
    override fun getPathSegments(): MutableList<String> = unsupported()
    override fun getPort(): Int = unsupported()
    override fun getQuery(): String? = unsupported()
    override fun getScheme(): String? = unsupported()
    override fun getSchemeSpecificPart(): String = unsupported()
    override fun getUserInfo(): String? = unsupported()
    override fun isHierarchical(): Boolean = unsupported()
    override fun isRelative(): Boolean = unsupported()
    override fun toString(): String = label
    override fun equals(other: Any?): Boolean = other is PlaceholderUri && other.label == label
    override fun hashCode(): Int = label.hashCode()
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = unsupported()
}

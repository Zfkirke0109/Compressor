package compress.joshattic.us

import android.net.PlaceholderUri
import android.net.Uri

/** Uris for JVM unit tests that only carry one around; see [PlaceholderUri]. */
object TestUris {
    val placeholder: Uri = PlaceholderUri()
    fun named(label: String): Uri = PlaceholderUri(label)
}

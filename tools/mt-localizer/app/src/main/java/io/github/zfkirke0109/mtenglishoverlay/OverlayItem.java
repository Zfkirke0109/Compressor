package io.github.zfkirke0109.mtenglishoverlay;

import android.graphics.Rect;

final class OverlayItem {
    final Rect bounds;
    final String source;
    final String english;

    OverlayItem(Rect bounds, String source, String english) {
        this.bounds = new Rect(bounds);
        this.source = source;
        this.english = english;
    }

    String key() {
        return bounds.flattenToString() + "|" + source;
    }
}

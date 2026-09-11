package com.clipsync.shizuku;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IClipUserService {
    void destroy() = 16777114;
    String getClipboardText() = 1;
    void setClipboardText(String text) = 2;
    int getClipboardHash() = 3;
    String getClipboardMime() = 4;
    String getClipboardUri() = 5;
    void setClipboardUri(String uri, String mime) = 6;
    Bundle getClipboardSnapshot() = 7;
    ParcelFileDescriptor openClipboardImage(String expectedIdentity) = 8;
}

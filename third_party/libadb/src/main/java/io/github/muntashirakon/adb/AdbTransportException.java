// SPDX-License-Identifier: Apache-2.0
// Added for Scrcpy Remote; see third_party/libadb/PATCHES.txt.

package io.github.muntashirakon.adb;

import java.io.IOException;

/** Transport loss before the stream received the peer's normal close. */
public final class AdbTransportException extends IOException {
    public AdbTransportException() {
        super("ADB transport ended before stream EOF.");
    }
}

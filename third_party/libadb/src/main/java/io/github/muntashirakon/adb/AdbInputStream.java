// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
// Modified for Scrcpy Remote; changes are listed in third_party/libadb/PATCHES.txt.

package io.github.muntashirakon.adb;

import java.io.IOException;
import java.io.InputStream;

public class AdbInputStream extends InputStream {
    public AdbStream mAdbStream;

    public AdbInputStream(AdbStream adbStream) {
        this.mAdbStream = adbStream;
    }

    @Override
    public int read() throws IOException {
        byte[] bytes = new byte[1];
        if (read(bytes) == -1) {
            return -1;
        }
        return bytes[0] & 0xff;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) return 0;
        return mAdbStream.read(b, off, len);
    }

    @Override
    public void close() {
    }

    @Override
    public int available() throws IOException {
        return mAdbStream.available();
    }
}

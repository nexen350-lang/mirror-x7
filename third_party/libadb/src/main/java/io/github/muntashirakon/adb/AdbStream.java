// SPDX-License-Identifier: BSD-3-Clause AND (GPL-3.0-or-later OR Apache-2.0)
// Modified for Scrcpy Remote; changes are listed in third_party/libadb/PATCHES.txt.

package io.github.muntashirakon.adb;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class abstracts the underlying ADB streams
 */
// Copyright 2013 Cameron Gutman
public class AdbStream implements Closeable {

    /**
     * The AdbConnection object that the stream communicates over
     */
    private final AdbConnection mAdbConnection;

    /**
     * The local ID of the stream
     */
    private final int mLocalId;

    /**
     * The remote ID of the stream
     */
    private volatile int mRemoteId;

    /**
     * Indicates whether WRTE is currently allowed
     */
    private final AtomicBoolean mWriteReady;
    private final ReentrantLock mWriteLock = new ReentrantLock();

    /**
     * A queue of data from the target's WRTE packets
     */
    private final Queue<byte[]> mReadQueue;

    /**
     * Bytes a peer may have queued on this stream before the reader takes them. A peer sends the
     * next packet only once the last one was acknowledged, except that adbd may send a stream's
     * last output early, as a shell command ends; a peer ignoring acknowledgement altogether
     * still fails the transport rather than growing memory without bound.
     */
    private static final int MAX_QUEUED_BYTES = 2 * 1024 * 1024;

    /** The bytes in {@link #mReadQueue}; guarded by it. */
    private int mQueuedBytes;
    /**
     * Store data received from the first WRTE packet in order to support buffering.
     */
    private final ByteBuffer mReadBuffer;

    /**
     * Indicates whether the connection is closed already
     */
    private volatile boolean mIsClosed;

    /**
     * Whether the remote peer has closed but we still have unread data in the queue
     */
    private volatile boolean mPendingClose;
    private volatile boolean mClosedByPeer;

    /**
     * Creates a new AdbStream object on the specified AdbConnection
     * with the given local ID.
     *
     * @param adbConnection AdbConnection that this stream is running on
     * @param localId       Local ID of the stream
     */
    AdbStream(AdbConnection adbConnection, int localId)
            throws IOException, InterruptedException, AdbPairingRequiredException {
        this.mAdbConnection = adbConnection;
        this.mLocalId = localId;
        this.mReadQueue = new ConcurrentLinkedQueue<>();
        this.mReadBuffer = (ByteBuffer) ByteBuffer.allocate(adbConnection.getMaxData()).flip();
        this.mWriteReady = new AtomicBoolean(false);
        this.mIsClosed = false;
    }

    public AdbInputStream openInputStream() {
        return new AdbInputStream(this);
    }

    public AdbOutputStream openOutputStream() {
        return new AdbOutputStream(this);
    }

    /**
     * Called by the connection thread to indicate newly received data.
     *
     * @param payload Data inside the WRTE message
     */
    void addPayload(byte[] payload) throws IOException {
        synchronized (mReadQueue) {
            if (mQueuedBytes + (long) payload.length > MAX_QUEUED_BYTES) {
                throw new IOException("ADB peer sent more unacknowledged output than this client keeps.");
            }
            mReadQueue.add(payload);
            mQueuedBytes += payload.length;
            mReadQueue.notifyAll();
        }
    }

    /**
     * Called by the connection thread to send an OKAY packet, allowing the
     * other side to continue transmission.
     *
     * @throws IOException If the connection fails while sending the packet
     */
    void sendReady() throws IOException {
        if (mIsClosed || mPendingClose) return;
        // Generate and send a OKAY packet
        mAdbConnection.sendPacket(AdbProtocol.generateReady(mLocalId, mRemoteId));
    }

    /**
     * Called by the connection thread to update the remote ID for this stream
     *
     * @param remoteId New remote ID
     */
    void updateRemoteId(int remoteId) {
        this.mRemoteId = remoteId;
    }

    boolean isOpenAcknowledged() {
        return mRemoteId != 0;
    }

    /**
     * Called by the connection thread to indicate the stream is okay to send data.
     */
    void readyForWrite() {
        mWriteReady.set(true);
    }

    /**
     * Called by the connection thread to notify that the stream was closed by the peer.
     */
    void notifyClose(boolean closedByPeer) {
        if (closedByPeer) {
            mClosedByPeer = true;
        }
        // We don't call close() because it sends another CLSE
        if (closedByPeer && !mReadQueue.isEmpty()) {
            // The remote peer closed the stream, but we haven't finished reading the remaining data
            mPendingClose = true;
        } else {
            mIsClosed = true;
        }

        // Notify readers and writers
        synchronized (this) {
            notifyAll();
        }
        synchronized (mReadQueue) {
            mReadQueue.notifyAll();
        }
    }

    /**
     * Read bytes from the ADB daemon.
     *
     * @return The number of bytes read, or zero for an empty read.
     * @throws IOException If the stream fails or reaches the peer's end of stream.
     */
    public int read(byte[] bytes, int offset, int length) throws IOException {
        checkBounds(bytes, offset, length);
        if (length == 0) return 0;
        final int count;
        // Buffer has no data, grab from the queue
        synchronized (mReadQueue) {
            if (mReadBuffer.hasRemaining()) {
                return readBuffer(bytes, offset, length);
            }
            byte[] data;
            // Wait for the connection to close or data to be received
            while ((data = mReadQueue.poll()) == null && !mIsClosed && !mPendingClose) {
                try {
                    mReadQueue.wait();
                } catch (InterruptedException e) {
                    //noinspection UnnecessaryInitCause
                    throw (IOException) new IOException().initCause(e);
                }
            }
            if (data == null) {
                mIsClosed = true;
                throw closedException();
            }
            mQueuedBytes -= data.length;
            mReadBuffer.clear();
            mReadBuffer.put(data);
            mReadBuffer.flip();
            count = readBuffer(bytes, offset, length);
        }

        // Keep one queued packet beside the reader's buffer, without holding its lock over I/O.
        sendReady();
        return count;
    }

    private int readBuffer(byte[] bytes, int offset, int length) {
        int count = Math.min(length, mReadBuffer.remaining());
        mReadBuffer.get(bytes, offset, count);
        return count;
    }

    private static void checkBounds(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || length > bytes.length - offset) {
            throw new IndexOutOfBoundsException();
        }
    }

    private IOException closedException() {
        // One CLSE wakes a channel's reader and writer together, so both report it the same way;
        // otherwise whichever thread noticed first would decide whether the loss is recoverable.
        return mClosedByPeer ? new IOException("Stream closed.") : new AdbTransportException();
    }

    /**
     * Sends a WRTE packet with a given byte array payload. It does not flush the stream.
     *
     * @param bytes Payload in the form of a byte array
     * @throws IOException If the stream fails while sending data
     */
    public void write(byte[] bytes, int offset, int length) throws IOException {
        checkBounds(bytes, offset, length);
        if (length == 0) return;
        // An ended connection no longer answers getMaxData() with an IOException, so a write that
        // arrives after the loss must report it before asking.
        if (mIsClosed || mPendingClose) throw closedException();
        int maxData;
        try {
            maxData = mAdbConnection.getMaxData();
        } catch (InterruptedException | AdbPairingRequiredException e) {
            //noinspection UnnecessaryInitCause
            throw (IOException) new IOException().initCause(e);
        }
        try {
            mWriteLock.lockInterruptibly();
        } catch (InterruptedException e) {
            throw new IOException("Interrupted while waiting for another ADB write.", e);
        }
        try {
            while (length > 0) {
                synchronized (this) {
                    while (!mIsClosed && !mPendingClose && !mWriteReady.compareAndSet(true, false)) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            throw new IOException("Interrupted while waiting for ADB write acknowledgement.", e);
                        }
                    }
                    if (mIsClosed || mPendingClose) {
                        throw closedException();
                    }
                }
                int count = Math.min(length, maxData);
                if (count <= 0) {
                    throw new IOException("ADB peer announced an unusable maximum payload size: " + maxData);
                }
                mAdbConnection.sendPacket(AdbProtocol.generateWrite(mLocalId, mRemoteId, bytes, offset, count));
                offset += count;
                length -= count;
            }
        } finally {
            mWriteLock.unlock();
        }
    }

    public void flush() throws IOException {
        if (mIsClosed) {
            throw closedException();
        }
        mAdbConnection.flushPacket();
    }

    /**
     * Closes the stream. This sends a close message to the peer.
     *
     * @throws IOException If the stream fails while sending the close message.
     */
    @Override
    public void close() throws IOException {
        synchronized (this) {
            mAdbConnection.unregisterStream(mLocalId, this);
            // This may already be closed by the remote host
            if (mIsClosed)
                return;

            // Notify readers/writers that we've closed
            notifyClose(false);
        }

        mAdbConnection.sendPacket(AdbProtocol.generateClose(mLocalId, mRemoteId));
    }

    /**
     * Returns whether the stream is closed or not
     *
     * @return True if the stream is close, false if not
     */
    public boolean isClosed() {
        return mIsClosed;
    }

    /**
     * Returns an estimate of available data.
     *
     * @return an estimate of the number of bytes that can be read from this stream without blocking.
     * @throws IOException if the stream is close.
     */
    public int available() throws IOException {
        synchronized (mReadQueue) {
            if (mReadBuffer.hasRemaining()) {
                return mReadBuffer.remaining();
            }
            byte[] data = mReadQueue.peek();
            if (data != null) return data.length;
            if (mIsClosed) throw closedException();
            return 0;
        }
    }
}

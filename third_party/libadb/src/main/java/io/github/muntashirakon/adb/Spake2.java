// SPDX-License-Identifier: Apache-2.0
// Copyright 2016 The BoringSSL Authors: the SPAKE2 protocol, its M and N points and its key schedule,
// from crypto/curve25519/spake25519.cc. Ported to Java for Scrcpy Remote; see
// third_party/libadb/PATCHES.txt.

package io.github.muntashirakon.adb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.math.ec.rfc7748.X25519Field;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.security.auth.Destroyable;

/**
 * SPAKE2 over edwards25519, compatible with BoringSSL's {@code SPAKE2_*} functions, which adbd
 * uses to pair with a client.
 *
 * <p>Field arithmetic is Bouncy Castle's constant-time {@link X25519Field}. Every scalar
 * multiplication is a Montgomery ladder over all 256 bits using the complete twisted Edwards
 * addition law and conditional swaps, so its sequence of operations does not depend on secret
 * scalars. Only the decoding of the peer's public message takes variable time.
 */
public final class Spake2 implements Destroyable {
    public enum Role { ALICE, BOB }

    public static final int MESSAGE_SIZE = 32;
    public static final int KEY_SIZE = 64;

    /** BoringSSL's M and N, as its source states their encodings. */
    static final byte[] M_ENCODED = hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e");
    static final byte[] N_ENCODED = hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778");
    /** The standard base point: y = 4/5 with even x. */
    private static final byte[] B_ENCODED = hex("5866666666666666666666666666666666666666666666666666666666666666");

    private static final BigInteger FIELD_PRIME = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    /** The order of the prime-order subgroup, l. */
    private static final BigInteger ORDER = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final int[] D = fieldConstant(BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(FIELD_PRIME)).mod(FIELD_PRIME));
    private static final int[] D2 = fieldConstant(BigInteger.valueOf(-121665).shiftLeft(1)
            .multiply(BigInteger.valueOf(121666).modInverse(FIELD_PRIME)).mod(FIELD_PRIME));
    private static final Point B = decodeOrFail(B_ENCODED);
    private static final Point M = decodeOrFail(M_ENCODED);
    private static final Point N = decodeOrFail(N_ENCODED);
    private static final SecureRandom RANDOM = new SecureRandom();

    private enum State { INIT, MESSAGE_GENERATED, KEY_GENERATED, DESTROYED }

    private final Role mRole;
    private final byte[] mMyName;
    private final byte[] mTheirName;
    private final boolean mPasswordScalarFix;
    private final byte[] mPrivateKey = new byte[32];
    private final byte[] mPasswordScalar = new byte[32];
    private final byte[] mPasswordHash = new byte[64];
    private final byte[] mMyMessage = new byte[MESSAGE_SIZE];
    private State mState = State.INIT;

    public Spake2(@NonNull Role role, @NonNull byte[] myName, @NonNull byte[] theirName) {
        this(role, myName, theirName, true);
    }

    private Spake2(Role role, byte[] myName, byte[] theirName, boolean passwordScalarFix) {
        mRole = role;
        mMyName = myName.clone();
        mTheirName = theirName.clone();
        mPasswordScalarFix = passwordScalarFix;
    }

    /** A peer without BoringSSL's 2017 password-scalar fix, which interoperates with one that has it. */
    @NonNull
    static Spake2 withoutPasswordScalarFix(@NonNull Role role, @NonNull byte[] myName, @NonNull byte[] theirName) {
        return new Spake2(role, myName, theirName, false);
    }

    /** The message to send to the peer: a fresh ephemeral point masked by the password. */
    @NonNull
    public synchronized byte[] generateMessage(@NonNull byte[] password) {
        if (mState != State.INIT) throw new IllegalStateException("A SPAKE2 context generates one message");
        byte[] wide = new byte[64];
        RANDOM.nextBytes(wide);
        System.arraycopy(reduce(wide), 0, mPrivateKey, 0, 32);
        Arrays.fill(wide, (byte) 0);
        // Multiplied by the cofactor so the peer's small-order components vanish later.
        leftShift3(mPrivateKey);
        Point p = new Point();
        scalarMult(mPrivateKey, B, p);

        SHA512Digest sha = new SHA512Digest();
        sha.update(password, 0, password.length);
        sha.doFinal(mPasswordHash, 0);
        System.arraycopy(passwordScalar(mPasswordHash, mPasswordScalarFix), 0, mPasswordScalar, 0, 32);

        Point mask = new Point();
        scalarMult(mPasswordScalar, mRole == Role.ALICE ? M : N, mask);
        add(p, mask, p);
        encode(p, mMyMessage);
        mState = State.MESSAGE_GENERATED;
        return mMyMessage.clone();
    }

    /**
     * The shared key from the peer's message, or null when that message is not a point. The
     * key agrees with the peer's only if both used the same password and names.
     */
    @Nullable
    public synchronized byte[] processMessage(@NonNull byte[] theirMessage) {
        if (mState != State.MESSAGE_GENERATED) {
            throw new IllegalStateException("A SPAKE2 context processes one message, after generating its own");
        }
        if (theirMessage.length != MESSAGE_SIZE) return null;
        Point q = new Point();
        if (!decode(theirMessage, q)) return null;

        Point peersMask = new Point();
        scalarMult(mPasswordScalar, mRole == Role.ALICE ? N : M, peersMask);
        negate(peersMask);
        add(q, peersMask, q);
        Point shared = new Point();
        scalarMult(mPrivateKey, q, shared);
        byte[] sharedEncoded = new byte[32];
        encode(shared, sharedEncoded);

        SHA512Digest sha = new SHA512Digest();
        if (mRole == Role.ALICE) {
            updateWithLength(sha, mMyName);
            updateWithLength(sha, mTheirName);
            updateWithLength(sha, mMyMessage);
            updateWithLength(sha, theirMessage);
        } else {
            updateWithLength(sha, mTheirName);
            updateWithLength(sha, mMyName);
            updateWithLength(sha, theirMessage);
            updateWithLength(sha, mMyMessage);
        }
        updateWithLength(sha, sharedEncoded);
        updateWithLength(sha, mPasswordHash);
        byte[] key = new byte[KEY_SIZE];
        sha.doFinal(key, 0);
        Arrays.fill(sharedEncoded, (byte) 0);
        mState = State.KEY_GENERATED;
        return key;
    }

    @Override
    public synchronized boolean isDestroyed() {
        return mState == State.DESTROYED;
    }

    @Override
    public synchronized void destroy() {
        Arrays.fill(mPrivateKey, (byte) 0);
        Arrays.fill(mPasswordScalar, (byte) 0);
        Arrays.fill(mPasswordHash, (byte) 0);
        mState = State.DESTROYED;
    }

    /** The encoding of [scalar] (32 bytes, little-endian) times the base point. */
    @NonNull
    static byte[] scalarMultBase(@NonNull byte[] scalar) {
        Point r = new Point();
        scalarMult(scalar, B, r);
        byte[] out = new byte[32];
        encode(r, out);
        return out;
    }

    /** The affine x and y of an encoded point, or null when it is not one. */
    @Nullable
    static BigInteger[] affine(@NonNull byte[] encoded) {
        Point p = new Point();
        if (encoded.length != 32 || !decode(encoded, p)) return null;
        byte[] x = new byte[32];
        byte[] y = new byte[32];
        X25519Field.normalize(p.x);
        X25519Field.normalize(p.y);
        X25519Field.encode(p.x, x, 0);
        X25519Field.encode(p.y, y, 0);
        return new BigInteger[]{littleEndian(x), littleEndian(y)};
    }

    /**
     * The password hash reduced modulo l. With the fix, l, 2l and 4l are added as needed to make it
     * a multiple of eight, so the mask it gives lies in the prime-order subgroup; each addition is a
     * masked add rather than a branch.
     */
    @NonNull
    static byte[] passwordScalar(@NonNull byte[] passwordHash, boolean withFix) {
        byte[] reduced = reduce(passwordHash);
        if (!withFix) return reduced;
        int[] scalar = words(reduced);
        int[] order = words(toLittleEndian(ORDER));
        for (int bit = 0; bit < 3; bit++) {
            int mask = -((scalar[0] >>> bit) & 1);
            long carry = 0;
            for (int i = 0; i < 8; i++) {
                carry += (scalar[i] & 0xffffffffL) + (order[i] & mask & 0xffffffffL);
                scalar[i] = (int) carry;
                carry >>>= 32;
            }
            carry = 0;
            for (int i = 0; i < 8; i++) {
                carry += (order[i] & 0xffffffffL) << 1;
                order[i] = (int) carry;
                carry >>>= 32;
            }
        }
        return bytes(scalar);
    }

    // ---- edwards25519 in extended coordinates: x = X/Z, y = Y/Z, x*y = T/Z ----

    private static final class Point {
        final int[] x = X25519Field.create();
        final int[] y = X25519Field.create();
        final int[] z = X25519Field.create();
        final int[] t = X25519Field.create();

        Point() {
            X25519Field.one(y);
            X25519Field.one(z);
        }

        void set(Point other) {
            X25519Field.copy(other.x, 0, x, 0);
            X25519Field.copy(other.y, 0, y, 0);
            X25519Field.copy(other.z, 0, z, 0);
            X25519Field.copy(other.t, 0, t, 0);
        }
    }

    /**
     * r = p + q by the complete addition law for a = -1 (Hisil, Wong, Carter and Dawson), which
     * also doubles. r may be p or q.
     */
    private static void add(Point p, Point q, Point r) {
        int[] pSum = X25519Field.create(), pDiff = X25519Field.create();
        int[] qSum = X25519Field.create(), qDiff = X25519Field.create();
        int[] a = X25519Field.create(), b = X25519Field.create(), c = X25519Field.create(), d = X25519Field.create();
        int[] e = X25519Field.create(), f = X25519Field.create(), g = X25519Field.create(), h = X25519Field.create();
        X25519Field.apm(p.y, p.x, pSum, pDiff);
        X25519Field.apm(q.y, q.x, qSum, qDiff);
        carry(pSum, pDiff, qSum, qDiff);
        X25519Field.mul(pDiff, qDiff, a);
        X25519Field.mul(pSum, qSum, b);
        X25519Field.mul(p.t, q.t, c);
        X25519Field.mul(c, D2, c);
        X25519Field.mul(p.z, q.z, d);
        X25519Field.add(d, d, d);
        X25519Field.apm(b, a, h, e);
        X25519Field.apm(d, c, g, f);
        carry(d, e, f, g, h);
        X25519Field.mul(e, f, r.x);
        X25519Field.mul(g, h, r.y);
        X25519Field.mul(e, h, r.t);
        X25519Field.mul(f, g, r.z);
    }

    private static void negate(Point p) {
        X25519Field.negate(p.x, p.x);
        X25519Field.negate(p.t, p.t);
        carry(p.x, p.t);
    }

    private static void cswap(int swap, Point a, Point b) {
        X25519Field.cswap(swap, a.x, b.x);
        X25519Field.cswap(swap, a.y, b.y);
        X25519Field.cswap(swap, a.z, b.z);
        X25519Field.cswap(swap, a.t, b.t);
    }

    /** r = scalar * p for a 32-byte little-endian scalar, by a Montgomery ladder over all 256 bits. */
    private static void scalarMult(byte[] scalar, Point p, Point r) {
        Point r0 = new Point();
        Point r1 = new Point();
        r1.set(p);
        int swap = 0;
        for (int i = 255; i >= 0; i--) {
            int bit = (scalar[i >>> 3] >>> (i & 7)) & 1;
            swap ^= bit;
            cswap(swap, r0, r1);
            swap = bit;
            add(r0, r1, r1);
            add(r0, r0, r0);
        }
        cswap(swap, r0, r1);
        r.set(r0);
    }

    private static void encode(Point p, byte[] out) {
        int[] zInverse = X25519Field.create();
        int[] x = X25519Field.create();
        int[] y = X25519Field.create();
        X25519Field.inv(p.z, zInverse);
        X25519Field.mul(p.x, zInverse, x);
        X25519Field.mul(p.y, zInverse, y);
        X25519Field.normalize(x);
        X25519Field.normalize(y);
        X25519Field.encode(y, out, 0);
        out[31] |= (byte) ((x[0] & 1) << 7);
    }

    /**
     * Decodes as BoringSSL's {@code x25519_ge_frombytes_vartime} does: y is taken modulo p, and
     * the encoding is refused only when no x exists.
     */
    private static boolean decode(byte[] encoded, Point r) {
        byte[] yBytes = encoded.clone();
        int sign = (yBytes[31] >>> 7) & 1;
        yBytes[31] &= 0x7f;
        int[] y = X25519Field.create();
        int[] u = X25519Field.create();
        int[] v = X25519Field.create();
        int[] x = X25519Field.create();
        X25519Field.decode(yBytes, 0, y);
        X25519Field.sqr(y, u);
        X25519Field.mul(u, D, v);
        X25519Field.subOne(u);
        X25519Field.addOne(v);
        carry(u, v);
        if (!X25519Field.sqrtRatioVar(u, v, x)) return false;
        X25519Field.normalize(x);
        if ((x[0] & 1) != sign) {
            X25519Field.negate(x, x);
            X25519Field.normalize(x);
        }
        X25519Field.normalize(y);
        X25519Field.copy(x, 0, r.x, 0);
        X25519Field.copy(y, 0, r.y, 0);
        X25519Field.one(r.z);
        X25519Field.mul(x, y, r.t);
        return true;
    }

    private static void carry(int[]... values) {
        for (int[] value : values) X25519Field.carry(value);
    }

    private static Point decodeOrFail(byte[] encoded) {
        Point p = new Point();
        if (!decode(encoded, p)) throw new IllegalStateException("A SPAKE2 constant is not a point");
        return p;
    }

    private static int[] fieldConstant(BigInteger value) {
        int[] element = X25519Field.create();
        X25519Field.decode(toLittleEndian(value), 0, element);
        return element;
    }

    // ---- scalars ----

    /** [wide] (little-endian) modulo l, as 32 little-endian bytes. */
    private static byte[] reduce(byte[] wide) {
        return toLittleEndian(littleEndian(wide).mod(ORDER));
    }

    /** n = 8n for a 32-byte little-endian n, as BoringSSL's left_shift_3. */
    private static void leftShift3(byte[] n) {
        int carry = 0;
        for (int i = 0; i < 32; i++) {
            int next = (n[i] & 0xff) >>> 5;
            n[i] = (byte) ((n[i] << 3) | carry);
            carry = next;
        }
    }

    private static void updateWithLength(SHA512Digest sha, byte[] data) {
        long length = data.length;
        for (int i = 0; i < 8; i++) {
            sha.update((byte) length);
            length >>>= 8;
        }
        sha.update(data, 0, data.length);
    }

    private static int[] words(byte[] littleEndian32) {
        int[] words = new int[8];
        for (int i = 0; i < 32; i++) words[i >>> 2] |= (littleEndian32[i] & 0xff) << ((i & 3) << 3);
        return words;
    }

    private static byte[] bytes(int[] words) {
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) out[i] = (byte) (words[i >>> 2] >>> ((i & 3) << 3));
        return out;
    }

    private static BigInteger littleEndian(byte[] bytes) {
        byte[] big = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) big[i] = bytes[bytes.length - 1 - i];
        return new BigInteger(1, big);
    }

    private static byte[] toLittleEndian(BigInteger value) {
        byte[] big = value.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < big.length && i < 32; i++) out[i] = big[big.length - 1 - i];
        return out;
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}

// SPDX-License-Identifier: Apache-2.0
// Added for Scrcpy Remote; see third_party/libadb/PATCHES.txt.

package io.github.muntashirakon.adb;

import org.junit.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class Spake2Test {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    // adbd's names, NUL included, from AOSP pairing_auth.cpp.
    private static final byte[] CLIENT = "adb pair client\u0000".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SERVER = "adb pair server\u0000".getBytes(StandardCharsets.UTF_8);
    private final SecureRandom random = new SecureRandom();

    @Test
    public void theBasePointTimesAClampedScalarGivesRfc8032sPublicKeys() throws Exception {
        String[][] vectors = {
                {"9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"},
                {"4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
                        "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"},
                {"c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
                        "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"},
        };
        for (String[] vector : vectors) {
            byte[] h = MessageDigest.getInstance("SHA-512").digest(hex(vector[0]));
            byte[] scalar = Arrays.copyOf(h, 32);
            scalar[0] &= (byte) 248;
            scalar[31] &= 127;
            scalar[31] |= 64;
            assertEquals(vector[1], toHex(Spake2.scalarMultBase(scalar)));
        }
    }

    @Test
    public void theProtocolPointsAreTheOnesBoringSslStates() {
        assertEquals("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e", toHex(Spake2.M_ENCODED));
        assertEquals("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778", toHex(Spake2.N_ENCODED));
        BigInteger[] m = Spake2.affine(Spake2.M_ENCODED);
        BigInteger[] n = Spake2.affine(Spake2.N_ENCODED);
        assertNotNull(m);
        assertNotNull(n);
        assertEquals(new BigInteger("31406539342727633121250288103050113562375374900226415211311216773867585644232"), m[0]);
        assertEquals(new BigInteger("21177308356423958466833845032658859666296341766942662650232962324899758529114"), m[1]);
        assertEquals(new BigInteger("49918732221787544735331783592030787422991506689877079631459872391322455579424"), n[0]);
        assertEquals(new BigInteger("54629554431565467720832445949441049581317094546788069926228343916274969994000"), n[1]);
        for (BigInteger[] point : new BigInteger[][]{m, n}) {
            BigInteger x2 = point[0].pow(2).mod(P);
            BigInteger y2 = point[1].pow(2).mod(P);
            assertEquals("On the curve", y2.subtract(x2).mod(P), BigInteger.ONE.add(D.multiply(x2).multiply(y2)).mod(P));
        }
    }

    @Test
    public void aliceAndBobAgreeOnAKeyForAdbsNames() {
        for (int run = 0; run < 20; run++) {
            byte[] password = randomPassword();
            Spake2 alice = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER);
            Spake2 bob = new Spake2(Spake2.Role.BOB, SERVER, CLIENT);
            byte[] aliceMessage = alice.generateMessage(password);
            byte[] bobMessage = bob.generateMessage(password);
            assertEquals(Spake2.MESSAGE_SIZE, aliceMessage.length);
            assertEquals(Spake2.MESSAGE_SIZE, bobMessage.length);
            byte[] aliceKey = alice.processMessage(bobMessage);
            byte[] bobKey = bob.processMessage(aliceMessage);
            assertNotNull(aliceKey);
            assertEquals(Spake2.KEY_SIZE, aliceKey.length);
            assertArrayEquals(aliceKey, bobKey);
        }
    }

    @Test
    public void aDifferentPasswordGivesADifferentKey() {
        byte[] password = randomPassword();
        byte[] other = password.clone();
        other[0] ^= 1;
        byte[][] keys = run(CLIENT, SERVER, password, SERVER, CLIENT, other);
        assertNotNull(keys[0]);
        assertNotNull(keys[1]);
        assertFalse(Arrays.equals(keys[0], keys[1]));
    }

    @Test
    public void differentNamesGiveDifferentKeys() {
        byte[] password = randomPassword();
        byte[] charlie = "charlie".getBytes(StandardCharsets.UTF_8);
        byte[][] keys = run(CLIENT, charlie, password, SERVER, charlie, password);
        assertNotNull(keys[0]);
        assertFalse(Arrays.equals(keys[0], keys[1]));
    }

    @Test
    public void aCorruptedMessageNeverYieldsTheSameKey() {
        byte[] password = randomPassword();
        for (int bit = 0; bit < 8 * Spake2.MESSAGE_SIZE; bit++) {
            Spake2 alice = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER);
            Spake2 bob = new Spake2(Spake2.Role.BOB, SERVER, CLIENT);
            byte[] aliceMessage = alice.generateMessage(password);
            byte[] bobMessage = bob.generateMessage(password);
            aliceMessage[bit / 8] ^= (byte) (1 << (bit & 7));
            byte[] bobKey = bob.processMessage(aliceMessage);
            byte[] aliceKey = alice.processMessage(bobMessage);
            assertNotNull(aliceKey);
            assertFalse("Bit " + bit, bobKey != null && Arrays.equals(aliceKey, bobKey));
        }
    }

    @Test
    public void aPeerWithoutThePasswordScalarFixStillAgrees() {
        for (int run = 0; run < 10; run++) {
            byte[] password = randomPassword();
            Spake2 oldAlice = Spake2.withoutPasswordScalarFix(Spake2.Role.ALICE, CLIENT, SERVER);
            Spake2 bob = new Spake2(Spake2.Role.BOB, SERVER, CLIENT);
            byte[] a = oldAlice.generateMessage(password);
            byte[] b = bob.generateMessage(password);
            byte[] oldAliceKey = oldAlice.processMessage(b);
            assertNotNull(oldAliceKey);
            assertArrayEquals(oldAliceKey, bob.processMessage(a));

            Spake2 alice = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER);
            Spake2 oldBob = Spake2.withoutPasswordScalarFix(Spake2.Role.BOB, SERVER, CLIENT);
            a = alice.generateMessage(password);
            b = oldBob.generateMessage(password);
            byte[] aliceKey = alice.processMessage(b);
            assertNotNull(aliceKey);
            assertArrayEquals(aliceKey, oldBob.processMessage(a));
        }
    }

    @Test
    public void thePasswordScalarIsTheHashModuloTheOrderMadeAMultipleOfEight() {
        for (int run = 0; run < 200; run++) {
            byte[] hash = new byte[64];
            random.nextBytes(hash);
            BigInteger reduced = littleEndian(hash).mod(L);
            BigInteger fixed = littleEndian(Spake2.passwordScalar(hash, true));
            assertEquals(reduced, fixed.mod(L));
            assertEquals(BigInteger.ZERO, fixed.mod(BigInteger.valueOf(8)));
            assertTrue(fixed.compareTo(L.shiftLeft(3)) < 0);
            assertEquals(reduced, littleEndian(Spake2.passwordScalar(hash, false)));
        }
    }

    @Test
    public void aMessageThatIsNotAPointIsRefused() {
        byte[] notAPoint = encodeY(firstYWithoutAPoint());
        assertNull(Spake2.affine(notAPoint));
        Spake2 bob = new Spake2(Spake2.Role.BOB, SERVER, CLIENT);
        bob.generateMessage(randomPassword());
        assertNull(bob.processMessage(notAPoint));
    }

    @Test
    public void aMessageOfTheWrongLengthIsRefused() {
        Spake2 bob = new Spake2(Spake2.Role.BOB, SERVER, CLIENT);
        bob.generateMessage(randomPassword());
        assertNull(bob.processMessage(new byte[31]));
    }

    @Test
    public void eachContextGeneratesOnceAndProcessesOnceAfterwards() {
        byte[] password = randomPassword();
        Spake2 alice = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER);
        assertThrows(IllegalStateException.class, () -> alice.processMessage(new byte[32]));
        alice.generateMessage(password);
        assertThrows(IllegalStateException.class, () -> alice.generateMessage(password));
        Spake2 bob = new Spake2(Spake2.Role.BOB, SERVER, CLIENT);
        assertNotNull(alice.processMessage(bob.generateMessage(password)));
        assertThrows(IllegalStateException.class, () -> alice.processMessage(new byte[32]));
    }

    @Test
    public void aDestroyedContextCannotBeUsed() {
        Spake2 alice = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER);
        alice.generateMessage(randomPassword());
        alice.destroy();
        assertTrue(alice.isDestroyed());
        assertThrows(IllegalStateException.class, () -> alice.processMessage(new byte[32]));
    }

    private byte[][] run(byte[] aliceName, byte[] aliceTheirs, byte[] alicePassword,
                         byte[] bobName, byte[] bobTheirs, byte[] bobPassword) {
        Spake2 alice = new Spake2(Spake2.Role.ALICE, aliceName, aliceTheirs);
        Spake2 bob = new Spake2(Spake2.Role.BOB, bobName, bobTheirs);
        byte[] a = alice.generateMessage(alicePassword);
        byte[] b = bob.generateMessage(bobPassword);
        return new byte[][]{alice.processMessage(b), bob.processMessage(a)};
    }

    /** A pairing password as adbd builds it: the six-digit code, then 64 bytes of TLS keying material. */
    private byte[] randomPassword() {
        byte[] password = new byte[6 + 64];
        for (int i = 0; i < 6; i++) password[i] = (byte) ('0' + random.nextInt(10));
        byte[] keying = new byte[64];
        random.nextBytes(keying);
        System.arraycopy(keying, 0, password, 6, 64);
        return password;
    }

    private static BigInteger firstYWithoutAPoint() {
        BigInteger exponent = P.subtract(BigInteger.ONE).shiftRight(1);
        for (BigInteger y = BigInteger.valueOf(2); ; y = y.add(BigInteger.ONE)) {
            BigInteger y2 = y.pow(2).mod(P);
            BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
            BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
            BigInteger x2 = u.multiply(v.modInverse(P)).mod(P);
            if (x2.signum() != 0 && x2.modPow(exponent, P).equals(P.subtract(BigInteger.ONE))) return y;
        }
    }

    private static byte[] encodeY(BigInteger y) {
        byte[] big = y.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < big.length && i < 32; i++) out[i] = big[big.length - 1 - i];
        return out;
    }

    private static BigInteger littleEndian(byte[] bytes) {
        byte[] big = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) big[i] = bytes[bytes.length - 1 - i];
        return new BigInteger(1, big);
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}

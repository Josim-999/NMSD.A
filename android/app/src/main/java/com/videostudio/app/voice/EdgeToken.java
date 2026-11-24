package com.videostudio.app.voice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/**
 * Builds the Sec-MS-GEC token the Microsoft Edge read-aloud voice service asks for.
 * Kept as Java: it is bit-exact integer hashing and must not change.
 */
public final class EdgeToken {
    private static final int ADJUSTMENT = -908327257;
    private static final int[][] CS64;
    private static final int CS64_ADJ0 = -218559718;
    private static final int CS64_ADJ1 = 186253312;
    private static final int CS64_FACTOR = -2104936141;
    private static final int[] K;
    private static final int[] K_BLOCK;
    private static final int SIGMA0_ADJ = 94112640;
    private static final int SIGMA1_ADJ = 758464;
    public static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final double WIN_EPOCH = 1.16444736E10d;
    private static final int[] INITIAL = {1779033703, -1150833019, 1013904242, -1521486534, 1359893119, -1694144372, 528734635, 1541459225};
    private static final int[] STATE = {-39862622, -1859896089, -1102475116, -852615670, 1811967817, 79508907, -21977116, 66059218};
    private static final int[] SCHEDULE = {-349913186, 358115615, -1597989150, 217723578, -1507660148, -774007663, -1059248736, -102298954, -255650445, 1884868174, 1904797190, 15051571, -765939616, 1920361828};

    private EdgeToken() {
    }

    static {
        int[] iArr = {1116352408, 1899447441, -1245643825, -373957723, 961987163, 1508970993, -1841331548, -1424204075, -670586216, 310598401, 607225278, 1426881987, 1925078388, -2132889090, -1680079193, -1046744716, -459576895, -272742522, 264347078, 604807628, 770255983, 1249150122, 1555081692, 1996064986, -1740746414, -1473132947, -1341970488, -1084653625, -958395405, -710438585, 113926993, 338241895, 666307205, 773529912, 1294757372, 1396182291, 1695183700, 1986661051, -2117940946, -1838011259, -1564481375, -1474664885, -1035236496, -949202525, -778901479, -694614492, -200395387, 275423344, 430227734, 506948616, 659060556, 883997877, 958139571, 1322822218, 1537002063, 1747873779, 1955562222, 2024104815, -2067236844, -1933114872, -1866530822, -1538233109, -1090935817, -965641998};
        K = iArr;
        int[] iArr2 = (int[]) iArr.clone();
        K_BLOCK = iArr2;
        iArr2[14] = ADJUSTMENT;
        CS64 = new int[][]{new int[]{-399601231, -1289289520}, new int[]{1155409094, -1998588643}, new int[]{1005282748, 693190712}, new int[]{-929277645, -1289172001}, new int[]{-1927364079, -737823531}, new int[]{827258325, 652566332}, new int[]{-383479375, 1618477510}, new int[]{-257574443, -2102214149}, new int[]{101076211, -95312934}, new int[]{-629540256, 545047652}, new int[]{-1093532320, 1010595059}, new int[]{-1980118536, -758035615}, new int[]{905590982, -1157324095}, new int[]{-884350010, -983408281}};
    }

    private static int rol16(int i) {
        return Integer.rotateLeft(i, 16);
    }

    private static int sigma0(int i) {
        return (i >>> 3) ^ (Integer.rotateRight(i, 7) ^ Integer.rotateRight(i, 18));
    }

    private static int sigma1(int i) {
        return (i >>> 10) ^ (Integer.rotateRight(i, 17) ^ Integer.rotateRight(i, 19));
    }

    private static int[] rounds(int[] iArr, int[] iArr2, int i) {
        int i2 = i;
        int[] iArr3 = i2 == 14 ? K_BLOCK : K;
        int i3 = iArr[0];
        int i4 = iArr[1];
        int i5 = iArr[2];
        int i6 = iArr[3];
        int i7 = iArr[4];
        int i8 = i5;
        int i9 = i6;
        int i10 = i7;
        int i11 = iArr[5];
        int i12 = iArr[6];
        int i13 = iArr[7];
        int i14 = i3;
        int i15 = i4;
        while (i2 < 64) {
            int rotateRight = i13 + ((Integer.rotateRight(i10, 6) ^ Integer.rotateRight(i10, 11)) ^ Integer.rotateRight(i10, 25)) + (i12 ^ ((i11 ^ i12) & i10)) + iArr3[i2] + iArr2[i2];
            int i16 = i9 + rotateRight;
            int rotateRight2 = rotateRight + ((Integer.rotateRight(i14, 2) ^ Integer.rotateRight(i14, 13)) ^ Integer.rotateRight(i14, 22)) + ((i14 & i15) | ((i14 | i15) & i8));
            i2++;
            i9 = i8;
            i13 = i12;
            i8 = i15;
            i12 = i11;
            i15 = i14;
            i11 = i10;
            i14 = rotateRight2;
            i10 = i16;
        }
        return new int[]{i14, i15, i8, i9, i10, i11, i12, i13};
    }

    private static void expand(int[] iArr, int i) {
        while (i < 64) {
            iArr[i] = sigma1(iArr[i - 2]) + iArr[i - 7] + sigma0(iArr[i - 15]) + iArr[i - 16];
            i++;
        }
    }

    private static int be(byte[] bArr, int i, int i2) {
        int i3 = 0;
        for (int i4 = 0; i4 < i2; i4++) {
            i3 = (i3 << 8) | (bArr[i + i4] & 0xFF);
        }
        return i3;
    }

    private static int[] init(byte[] bArr) {
        int[] iArr = new int[64];
        iArr[14] = be(bArr, 0, 3);
        iArr[15] = be(bArr, 3, 4);
        int i = 16;
        while (i < 30) {
            int i2 = SCHEDULE[i - 16];
            int i3 = i - 2;
            if (i3 >= 14) {
                i2 += i == 16 ? sigma1(iArr[i3]) ^ SIGMA1_ADJ : sigma1(iArr[i3]);
            }
            int i4 = i - 7;
            if (i4 >= 14) {
                i2 += iArr[i4];
            }
            int i5 = i - 15;
            if (i5 >= 14) {
                i2 += i == 29 ? sigma0(iArr[i5]) ^ SIGMA0_ADJ : sigma0(iArr[i5]);
            }
            iArr[i] = i2;
            i++;
        }
        iArr[30] = ((((sigma1(iArr[28]) + iArr[23]) + sigma0(iArr[15])) + iArr[14]) + ADJUSTMENT) - K[14];
        expand(iArr, 31);
        int[] rounds = rounds(STATE, iArr, 14);
        int[] iArr2 = new int[8];
        for (int i6 = 0; i6 < 8; i6++) {
            iArr2[i6] = INITIAL[i6] + rounds[i6];
        }
        return iArr2;
    }

    static byte[] sha(byte[] bArr) {
        int length = bArr.length + 57;
        int floorMod = Math.floorMod(55 - length, 64);
        int length2 = bArr.length;
        int i = length2 - 7;
        int i2 = (length2 - 6) + floorMod;
        int i3 = i2 + 8;
        byte[] bArr2 = new byte[i3];
        System.arraycopy(bArr, 7, bArr2, 0, i);
        bArr2[i] = (byte) 0x80;
        long j = length * 8;
        for (int i4 = 0; i4 < 8; i4++) {
            bArr2[(i2 + 7) - i4] = (byte) (j >>> (i4 * 8));
        }
        int[] init = init(bArr);
        for (int i5 = 0; i5 < i3; i5 += 64) {
            int[] iArr = new int[64];
            for (int i6 = 0; i6 < 16; i6++) {
                iArr[i6] = be(bArr2, (i6 * 4) + i5, 4);
            }
            expand(iArr, 16);
            int[] rounds = rounds(init, iArr, 0);
            for (int i7 = 0; i7 < 8; i7++) {
                init[i7] = init[i7] + rounds[i7];
            }
        }
        byte[] bArr3 = new byte[32];
        for (int i8 = 0; i8 < 8; i8++) {
            int i9 = i8 * 4;
            int i10 = init[i8];
            bArr3[i9] = (byte) (i10 >>> 24);
            bArr3[i9 + 1] = (byte) (i10 >>> 16);
            bArr3[i9 + 2] = (byte) (i10 >>> 8);
            bArr3[i9 + 3] = (byte) i10;
        }
        return bArr3;
    }

    private static int le(byte[] bArr, int i) {
        return ((bArr[i + 3] & 0xFF) << 24) | (bArr[i] & 0xFF) | ((bArr[i + 1] & 0xFF) << 8) | ((bArr[i + 2] & 0xFF) << 16);
    }

    static int[] cs64(byte[] bArr, byte[] bArr2) {
        int i = 0;
        int le = le(bArr2, 0) | 1;
        int le2 = le(bArr2, 4) | 1;
        int[] iArr = {le * CS64_FACTOR, CS64_FACTOR * le2};
        int length = bArr.length;
        int i2 = length + 1;
        byte[] bArr3 = new byte[i2];
        System.arraycopy(bArr, 0, bArr3, 1, bArr.length);
        int i3 = ((length + 4) / 4) * 4;
        byte[] bArr4 = new byte[i3];
        System.arraycopy(bArr3, 0, bArr4, 0, i2);
        int i4 = i3 / 4;
        int i5 = (i4 % 2) + i4;
        int[] iArr2 = new int[i5];
        for (int i6 = 0; i6 < i4; i6++) {
            iArr2[i6] = le(bArr4, i6 * 4);
        }
        int length2 = CS64.length + i5;
        int[] iArr3 = new int[length2];
        int[] iArr4 = new int[length2];
        int i7 = 0;
        while (true) {
            int[][] iArr5 = CS64;
            if (i7 >= iArr5.length) {
                break;
            }
            int[] iArr6 = iArr5[i7];
            iArr3[i7] = iArr6[0] * iArr[i7 % 2];
            iArr4[i7] = iArr6[1];
            i7++;
        }
        for (int i8 = 0; i8 < i5; i8++) {
            int i9 = iArr2[i8];
            int i10 = i8 % 2;
            int i11 = (i10 == 0 ? le : le2) * i9;
            int rol16 = rol16(i9) * (i10 == 0 ? -284857861 : -1021897765);
            if (i8 == 0) {
                i11 += iArr[0] * CS64_ADJ0;
                rol16 += CS64_ADJ1;
            }
            int[][] iArr7 = CS64;
            iArr3[iArr7.length + i8] = i11;
            iArr4[iArr7.length + i8] = rol16;
        }
        int i12 = 0;
        int i13 = 0;
        int i14 = 0;
        int i15 = 0;
        while (i < length2) {
            int i16 = iArr3[i];
            int i17 = i + 1;
            int i18 = iArr3[i17];
            int rol162 = rol16(iArr4[i] + i16) * 1995771700;
            int rol163 = i14 + (rol16(rol162) * (-1007687017)) + rol162;
            int rol164 = rol16(iArr4[i17] + i18) * 411160124;
            int rol165 = rol164 + (rol16(rol164) * 901586633);
            i14 = rol163 + rol165;
            int rol166 = rol16(rol16(rol16(rol16((i13 * le) + i16) * (-812076783)) * (-2029495393)) * 315537773) * 629022083;
            i13 = (-1079730327) * rol16(rol16(rol16(rol16((rol166 * le2) + i18) * (-1569450251)) * (-746350849)) * 2090019721);
            i15 = i15 + rol166 + i13;
            i += 2;
            i12 = rol165;
        }
        return new int[]{i12 ^ i13, i14 ^ i15};
    }

    private static String hex(byte[] bArr) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bArr) {
            sb.append(String.format("%02X", Integer.valueOf(b & 0xFF)));
        }
        return sb.toString();
    }

    public static String generate(double d) {
        double d2 = d + WIN_EPOCH;
        byte[] bytes = (new BigDecimal((d2 - (d2 % 300.0d)) * 1.0E7d).setScale(0, RoundingMode.HALF_EVEN).toPlainString() + TRUSTED_CLIENT_TOKEN).getBytes(StandardCharsets.US_ASCII);
        byte[] sha = sha(bytes);
        int[] cs64 = cs64(bytes, sha);
        byte[] bArr = new byte[8];
        for (int i = 0; i < 4; i++) {
            int i2 = i * 8;
            bArr[i] = (byte) (cs64[0] >>> i2);
            bArr[i + 4] = (byte) (cs64[1] >>> i2);
        }
        return hex(bArr) + "-" + hex(sha);
    }

}

package org.gregoire.debug.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Byte and Bit manipulation routines.<br>
 * Returns Big endian bit vals, so reading 4 bits of binary'0100' returns 4 instead of 2. 
 * 
 * @author Paul Gregoire
 * @author Andy Shaules
 */
public class ByteUtil {

    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(ByteUtil.class);

    public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    private final byte[] data;

    private int dataIndex = 0;

    private int bitIndex = 0;

    public ByteUtil(byte b) {
        data = new byte[1];
        data[0] = (byte) (b & 0xff);
    }

    public ByteUtil(byte[] b) {
        data = b;
    }

    //    public ByteUtil(byte b1, byte b2) {
    //        bitIndex = 0;
    //        data = new byte[2];
    //        data[0] = (byte) (b1 & 0xff);
    //        data[1] = (byte) (b2 & 0xff);
    //    }

    /**
     * This methods reads bits from high to low.
     * <p>
     * Reading 2 bits will return an integer where the returned value has a potential maximum of 1<<2.
     * </p>
     * 
     * @param numBits
     *            The number of bits to read.
     * @return Returns an integer with a max value up to ( 1 << bits read )
     */
    public int nibble(int numBits) {
        int ret = 0;
        while ((dataIndex < data.length) && numBits > 0) {
            ret |= (((data[dataIndex] >> (7 - bitIndex++)) & 0x1) << --numBits);
            if ((bitIndex %= 8) == 0) {
                dataIndex++;
            }
        }
        return ret;
    }

    public static boolean isBitSet(byte b, int bit) {
        return (b & (1 << bit)) != 0;
    }

    public static int readUnsignedShort(ByteBuffer in) {
        short val = in.getShort();
        return val >= 0 ? val : 0x10000 + val;
    }

    public static String toHexString(byte[] ba) {
        StringBuilder hex = new StringBuilder(ba.length * 2);
        for (byte b : ba) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Converts a given int value into a byte array.
     * 
     * @param value the int value
     * @return four bytes
     */
    public static final byte[] intToByteArray(int value) {
        return new byte[] { (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value };
    }

    /**
     * Convert a byte array to an int array with big endian order.
     * 
     * @param bytes
     * @return
     */
    public static int[] convertBytesToIntsBE(byte[] bytes) {
        IntBuffer intBuf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asIntBuffer();
        int[] array = new int[intBuf.remaining()];
        intBuf.get(array);
        return array;
    }

    /**
     * Convert a byte array to an int array with little endian order.
     * 
     * @param bytes
     * @return
     */
    public static int[] convertBytesToIntsLE(byte[] bytes) {
        IntBuffer intBuf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int[] array = new int[intBuf.remaining()];
        intBuf.get(array);
        return array;
    }

    /**
     * Convert an int array to a byte array with big endian order.
     * 
     * @param ints
     * @return
     */
    public static byte[] convertIntsToBytesBE(int[] ints) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(ints.length * 4).order(ByteOrder.BIG_ENDIAN);
        IntBuffer intBuffer = byteBuffer.asIntBuffer();
        intBuffer.put(ints);
        intBuffer.flip();
        byte[] array = byteBuffer.array();
        return array;
    }

    /**
     * Convert short array to big-endian byte array.
     * 
     * @param shorts
     * @param bytes
     */
    public static void shortsToBytesBE(short[] shorts, byte[] bytes) {
        for (int i = 0; i < shorts.length; i++) {
            int dx = 0 + (i << 1);
            bytes[dx] = (byte) ((shorts[i] >> 8) & 0xff);
            bytes[dx + 1] = (byte) (shorts[i] & 0xff);
        }
    }

    public static ByteBuffer stringToByteBuffer(String str, Charset charset) {
        return ByteBuffer.wrap(str.getBytes(charset));
    }

    public static String byteBufferToString(ByteBuffer buffer, Charset charset) {
        byte[] bytes;
        if (buffer.hasArray()) {
            bytes = buffer.array();
        } else {
            bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
        }
        return new String(bytes, charset);
    }

    public static String dumpBytes(byte[] data, int count, boolean hex) {
        byte[] dest = new byte[count];
        System.arraycopy(data, 0, dest, 0, count);
        String result = null;
        if (hex) {
            result = toHexString(dest);
        } else {
            result = Arrays.toString(dest);
        }
        return result;
    }

    public static String toHexString(byte[] data, int length, int offset) {
        byte[] chunk = new byte[length];
        System.arraycopy(data, offset, chunk, 0, length);
        return toHexString(chunk);
    }

    /**
     * Converts an array of bytes masquerading as an set of ints into a proper byte array.
     * 
     * @param ints
     * @return byte[]
     */
    public final static byte[] intArrayToByteArray(int... ints) {
        byte[] data = new byte[ints.length];
        for (int i = 0; i < ints.length; i++) {
            data[i] = (byte) ints[i];
        }
        return data;
    }

    /**
     * Converts an hexadecimal string into a single byte.
     * 
     * @param s hex encoded string
     * @return byte
     */
    public static byte hexStringToByte(String s) {
        return (byte) Integer.parseInt(s, 16);
    }

    /**
     * Converts an hexadecimal string into a proper byte array.
     * 
     * @param s hex encoded string
     * @return byte[]
     */
    public final static byte[] hexStringToByteArray(String s) {
        // remove all the whitespace first
        s = s.replaceAll("\\s+", "");
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Finds the first index of the target array within the outer array.
     * 
     * @param target
     * @param outer
     * @return index of the target array if it exists in the outer array and -1 if not found
     */
    public final static int indexOf(byte[] target, byte[] outer) {
        for (int i = 0; i < outer.length - target.length + 1; ++i) {
            boolean found = true;
            for (int j = 0; j < target.length; ++j) {
                if (outer[i + j] != target[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return i;
            }
        }
        return -1;
    }

    public final static ByteUtil build(byte b) {
        return new ByteUtil(b);
    }

    public final static ByteUtil build(byte[] bytes) {
        return new ByteUtil(bytes);
    }

}

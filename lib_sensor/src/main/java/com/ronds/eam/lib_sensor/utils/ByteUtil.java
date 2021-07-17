package com.ronds.eam.lib_sensor.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * @author An.Wang 2019/4/2 10:55
 */
public class ByteUtil {
  private static final ByteOrder byteOrderDef = ByteOrder.LITTLE_ENDIAN;
  private static ByteOrder byteOrder = byteOrderDef;

  public static ByteOrder getByteOrder() {
    return byteOrder;
  }

  public static void setByteOrder(ByteOrder byteOrder) {
    if (byteOrder == null) return;
    ByteUtil.byteOrder = byteOrder;
  }

  public static short getShortFromByteArray(byte[] data, int start) {
    if (data == null || start > data.length - 2) return (short) 0;
    final byte[] bytes = Arrays.copyOfRange(data, start, start + 2);
    return ByteBuffer.wrap(bytes).order(byteOrder).getShort();
  }

  public static int getIntFromByteArray(byte[] data, int start) {
    if (data == null || start > data.length - 4) return (int) 0;
    final byte[] bytes = Arrays.copyOfRange(data, start, start + 4);
    return ByteBuffer.wrap(bytes).order(byteOrder).getInt();
  }

  public static float getFloatFromByteArray(byte[] data, int start) {
    if (data == null || start > data.length - 4) return (float) 0;
    final byte[] bytes = Arrays.copyOfRange(data, start, start + 4);
    return ByteBuffer.wrap(bytes).order(byteOrder).getFloat();
  }

  public static short[] bytesToShorts(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    final short[] shorts = new short[bytes.length / 2];
    ByteBuffer.wrap(bytes).order(byteOrder).asShortBuffer().get(shorts);
    return shorts;
  }

  public static float[] bytesToFloats(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    final float[] floats = new float[bytes.length / 4];
    ByteBuffer.wrap(bytes).order(byteOrder).asFloatBuffer().get(floats);
    return floats;
  }

  public static byte[] shortToBytes(short s) {
    final byte[] bytes = new byte[2];
    ByteBuffer.wrap(bytes).order(byteOrder).putShort(s);
    return bytes;
  }

  public static byte[] shortsToBytes(short[] shorts) {
    if (shorts == null) {
      return null;
    }
    final byte[] bytes = new byte[shorts.length * 2];
    ByteBuffer.wrap(bytes).order(byteOrder).asShortBuffer().put(shorts);
    return bytes;
  }

  public static byte[] intToBytes(int n) {
    final byte[] bytes = new byte[4];
    ByteBuffer.wrap(bytes).order(byteOrder).putInt(n);
    return bytes;
  }

  public static byte[] intToBytesTest(int n) {
    final byte[] bytes = new byte[4];
    ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(n);
    return bytes;
  }

  public static int bytesToInt(byte[] b) {
    if (b == null) return 0;
    return ByteBuffer.wrap(b).order(byteOrder).getInt();
  }

  public static byte[] floatToBytes(float f) {
    final byte[] bytes = new byte[4];
    ByteBuffer.wrap(bytes).order(byteOrder).putFloat(f);
    return bytes;
  }

  public static byte[] floatsToBytes(float[] floats) {
    if (floats == null) return null;
    final byte[] bytes = new byte[floats.length * 4];
    ByteBuffer.wrap(bytes).order(byteOrder).asFloatBuffer().put(floats);
    return bytes;
  }

  public static float bytesToFloat(byte[] b) {
    if (b == null) return 0;
    return ByteBuffer.wrap(b).order(byteOrder).getFloat();
  }

  /**
   * CRC校验
   */
  public static int computeCRC32(byte[] data) {
    // 1
    //CRC32 crc32 = new CRC32();
    //crc32.update(data);
    //return (int)(crc32.getValue());

    // 2
    //long crc = 0x0FFFFFFFFL;
    //if (data == null) {
    //  return (int) (crc & 0x0FFFFFFFFL);
    //}
    //long temp;
    //for (byte b : data) {
    //  temp = (crc & 0x0FFL) ^ (b & 0x0FFL);
    //  for (byte j = 0; j < 8; j++) {
    //    long status = temp & 0x01L;
    //    if (status != 0) {
    //      temp = temp >>> 1 ^ 0x0EDB88320L;
    //    } else {
    //      temp >>>= 1;
    //    }
    //  }
    //  crc = crc >>> 8 ^ temp;
    //}
    //return (int) ((crc ^ 0x0FFFFFFFFL) & 0x0FFFFFFFFL);

    // 3
    int crc = 0xFFFFFFFF;
    if (data == null) {
      return crc;
    }
    int temp;
    for (byte b : data) {
      temp = (crc & 0x0FF) ^ (b & 0x0FF);
      for (byte j = 0; j < 8; j++) {
        if ((temp & 1) != 0) {
          temp = temp >>> 1 ^ 0xEDB88320;
        } else {
          temp >>>= 1;
        }
      }
      crc = crc >>> 8 ^ temp;
    }
    return ~crc;
  }

  public static String byteToBit(byte b) {
    return "" + (byte) ((b >> 7) & 0x1) +
        (byte) ((b >> 6) & 0x1) +
        (byte) ((b >> 5) & 0x1) +
        (byte) ((b >> 4) & 0x1) +
        (byte) ((b >> 3) & 0x1) +
        (byte) ((b >> 2) & 0x1) +
        (byte) ((b >> 1) & 0x1) +
        (byte) ((b >> 0) & 0x1);
  }

  // 每个字节位反转了, 从左到右依次为 第 1 个字节第 1 位, 第 2位, ..., 第 n 个字节第 1 位, 第 2 位,...
  public static String bytes2BitStr(byte[] bytes) {
    String ret = "";
    if (bytes == null) return ret;
    final StringBuilder sb = new StringBuilder();
    //      for (int i = 0, length = bytes.length; i < length; i++) {
    //         for (int j = 0; j < 8; j++) {
    //            sb.append((byte) ((bytes[i] >> j) & 0x1));
    //         }
    //      }
    for (byte b : bytes) {
      for (int j = 0; j < 8; j++) {
        sb.append((byte) ((b >> j) & 0x1));
      }
    }
    return sb.toString();
  }

  public static String byteArray2HexString(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return "[]";
    }
    StringBuilder sb = new StringBuilder();
    String temp;
    for (byte b : bytes) {
      temp = Integer.toHexString(0xFF & b);
      if (temp.length() < 2) {
        sb.append("0x0");
      }
      else {
        sb.append("0x");
      }
      sb.append(temp.toUpperCase()).append(", ");
    }
    return sb.delete(sb.length() - 2, sb.length())
        .insert(0, "[")
        .append("]")
        .toString();
  }

  public static String byteArray2HexStringWithIndex(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return "[]";
    }
    StringBuilder sb = new StringBuilder();
    String temp;
    for (int i = 0; i < bytes.length; i ++) {
      byte b = bytes[i];
      temp = Integer.toHexString(0xFF & b);
      sb.append(i).append(" - ");
      if (temp.length() < 2) {
        sb.append("0x0");
      }
      else {
        sb.append("0x");
      }
      sb.append(temp.toUpperCase()).append(", ");
    }
    return sb.delete(sb.length() - 2, sb.length())
        .insert(0, "[")
        .append("]")
        .toString();
  }

  /**
   * 二进制字符串转byte
   */
  public static byte decodeBinaryString(String byteStr) {
    int re, len;
    if (null == byteStr) {
      return 0;
    }
    len = byteStr.length();
    if (len != 4 && len != 8) {
      return 0;
    }
    if (len == 8) {// 8 bit处理
      if (byteStr.charAt(0) == '0') {// 正数
        re = Integer.parseInt(byteStr, 2);
      } else {// 负数
        re = Integer.parseInt(byteStr, 2) - 256;
      }
    } else {// 4 bit处理
      re = Integer.parseInt(byteStr, 2);
    }
    return (byte) re;
  }

  /*
   * int转字节数组
   *
   * @param int src
   *
   * @return byte[] result
   */
  @Deprecated
  public static byte[] intToByteArray(int src) {
    byte[] result = new byte[4];
    for (int i = 0; i < 4; i++) {
      int offset = (result.length - 1 - i) * 8;
      result[3 - i] = (byte) ((src >>> offset) & 0xff);
    }
    return result;
  }

  @Deprecated
  public static byte[] floatToByteArray(float src) {
    byte[] result = new byte[4];
    for (int i = 0; i < 4; i++) {
      int offset = (result.length - 1 - i) * 8;
      result[3 - i] = (byte) ((Float.floatToIntBits(src) >>> offset) & 0xff);
    }
    return result;
  }

  /*
   * short转字节数组
   *
   * @param short src
   *
   * @return byte[] result
   */
  @Deprecated
  public static byte[] shortToByteArray(short src) {
    byte[] result = new byte[2];
    for (int i = 0; i < 2; i++) {
      int offset = (result.length - 1 - i) * 8;
      result[1 - i] = (byte) ((src >>> offset) & 0xff);
    }
    return result;
  }

  /**
   * string to byte
   */
  @Deprecated
  public static byte[] stringToByteArray(String str) {
    byte[] result = new byte[str.length() * 2];
    for (int i = 0; i < str.length(); i++) {
      char item = str.charAt(i);
      result[0 + i * 2] = charToByteArray(item)[0];
      result[1 + i * 2] = charToByteArray(item)[1];
    }

    return result;
  }

  /*
   * short转字节数组
   *
   * @param char src
   *
   * @return byte[] result
   */
  @Deprecated
  public static byte[] charToByteArray(char src) {
    byte[] result = new byte[2];
    for (int i = 0; i < 2; i++) {
      int offset = (result.length - 1 - i) * 8;
      result[1 - i] = (byte) ((src >>> offset) & 0xff);
    }
    return result;
  }

  /**
   * 累加和校验，并取反
   */
  public static byte makeCheckSum(byte[] data) {
    int total = 0;
    for (byte e : data) {
      total += e & 0xFF;
    }
    return (byte) (0x100 - total % 0x100);
    //int total = 0;
    //int len = data.length;
    //for (int i = 0; i < len; i++) {
    //  int item = data[i] & 0xff;
    //  total = total + item;
    //}
    //
    ////用256求余最大是255，即16进制的FF
    //int mod = total % 256;
    //mod = 256 - mod;
    //if (mod == 0) {
    //  return (byte) 0xff;
    //} else {
    //  return (byte) mod;
    //}
  }

  /**
   * 取反
   */
  public static String parseHex2Opposite(String str) {
    String hex;
    //十六进制转成二进制
    byte[] er = parseHexStr2Byte(str);

    //取反
    byte erBefore[] = new byte[er.length];
    for (int i = 0; i < er.length; i++) {
      erBefore[i] = (byte) ~er[i];
    }

    //二进制转成十六进制
    hex = parseByte2HexStr(erBefore);

    // 如果不够校验位的长度，补0,这里用的是两位校验
    hex = (hex.length() < 2 ? "0" + hex : hex);

    return hex;
  }

  /**
   * 将十六进制转换为二进制
   */
  public static byte[] parseHexStr2Byte(String hexStr) {
    if (hexStr.length() < 1) {
      return null;
    }
    byte[] result = new byte[hexStr.length() / 2];
    for (int i = 0; i < hexStr.length() / 2; i++) {
      int high = Integer.parseInt(hexStr.substring(i * 2, i * 2 + 1), 16);
      int low = Integer.parseInt(hexStr.substring(i * 2 + 1, i * 2 + 2), 16);
      result[i] = (byte) (high * 16 + low);
    }
    return result;
  }

  /**
   * 将二进制转换成十六进制
   */
  public static String parseByte2HexStr(byte buf[]) {
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < buf.length; i++) {
      String hex = Integer.toHexString(buf[i] & 0xFF);
      if (hex.length() == 1) {
        hex = '0' + hex;
      }
      sb.append(hex.toUpperCase());
    }
    return sb.toString();
  }

  public static byte[] getTestBytes() {
    return new byte[]{
        (byte)0xa0, 0x02, 0x17, 0x00, 0x02, 0x00, 0x00, 0x15, 0x04, 0x10, 0x10, 0x16, 0x00, 0x01, 0x01,
        0x15, 0x04, 0x10, 0x11, 0x16, 0x00, 0x00, (byte)0xa4
    };
    //return new byte[]{
    //    (byte) 0xa0, 0xc , 0x41 , 0x0 , 0x1 , 0x0 , 0x65 , (byte) 0xcd, 0x1d , (byte) 0xe0, 0x4f , 0xe , 0x1c , 0x1 , 0x32 , 0x30 , 0x35 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 ,
    //    (byte) 0xcd, 0x0 , 0x0 , 0x0 , (byte) 0x83, 0x0 , 0x4 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 ,
    //    (byte) 0x80, 0x3f , 0x0 , 0x0 , (byte) 0x80, 0x3f , 0x0 , 0x0 , (byte) 0x80, 0x3f , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x0 , 0x41
    //};
    //return new byte[] {(byte) 0xa0, 0x0c, 0x41, 0x0, 0x17 , 0x8 , (byte) 0xc4, 0x0 , 0x10 , 0x61 , 0x61 , 0x6e ,
    //    (byte) 0xaa, (byte) 0xd0, (byte) 0xe4, 0x17 , (byte) 0xd1, 0x1 , (byte) 0xaf, (byte) 0x99,
    //    (byte) 0xdf, (byte) 0xa1, 0x68 , 0x58 , (byte) 0xa6, 0x57 , (byte) 0x8a, (byte) 0xb2, 0x29 , 0x5a , 0x58 , 0x28 ,
    //    (byte) 0x80, (byte) 0xc2, 0x38 , (byte) 0xac, 0x0 , 0x0 , (byte) 0x80, 0x3f , 0x0 , 0x0 ,
    //    (byte) 0x80, 0x3f , 0x0 , 0x0 , (byte) 0x80, 0x3f , 0x66 , 0x17 , (byte) 0xe1, (byte) 0xbb, 0x78 ,
    //    (byte) 0xac, 0x21 , 0xa , 0x71 , 0x68 , 0x5d , (byte) 0x99, 0x68 , (byte) 0xa7, (byte) 0xd1,
    //    (byte) 0xe6, (byte) 0x85};
  }
}
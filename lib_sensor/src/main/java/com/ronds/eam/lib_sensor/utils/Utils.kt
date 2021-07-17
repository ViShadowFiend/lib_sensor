package com.ronds.eam.lib_sensor.utils

import com.ronds.eam.lib_sensor.exceptions.CmdIncorrectException
import com.ronds.eam.lib_sensor.exceptions.HeadIncorrectException
import com.ronds.eam.lib_sensor.exceptions.SizeIncorrectException

object Utils {
  fun crc(data: ByteArray): UInt {
    var crc: UInt
    var temp: UInt
    var j: Int

    crc = 0xFFFFFFFFU

    for (i in data.indices) {
      temp = crc and 0xFFU xor data[i].toUInt()
      j = 0
      while (j < 8) {
        val status = temp and 0x1U
        temp = if (status != 0U) {
          temp shr 1 xor 0xEDB88320U
        } else {
          temp shr 1
        }
        j++
      }
      crc = crc shr 8 xor temp
    }
    return crc xor 0xFFFFFFFFU
  }

  fun buildBytes(totalSize: Int, data: List<Pair<Int, Any>>): ByteArray {
    val ret = ByteArray(totalSize)
    var i = 0
    for (e in data) {
      val size = e.first
      var v = e.second
      when (v) {
        is Short -> v = ByteUtil.shortToBytes(v)
        is Int -> v = ByteUtil.intToBytes(v)
        is Float -> v = ByteUtil.floatToBytes(v)
        is Byte -> v = byteArrayOf(v)
        is String -> {
          // String(originalByteArray).toByteArray() 得到的结果并不等于 originalByteArray
          // 甚至 size 都不一样. String 跟 ByteArray 的转换 需要用到 char 来中转
          // v = v.toByteArray()
          v = v.toCharArray().toByteArray()
          if (v.size < size) {
            v = v.copyInto(ByteArray(size))
          }
        }
      }
      if (v is Array<*> || v is ByteArray) {
        System.arraycopy(v, 0, ret, i, size)
      }
      i += size
    }
    return ret
  }
}

fun ByteArray.toHexString(
  separator: CharSequence = "",
  prefix: CharSequence = "",
  postfix: CharSequence = "",
  isUpperCase: Boolean = true,
  limit: Int = -1,
  truncated: CharSequence = "...",
): String {
  return joinToString(separator, prefix, postfix, limit, truncated) {
    it.toHexString(isUpperCase)
  }
}



internal fun Byte.toHexString(isUpperCase: Boolean = true): String {
  val format = if (isUpperCase) {
    "%02X"
  } else {
    "%02x"
  }
  return String.format(format, toInt() and 0xFF)
}

internal fun ByteArray.toCharArray(): CharArray {
  return map { it.toInt().toChar() }.toCharArray()
}

internal fun CharArray.toByteArray(): ByteArray {
  return map { it.code.toByte() }.toByteArray()
}

internal fun ByteArray.getString(index: Int, len: Int) = String(
  this.copyOfRange(index, index + len).toCharArray()
)

internal fun ByteArray.getByte(index: Int) = this[index]
internal fun ByteArray.getChar(index: Int) = this[index].toInt().toChar()
internal fun ByteArray.getInt(index: Int) = ByteUtil.getIntFromByteArray(this, index)
internal fun ByteArray.getShort(index: Int) = ByteUtil.getShortFromByteArray(this, index)
internal fun ByteArray.getFloat(index: Int) = ByteUtil.getFloatFromByteArray(this, index)

internal fun ByteArray?.pack(head: Byte, cmd: Byte): ByteArray {
  val src = this
  val originSize = src?.size ?: 0

  val length: Short = (originSize + 5).toShort() // length

  val lengthB: ByteArray = ByteUtil.shortToBytes(length) // lengthB

  val bytesWithoutCs = ByteArray(originSize + 4)

  bytesWithoutCs[0] = head
  bytesWithoutCs[1] = cmd
  System.arraycopy(lengthB, 0, bytesWithoutCs, 2, 2)
  if (src != null && originSize > 0) {
    System.arraycopy(src, 0, bytesWithoutCs, 4, originSize)
  }

  val cs: Byte = ByteUtil.makeCheckSum(bytesWithoutCs) // cs
  val bytes = bytesWithoutCs.copyOf(bytesWithoutCs.size + 1)
  bytes[bytes.size - 1] = cs

  return bytes
}

@Throws(Exception::class)
internal fun ByteArray?.unpack(headFrom: Byte, cmdFrom: Byte, packSize: Int): ByteArray {
  val size = this?.size ?: 0
  if (this == null || size != packSize || size < 5) {
    throw SizeIncorrectException
  }
  val head = this[0]
  if (head != headFrom) {
    throw HeadIncorrectException
  }
  val cmd = this[1]
  if (cmd != cmdFrom) {
    throw CmdIncorrectException
  }
  return this.copyOfRange(4, packSize - 1)
}
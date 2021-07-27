package com.ronds.eam.lib_sensor.adapters.rh205

import com.ronds.eam.lib_sensor.adapters.Decoder
import com.ronds.eam.lib_sensor.adapters.Encoder
import com.ronds.eam.lib_sensor.consts.RH205Consts
import com.ronds.eam.lib_sensor.exceptions.RException
import com.ronds.eam.lib_sensor.utils.ByteUtil
import com.ronds.eam.lib_sensor.utils.Utils
import com.ronds.eam.lib_sensor.utils.getByte
import com.ronds.eam.lib_sensor.utils.getFloat
import com.ronds.eam.lib_sensor.utils.getInt
import com.ronds.eam.lib_sensor.utils.getShort
import com.ronds.eam.lib_sensor.utils.getString

// kotlin 1.5.0 之后 Unsigned 类型 release, 之后可以考虑替换为 Unsigned 类型.
data class SystemParamsAdapter(
  var channel: Byte = 0,
  var originLoraRxFreq: Float = 0F,
  var originLoraTxFreq: Float = 0F,
  var loraTxPow: Byte = 0,
  var bleName: String = "",
  var sn: Int = 0,
  var mcVersion: String = "",
  var adVersion: Byte = 0,
  var sVersionMain: Short = 0,
  var sVersionSub: Byte = 0,
  var accCoe: Float = 0F,
  var accCoeX: Float = 0F,
  var accCoeY: Float = 0F,
) : Encoder, Decoder<SystemParamsAdapter> {

  private var loraTxFreq: Int = 0
  private var loraRxFreq: Int = 0

  override val cmdTo: Byte = RH205Consts.CMD_SET_SYSTEM_PARAMS
  override val cmdFrom: Byte = RH205Consts.CMD_GET_SYSTEM_PARAMS

  override fun encode(): ByteArray {
    checkChannel(channel)
    checkLoraRxFreq(originLoraRxFreq)
    checkLoraTxFreq(originLoraTxFreq)
    loraTxFreq = encodeLoraTxFreq(originLoraTxFreq, channel)
    loraRxFreq = encodeLoraRxFreq(originLoraRxFreq)
    val bs = bleName.toByteArray()
    val len = bs.size
    return listOf<Pair<Int, Any>>(
      1 to channel,
      4 to loraRxFreq,
      4 to loraTxFreq,
      1 to loraTxPow,
      20 to bleName,
      4 to sn,
      2 to mcVersion,
      1 to adVersion,
      2 to sVersionMain,
      1 to sVersionSub,
      2 to ByteArray(2),
      4 to accCoe,
      4 to accCoeX,
      4 to accCoeY,
      16 to ByteArray(16),
    )
      .let { Utils.buildBytes(70, it) }
      .run { pack() }
  }

  override fun decode(bytes: ByteArray?): SystemParamsAdapter {
    val unpack = try {
      bytes.unpack(75)
    } catch (e: Exception) {
      throw RException("unpack失败. len = ${bytes?.size}.", e)
    }
    return SystemParamsAdapter().apply {
      try {
        channel = unpack.getByte(0)
        checkChannel(channel)
        loraRxFreq = unpack.getInt(1)
        originLoraRxFreq = decodeLoraRxFreq(loraRxFreq)
        checkLoraRxFreq(originLoraRxFreq)
        loraTxFreq = unpack.getInt(5)
        originLoraTxFreq = decodeLoraTxFreq(loraTxFreq, channel)
        checkLoraTxFreq(originLoraTxFreq)
        loraTxPow = unpack.getByte(9)
        bleName = unpack.getString(10, 20)
        sn = unpack.getInt(30)
        mcVersion = unpack.getString(34, 2)
        adVersion = unpack.getByte(36)
        sVersionMain = unpack.getShort(37)
        sVersionSub = unpack.getByte(39)
        accCoe = unpack.getFloat(42)
        accCoeX = unpack.getFloat(46)
        accCoeY = unpack.getFloat(50)
      } catch (e: Exception) {
        throw RException("解析错误. ${ByteUtil.parseByte2HexStr(bytes)}", e)
      }
    }
  }

  private fun checkChannel(channel: Byte): Boolean {
    return channel.toUByte() in 1U..255U
  }

  private fun checkLoraTxFreq(freq: Float): Boolean {
    val r = freq.toString()
      .matches(
        Regex(
          "4(70\\.7|72\\.3|73\\.9|75\\.5|77\\.1|78\\.7|80\\.3|81\\.9|83\\.5|85\\.1|86\\.7|88\\.3)"
        )
      )
    // if (!r) {
    //   throw IllegalArgumentException("LoraTxFreq $freq illegal.")
    // }
    return r
  }

  private fun checkLoraRxFreq(freq: Float): Boolean {
    val r = freq.toString().matches(Regex("5(10\\.5|0[0-9]\\.[05])"))
    // if (!r) {
    //   throw IllegalArgumentException("LoraRxFreq $freq illegal.")
    // }
    return r
  }

  private fun encodeLoraRxFreq(freq: Float): Int {
    val r = freq * 100_0000
    return r.toInt()
  }

  private fun decodeLoraRxFreq(freq: Int): Float {
    val r = freq / 100_0000f
    return r
  }

  private fun encodeLoraTxFreq(freq: Float, channel: Byte): Int {
    val a: Int = (channel.toUByte().toInt() - 1) % 8
    val r = (freq + a * 0.2f) * 100_0000
    return r.toInt()
  }

  private fun decodeLoraTxFreq(freq: Int, channel: Byte): Float {
    val a: Int = (channel.toUByte().toInt() - 1) % 8
    val r = freq / 100_0000.toFloat() - a * 0.2f
    return r
  }
}

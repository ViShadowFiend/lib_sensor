package com.ronds.eam.lib_sensor.adapters.rh205

import com.ronds.eam.lib_sensor.adapters.Encoder
import com.ronds.eam.lib_sensor.consts.RH205Consts
import com.ronds.eam.lib_sensor.utils.Utils

private const val ACC_COE_Z = 0.037422084375f
private const val ACC_COE_X = 0.0047900390625f
private const val ACC_COE_Y = 0.0047900390625f

data class SampleParamsAdapter(
  // 采集长度, 单位 K个点, 1/2/4/8/16/32. 如 2K, 即 2 * 1024 个点. 每个点 2 个字节, 即 4096 Byte
  var len: Short = 0,
  // 分析频率, 单位 100hz, 5, 10, 20, 50, 100, 200,
  var freq: Short = 0,
  // 轴向, 0 - z, 1 - x, 2 -y, 3 - 采集温度
  var axis: Byte = 0,
  // 测温发射率
  var tempEmi: Float = 0.97f,
) : Encoder {

  override val cmdTo: Byte
    get() = RH205Consts.CMD_SAMPLING_PARAMS

  // 获取加速度系数, 205 和 517 不同
  fun getAccCoe(): Float {
    return when (axis) {
      // z
      0.toByte() -> ACC_COE_Z
      // x
      1.toByte() -> ACC_COE_X
      // y
      2.toByte() -> ACC_COE_Y
      else -> 0f
    }
  }

  override fun encode(): ByteArray {
    return listOf(
      2 to len,
      2 to freq,
      1 to axis,
      4 to tempEmi,
    )
      .let { Utils.buildBytes(9, it) }
      .run { pack() }
  }

  val lenDisplay get() = "${len}K"
  val freqDisplay get() = "${freq * 100}Hz"
  val axisDisplay
    get(): String {
      val ret = ""
      if (axis == 0.toByte()) {
        return "Z"
      }
      if (axis == 1.toByte()) {
        return "X"
      }
      if (axis == 2.toByte()) {
        return "Y"
      }
      return ret
    }
}
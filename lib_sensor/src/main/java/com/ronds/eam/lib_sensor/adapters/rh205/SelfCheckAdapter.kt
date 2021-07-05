package com.ronds.eam.lib_sensor.adapters.rh205

import com.ronds.eam.lib_sensor.adapters.Decoder
import com.ronds.eam.lib_sensor.consts.RH205Consts
import com.ronds.eam.lib_sensor.utils.getByte
import com.ronds.eam.lib_sensor.utils.getInt

data class SelfCheckAdapter(
  // 节点类型
  var nodeType: Byte = 0,
  // sn
  var sn: Int = 0,
  // rtc 晶振状态
  var rtcStatus: Status = Status.ABNORMAL,
  // ad 模块状态
  var adStatus: Status = Status.ABNORMAL,
  // mems 模块状态
  var memsStatus: Status = Status.ABNORMAL,
  // 外部 flash 状态
  var flashStatus: Status = Status.ABNORMAL,
  // 温度模块状态
  var tempStatus: Status = Status.ABNORMAL,
  // Lora 模块状态
  var loraStatus: Status = Status.ABNORMAL,
  // Lora 信号
  var loraSignal: Byte = 0,
  // 蓝牙信号
  var btSignal: Byte = 0,
): Decoder<SelfCheckAdapter> {
  override val cmdFrom: Byte = RH205Consts.CMD_SELF_CHECK

  override fun decode(bytes: ByteArray?): SelfCheckAdapter {
    val d = bytes.unpack(20)
    return SelfCheckAdapter().apply {
      nodeType = d.getByte(0)
      sn = d.getInt(1)
      rtcStatus = d.getByte(5).status
      adStatus = d.getByte(6).status
      memsStatus = d.getByte(7).status
      flashStatus = d.getByte(8).status
      tempStatus = d.getByte(9).status
      loraStatus = d.getByte(10).status
      loraSignal = d.getByte(11)
      btSignal = d.getByte(12)
    }
  }

  private val Byte.status: Status
    get() = Status.of(this)

  enum class Status(val status: Byte) {
    NORMAL(0x01) {
      override val display: String
        get() = "正常"
    },
    ABNORMAL(0x00) {
      override val display: String
        get() = "异常"
    },
    ;
    abstract val display: String

    companion object {
      fun of(status: Byte): Status {
        return values().find { it.status == status } ?: ABNORMAL
      }
    }
  }
}
package com.ronds.eam.lib_sensor

import android.util.Log
import com.clj.fastble.BleManager
import com.clj.fastble.callback.BleNotifyCallback
import com.clj.fastble.callback.BleReadCallback
import com.clj.fastble.callback.BleWriteCallback
import com.clj.fastble.exception.BleException
import com.ronds.eam.lib_sensor.adapters.Decoder
import com.ronds.eam.lib_sensor.adapters.rh205.CalibrationVibrationAdapter
import com.ronds.eam.lib_sensor.adapters.rh205.GetDataDetailParam
import com.ronds.eam.lib_sensor.adapters.rh205.GetDataInfoListParam
import com.ronds.eam.lib_sensor.adapters.rh205.GetDataInfoListResult
import com.ronds.eam.lib_sensor.adapters.rh205.GetDataListParams
import com.ronds.eam.lib_sensor.adapters.rh205.GetDataListResult
import com.ronds.eam.lib_sensor.adapters.rh205.SampleParamsAdapter
import com.ronds.eam.lib_sensor.adapters.rh205.SampleResultAdapter
import com.ronds.eam.lib_sensor.adapters.rh205.SampleTempResultAdapter
import com.ronds.eam.lib_sensor.adapters.rh205.SelfCheckAdapter
import com.ronds.eam.lib_sensor.adapters.rh205.SystemParamsAdapter
import com.ronds.eam.lib_sensor.consts.HEAD_FROM_SENSOR
import com.ronds.eam.lib_sensor.consts.HEAD_TO_SENSOR
import com.ronds.eam.lib_sensor.consts.RH205Consts
import com.ronds.eam.lib_sensor.consts.RH205Consts.CMD_CALIBRATION_VIBRATION
import com.ronds.eam.lib_sensor.consts.RH205Consts.CMD_DATA_DETAIL
import com.ronds.eam.lib_sensor.consts.RH205Consts.CMD_GET_SYSTEM_PARAMS
import com.ronds.eam.lib_sensor.consts.RH205Consts.CMD_SAMPLING_PARAMS
import com.ronds.eam.lib_sensor.consts.RH205Consts.CMD_SELF_CHECK
import com.ronds.eam.lib_sensor.consts.RH205Consts.CMD_SET_SYSTEM_PARAMS
import com.ronds.eam.lib_sensor.consts.RH205Consts.CMD_WAVE_DATA_RESULT
import com.ronds.eam.lib_sensor.consts.UUID_SERVICE
import com.ronds.eam.lib_sensor.consts.UUID_UP
import com.ronds.eam.lib_sensor.utils.ByteUtil
import com.ronds.eam.lib_sensor.utils.CRC
import com.ronds.eam.lib_sensor.utils.getInt
import com.ronds.eam.lib_sensor.utils.getShort
import com.ronds.eam.lib_sensor.utils.pack
import com.ronds.eam.lib_sensor.utils.toHex
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object RH205Mgr : ABleMgr() {
  // 波形数据传输或者升级文件传输时, 每包有效数最大长度
  private const val CHUNK_LEN = 232
  // 波形数据传输或者升级文件传输最大包数
  private const val MAX_CHUNK_COUNT = 320

  // 分析频率转为采样频率的系数
  // RH205 因为硬件时钟的原因. 采样频率 = 分析频率 * 2.5
  // 一般情况. 采样频率 = 分析频率 * 2.56.
  const val FREQ_COE = 2.50f

  override val TIP_DISCONNECT: String
    get() = "当前未与205连接, 请连接205后重试"
  override val TIP_TIMEOUT: String
    get() = "响应超时, 请检查与205的连接"

  var sdf_ = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault())

  var bytesWave: ByteArray? = null // 波形原始数据 byte[]

  var mainVersionSensor: Int? = null // 传感器当前的主版本号
  var mainVersionRemote: Int? = null // 服务器获取的的主版本号
  var tempVersionSensor: Int? = null // 传感器当前的测温模块版本号
  var tempVersionRemote: Int? = null // 服务器获取的测温模块版本号

  private val isRunning = AtomicBoolean(false) // 标识ble设备是否执行命令
  private var runningStatus: String? = null // 描述当前 ble 设备正在执行的命令

  fun testNotify(notify: (String) -> Unit) {
    notify {
      ByteUtil.byteArray2HexString(it).let { s ->
        notify(s)
      }
    }
  }

  fun testWrite(s: String, callBack: BleWriteCallback) {
    write(ByteUtil.parseHexStr2Byte(s), callBack)
  }

  fun testRead(callback: BleReadCallback) {
    read(callback)
  }

  fun isNeedUpgrade(sensorVersion: Int?, remoteVersion: Int?): Boolean {
    if (sensorVersion == null || remoteVersion == null) return false
    return remoteVersion > sensorVersion
  }

  // 是否正在采集
  private val isSampling = AtomicBoolean(false)

  // 是否正在测温
  private val isTempProcessing = AtomicBoolean(false)

  // 测温回调
  private var sampleTempCallback: SampleTempCallback? = null

  // 收到的每包数据, 与 205 的协议规定, 振动数据不超过 320 包
  // list 中 index 对应收到的包的编号
  private val waveData: MutableList<ByteArray?> = MutableList(MAX_CHUNK_COUNT) { null }

  // 回复下位机收包结果, 40 * 8bit, 总共 320 bit, 对应最多 320 包的收包情况.
  // 当收到包后, 将该包对应的 位 置 1
  private val response = ByteArray(MAX_CHUNK_COUNT / 8)

  /**
   * ok
   * 开始温度采集
   * @param tempEmi 测温发射率
   */
  fun sampleTemp(param: SampleParamsAdapter, callback: SampleTempCallback) {
    sampleTempCallback = callback
    if (!isConnected()) {
      sampleTempCallback?.onFail(TIP_DISCONNECT)
      return
    }
    isTempProcessing.set(true)
    val isTimeoutTemp = AtomicBoolean(false)
    val isReceivedTemp = AtomicBoolean(false)

    curFuture?.cancel(true)
    mainHandler.removeCallbacksAndMessages(null)
    curFuture = singleExecutor.submit {
      doSleep(200)
      notify { data ->
        dTag("notify_temp", ByteUtil.parseByte2HexStr(data))
        dTag("notify_temp1", data?.toHex())
        isReceivedTemp.set(true)
        if (!isTempProcessing.get()) return@notify
        if (isTimeoutTemp.get()) return@notify
        var r = SampleTempResultAdapter()
        try {
          r = r.decode(data)
        } catch (e: Exception) {
          mainHandler.post {
            if (isTempProcessing.get()) {
              sampleTempCallback?.onFail("采集失败, 返回格式有误")
            }
            isTempProcessing.set(false)
          }
          return@notify
        }
        sampleTempCallback?.onReceiveTemp(r.temp)
      }
      val sampleTemp = param.encode()
      doSleep(200)
      write(sampleTemp)
    }
  }

  fun removeSampleTempCallback() {
    sampleTempCallback = null
  }

  /**
   * 测振
   */
  fun sample(
    params: SampleParamsAdapter,
    onTimeListener: ((time: Long, speed: Float) -> Unit)? = null,
    callback: SampleResultCallback
  ) {
    if (!isConnected()) {
      callback.onFail(TIP_DISCONNECT)
      return
    }
    // 采集长度. 代表多少个点
    val caiJiChangDu = params.len * 1024
    // 分析频率, 单位 Hz
    val fenXiPinLv = params.freq * 100
    // 采样频率, 单位 hz
    val caiYangPinLv = fenXiPinLv * FREQ_COE
    // 采集时间. 单位 ms.
    // 频率为每秒振动多少下. 振动一下即有一个点的数据. 频率为多少, 即每秒有采集多少个点的数据
    val collectTime = (caiJiChangDu.toDouble() / caiYangPinLv * 1000.0).toLong()
    // 回传时间. 单位 ms.
    // * 2 因为每个点(short) 2个字节, / 4 是预估传输速度为 4b/ms
    // 预留 10 * 1000 ms. 因为 RH205 时常超时
    val transferTime = (caiJiChangDu * 2.0 / 4).toLong() + caiJiChangDu * 10
    // 采样数据长度. 即有多少个字节的数据
    val dataLength = caiJiChangDu * 2

    val isTimeoutSample = AtomicBoolean(false)
    val isReceivedSample = AtomicBoolean(false)
    val isTimeoutResultFirst = AtomicBoolean(false)
    val isReceivedResultFirst = AtomicBoolean(false)
    val isTimeoutResultOthers = AtomicBoolean(false)
    val isReceivedResultOthers = AtomicBoolean(false)
    val isTimeoutWaveData = AtomicBoolean(false)
    val isReceivedWaveData = AtomicBoolean(false)

    waveData.fill(null)
    response.fill(0)
    var ratio: Float = 0f
    var crc: Int = 0
    var totalBagCount = 0

    release()
    doSleep(100)

    var testTime: Long = System.currentTimeMillis()
    var testIndex: Int = 0
    var testTotalBagCount: Int = (dataLength.toFloat() / CHUNK_LEN).toInt()

    // 用以计算传输耗时和传输速率
    var firstChunk = true
    var firstChunkMills = 0L

    BleManager.getInstance()
      .notify(curBleDevice, UUID_SERVICE, UUID_UP, object : BleNotifyCallback() {
        override fun onCharacteristicChanged(data: ByteArray?) {
          onLog?.invoke("notify 收到. ${ByteUtil.parseByte2HexStr(data)}")
          if (data == null || data.size < 5 || data[0] != HEAD_FROM_SENSOR) {
            return
          }
          Log.d(
            "收到: ",
            "${ByteUtil.parseByte2HexStr(byteArrayOf(data[1]))} ${System.currentTimeMillis()}"
          )
          when (data[1]) {
            CMD_SAMPLING_PARAMS -> { // 收到下达测振的回复
              dTag("notify_sample", data)
              isReceivedSample.set(true)
              if (isTimeoutSample.get()) {
                return
              }
              var r = SampleResultAdapter()
              try {
                r = r.decode(data)
              } catch (e: Exception) {
                mainHandler.post {
                  if (isSampling.get()) {
                    callback.onFail("采集失败, 返回格式有误")
                  }
                  isSampling.set(false)
                }
                return
              }
              callback.onCallbackSampleResult(r)
              // 等待 采集时间, 若还未收到波形数据, 则超时
              doRetry(0, {}, collectTime, 0, 5000, isReceivedWaveData, isTimeoutWaveData) {
                mainHandler.post {
                  callback.onFail("等待波形数据超时")
                  mainHandler.removeCallbacksAndMessages(null)
                  BleManager.getInstance().clearCharacterCallback(curBleDevice)
                }
              }
              // 等待 采集时间 + 传输时间, 若还未收到结果确认, 则超时
              doRetry(
                0, {}, collectTime + transferTime, 0, 5000, isReceivedResultFirst,
                isTimeoutResultFirst
              ) {
                mainHandler.post {
                  callback.onFail("等待结果确认超时")
                  mainHandler.removeCallbacksAndMessages(null)
                  BleManager.getInstance().clearCharacterCallback(curBleDevice)
                }
              }
            }
            CMD_DATA_DETAIL -> { // 收到测振数据
              isReceivedWaveData.set(true)
              Log.d(
                "收到包号",
                "${testIndex++}/${testTotalBagCount}, ${System.currentTimeMillis() - testTime}"
              )
              if (firstChunk) {
                firstChunk = false
                firstChunkMills = System.currentTimeMillis()
              }
              testTime = System.currentTimeMillis()
              if (isTimeoutWaveData.get()) {
                return
              }
              val curBagNum: Int = data.getShort(4).toInt()
              val bytes = Arrays.copyOfRange(data, 6, data.size - 1)
              waveData[curBagNum] = bytes
            }
            CMD_WAVE_DATA_RESULT -> {
              if (data.size != 9) {
                return
              }
              isReceivedResultFirst.set(true)
              if (isTimeoutResultFirst.get()) {
                return
              }
              isReceivedResultOthers.set(true)
              if (isTimeoutResultOthers.get()) {
                return
              }
              totalBagCount = ByteUtil.getIntFromByteArray(data, 4)
              var isEnd = true
              for (i in 0 until totalBagCount) {
                if (waveData[i] != null) {
                  updateBit(response, i)
                } else {
                  isEnd = false
                }
              }
              val log = ByteUtil.bytes2BitStr(response).substring(0, totalBagCount)
              onLog?.invoke("response: $log")
              if (isEnd) {
                val bytes: MutableList<Byte> = mutableListOf()
                for (i in 0 until totalBagCount) {
                  waveData[i]!!.forEach { bytes.add(it) }
                }
                val testLen = bytes.size
                // RH205 最后一包有填充了 0x00 的无效数据, 根据 dataLen 截掉小尾巴
                val bytesArr = Arrays.copyOfRange(bytes.toByteArray(), 0, dataLength)
                val testLenClip = bytesArr.size
                onLog?.invoke("波形测试: 包数=${totalBagCount}, len=${testLen}->${testLenClip}")
                callback.onReceiveVibData(bytesArr)
                // 传输耗时
                val time = System.currentTimeMillis() - firstChunkMills
                // 平均传输速率, b / ms, kb / s
                val speed = dataLength.toFloat() / time
                onTimeListener?.invoke(System.currentTimeMillis() - firstChunkMills, speed)
                val res = response.pack(HEAD_TO_SENSOR, CMD_WAVE_DATA_RESULT)
                doSleep(50)
                write(res)
                mainHandler.post {
                  release()
                }
              } else {
                doRetry(50L, {
                  val res = response.pack(HEAD_TO_SENSOR, CMD_WAVE_DATA_RESULT)
                  write(res)
                }, transferTime, 2, 5000, isReceivedResultOthers, isTimeoutResultOthers) {
                  mainHandler.post {
                    callback.onFail("回复结果超时")
                    release()
                  }
                }
              }
            }
          }
        }

        override fun onNotifyFailure(exception: BleException?) {
          onLog?.invoke("notify 失败. ${exception?.description}")
          mainHandler.post {
            callback.onFail(exception?.description ?: "notify 失败")
            mainHandler.removeCallbacksAndMessages(null)
            BleManager.getInstance().clearCharacterCallback(curBleDevice)
          }
        }

        override fun onNotifySuccess() {
          onLog?.invoke("notify 成功")
          doRetry(100, {
            val data = params.encode()
            write(data)
          }, 0, 2, 400, isReceivedSample, isTimeoutSample) {
            mainHandler.post {
              callback.onFail("下达采集参数超时")
              release()
            }
          }
        }
      })
  }

  /**
   * ok
   * 停止采集
   */
  fun stopSample(callback: ActionCallback) {
    if (!isConnected()) {
      callback.onFail(TIP_DISCONNECT)
      return
    }
    val isReceived = AtomicBoolean(false)
    val isTimeout = AtomicBoolean(false)
    isSampling.set(false)
    curFuture?.cancel(true)
    removeSampleTempCallback()
    mainHandler.removeCallbacksAndMessages(null)
    curFuture = singleExecutor.submit {
      doSleep(200)
      notify { data ->
        dTag("stop_sample_205_receive", ByteUtil.byteArray2HexString(data))
        if (data == null) {
          mainHandler.post {
            callback.onFail("停止采集失败")
            mainHandler.removeCallbacksAndMessages(null)
          }
          BleManager.getInstance().removeNotifyCallback(curBleDevice, UUID_UP)
          return@notify
        }
        isReceived.set(true)
        if (isTimeout.get()) {
          mainHandler.removeCallbacksAndMessages(null)
          BleManager.getInstance().removeNotifyCallback(curBleDevice, UUID_UP)
          return@notify
        }
        mainHandler.post {
          callback.onSuccess()
          mainHandler.removeCallbacksAndMessages(null)
        }
        BleManager.getInstance().removeNotifyCallback(curBleDevice, UUID_UP)
      }
      doTimeout(200L, {
        val data = byteArrayOf().pack(HEAD_TO_SENSOR, RH205Consts.CMD_STOP_SAMPLE)
        write(data)
      }, 200L, 2, 500, isReceived, isTimeout) {
        mainHandler.post {
          callback.onFail("超时")
          mainHandler.removeCallbacksAndMessages(null)
        }
        BleManager.getInstance().removeNotifyCallback(curBleDevice, UUID_UP)
      }
    }
  }

  fun getDataList(param: GetDataListParams, callback: GetDataListResultCallback) {
    if (!isConnected()) {
      callback.onFail(TIP_DISCONNECT)
      return
    }
    release()
    val isReceived = AtomicBoolean(false)
    val isTimeout = AtomicBoolean(false)
    curFuture = singleExecutor.submit {
      doSleep(200)

      notify { data ->
        if (isTimeout.get()) {
          return@notify
        }
        isReceived.set(true)
        var r = GetDataListResult()
        try {
          r = r.decode(data)
        } catch (e: Exception) {
          return@notify
        }
        callback.onCallbackResult(r)
      }
      doTimeout(200, {
        val data = param.encode()
        write(data)
      }, 200, 2, 500, isReceived, isTimeout) {
        mainHandler.post {
          callback.onFail("超时")
          release()
        }
      }
    }
  }

  fun getDataInfoList(param: GetDataInfoListParam, callback: GetDataInfoListResultCallback) {
    if (!isConnected()) {
      callback.onFail(TIP_DISCONNECT)
      return
    }
    release()
    val isReceived = AtomicBoolean(false)
    val isTimeout = AtomicBoolean(false)
    curFuture = singleExecutor.submit {
      doSleep(200)
      notify { data ->
        if (isTimeout.get()) {
          return@notify
        }
        isReceived.set(true)
        var r = GetDataInfoListResult()
        try {
          r = r.decode(data)
        } catch (e: Exception) {
          return@notify
        }
        callback.onCallbackResult(r)
      }
      doTimeout(200, {
        val data = param.encode()
        write(data)
      }, 200, 2, 500, isReceived, isTimeout) {
        mainHandler.post {
          callback.onFail("超时")
          release()
        }
      }
    }
  }

  fun getDataDetail(param: GetDataDetailParam, callback: SampleVibCallback) {
    if (!isConnected()) {
      callback.onFail(TIP_DISCONNECT)
      return
    }

    val isTimeoutResult = AtomicBoolean(false)
    val isReceivedResult = AtomicBoolean(false)
    val isTimeoutWaveData = AtomicBoolean(false)
    val isReceivedWaveData = AtomicBoolean(false)

    waveData.fill(null)
    response.fill(0)
    var totalBagCount = 0

    release()
    doSleep(50)
    BleManager.getInstance().notify(curBleDevice, UUID_SERVICE, UUID_UP, object : BleNotifyCallback() {
      override fun onCharacteristicChanged(data: ByteArray?) {
        onLog?.invoke("notify 收到. ${ByteUtil.parseByte2HexStr(data)}")
        if (data == null || data.size < 5 || data[0] != HEAD_FROM_SENSOR) {
          release()
          return
        }
        when (data[1]) {
          CMD_DATA_DETAIL -> { // 收到测振数据
            if (isTimeoutWaveData.get()) {
              return
            }
            isReceivedWaveData.set(true)
            val curBagNum: Int = data.getShort(4).toInt()
            val bytes = Arrays.copyOfRange(data, 6, data.size - 1)
            waveData[curBagNum] = bytes
          }
          CMD_WAVE_DATA_RESULT -> {
            if (data.size != 9) {
              return
            }
            if (isTimeoutResult.get()) {
              return
            }
            isReceivedResult.set(true)
            if (isTimeoutResult.get()) {
              return
            }
            totalBagCount = data.getInt(4)
            var isEnd = true
            for (i in 0 until totalBagCount) {
              if (waveData[i] != null) {
                updateBit(response, i)
              }
              else {
                isEnd = false
              }
            }
            if (isEnd) {
              val bytes: MutableList<Byte> = mutableListOf()
              for (i in 0 until totalBagCount) {
                waveData[i]!!.forEach { bytes.add(it) }
              }
              val shorts: ShortArray = ByteUtil.bytesToShorts(bytes.toByteArray())
              val res = response.pack(HEAD_TO_SENSOR, CMD_WAVE_DATA_RESULT)
              doSleep(50)
              write(res)
              mainHandler.post {
                // TODO coe 没有
                callback.onReceiveVibData(shorts, 1f)
                release()
              }
            }
            else {
              doTimeout(50L, {
                val res = response.pack(HEAD_TO_SENSOR, CMD_WAVE_DATA_RESULT)
                write(res)
              }, param.dataLen / 2.toLong(), 2, 5000, isReceivedResult, isTimeoutResult) {
                mainHandler.post {
                  callback.onFail("回复结果超时")
                  release()
                }
              }
            }
          }
        }
      }

      override fun onNotifyFailure(exception: BleException?) {
        onLog?.invoke("notify 失败. ${exception?.description}")
        mainHandler.post {
          callback.onFail(exception?.description ?: "notify 失败")
          release()
        }
      }

      override fun onNotifySuccess() {
        onLog?.invoke("notify 成功.")
        doTimeout(1000, {
          val data = param.encode()
          write(data)
        }, 0, 2, 1000, isReceivedWaveData, isTimeoutWaveData) {
          mainHandler.post {
            callback.onFail("下达采集参数超时")
            mainHandler.removeCallbacksAndMessages(null)
            BleManager.getInstance().clearCharacterCallback(curBleDevice)
          }
        }
      }
    })
  }

  /**
   * ok
   * 下达系统参数
   */
  fun setSystemParams(data: SystemParamsAdapter, callback: ActionCallback) {
    if (!isConnected()) {
      callback?.onFail(TIP_DISCONNECT)
      return
    }
    val isTimeout = AtomicBoolean(false)
    val isReceived = AtomicBoolean(false)
    curFuture?.cancel(true)
    mainHandler.removeCallbacksAndMessages(null)
    curFuture = singleExecutor.submit {
      doSleep(200)
      notify { data ->
        dTag("notify_set_system_params", data)
        isReceived.set(true)
        if (isTimeout.get()) return@notify
        if (data != null && data.size == 6 && data[4] == 1.toByte() && data[0] == HEAD_FROM_SENSOR && data[1] == CMD_SET_SYSTEM_PARAMS) {
          callback?.onSuccess()
        } else {
          callback?.onFail("下达系统参数失败")
        }
      }
      doTimeout(200L, {
        write(data.encode())
      }, 200L, 2, 500, isReceived, isTimeout) {
        callback?.onFail(TIP_TIMEOUT)
      }
    }
  }

  /**
   * ok
   * 获取系统参数
   */
  fun getSystemParams(decoder: Decoder<SystemParamsAdapter>, callback: GetSystemParamsCallback) {
    if (!isConnected()) {
      callback.onFail(TIP_DISCONNECT)
      return
    }
    val isReceived = AtomicBoolean(false)
    val isTimeout = AtomicBoolean(false)
    curFuture?.cancel(true)
    curFuture = singleExecutor.submit {
      doSleep(200)
      notify { data ->
        dTag("notify_get_system_params", data)
        isReceived.set(true)
        if (isTimeout.get()) {
          callback.onFail(null)
          return@notify
        }
        try {
          val d = decoder.decode(data)
          callback.onCallback(d)
        } catch (e: Exception) {
          callback.onFail("获取系统参数失败, ${e}")
          return@notify
        }
      }
      doTimeout(200L, {
        val data = byteArrayOf().pack(HEAD_TO_SENSOR, CMD_GET_SYSTEM_PARAMS)
        dTag("getSystemParams_send", data)
        write(data)
      }, 200L, 2, 500, isReceived, isTimeout) {
        callback.onFail(TIP_TIMEOUT)
      }
    }
  }

  /**
   * 硬件自检测
   */
  fun selfCheck(decoder: Decoder<SelfCheckAdapter>, callback: SelfCheckCallback) {
    if (!isConnected()) {
      callback.onFail(TIP_DISCONNECT)
      return
    }
    val isReceived = AtomicBoolean(false)
    val isTimeout = AtomicBoolean(false)
    curFuture?.cancel(true)
    curFuture = singleExecutor.submit {
      doSleep(200)
      notify { data ->
        dTag("notify_self_check", data)
        isReceived.set(true)
        if (isTimeout.get()) {
          callback.onFail(null)
          return@notify
        }
        try {
          val d = decoder.decode(data)
          callback.onCallback(d)
        } catch (e: Exception) {
          callback.onFail("硬件自检测失败")
          return@notify
        }
      }
      doTimeout(200L, {
        val data = byteArrayOf().pack(HEAD_TO_SENSOR, CMD_SELF_CHECK)
        dTag("self_check_send", data)
        write(data)
      }, 200L, 2, 6000, isReceived, isTimeout) {
        callback.onFail(TIP_TIMEOUT)
      }
    }
  }

  /**
   * ok
   * 振动校准
   */
  fun calibrate(encoder: CalibrationVibrationAdapter, callback: CalibrationCallback) {
    if (!isConnected()) {
      callback?.onFail(TIP_DISCONNECT)
      return
    }
    val isReceived = AtomicBoolean(false)
    val isTimeout = AtomicBoolean(false)
    curFuture?.cancel(true)
    mainHandler.removeCallbacksAndMessages(null)
    curFuture = singleExecutor.submit {
      doSleep(200)
      notify { data ->
        dTag("notify_calibration", data)
        isReceived.set(true)
        if (isTimeout.get()) return@notify
        if (data == null || data.size < 9 || data[0] != HEAD_FROM_SENSOR || data[1] != CMD_CALIBRATION_VIBRATION) {
          callback?.onFail("振动校准失败")
          return@notify
        }
        val coe: Float = ByteUtil.getFloatFromByteArray(data, 4)
        callback?.onCallback(coe)
      }
      val len = encoder.len // 单位 K
      val freq = encoder.freq // 单位 100Hz
      // 校准需要的时间(s)为 长度(K) * 1024 / 频率(HZ) / 2.56, 预留 1000 ms
      val timeout = len * 1024 / (freq * 100) / FREQ_COE * 1000 + 1000
      val action = {
        val data = encoder.encode()
        dTag("calibration_data", data)
        write(data)
      }
      doTimeout(200L, action, timeout.toLong(), 1, retryTimeout = timeout.toInt(),
        isTimeout = isTimeout, isReceived = isReceived, toDoWhenTimeout = {
        callback?.onFail("超时")
      })
    }
  }

  /**
   * ok
   * 升级
   * @param sn sn 号
   * @param byteArray 升级数据
   */
  fun upgrade(sn: Int, transferInterval: Long = 200L, byteArray: ByteArray?, callback: UpgradeCallback) {
    if (!isConnected()) {
      callback.onUpgradeResult(false, TIP_DISCONNECT, null)
      return
    }
    if (byteArray == null) {
      callback.onUpgradeResult(false, "升级文件不存在, 请重新下载升级文件", null)
      return
    }
    val isTimeoutPrepare = AtomicBoolean(false)
    val isReceivedPrepare = AtomicBoolean(false)
    val isTimeoutResult = AtomicBoolean(false)
    val isReceivedResult = AtomicBoolean(false)

    val bytesUpgrade = byteArray
    val hex = ByteUtil.parseByte2HexStr(bytesUpgrade)
    dTag("upgrade_hex", hex)
    val snB: ByteArray = ByteUtil.intToBytes(sn)
    val length: Int = bytesUpgrade.size
    val lengthB: ByteArray = ByteUtil.intToBytes(length)
    val crc: Int = ByteUtil.computeCRC32(bytesUpgrade)
    val crcB: ByteArray = ByteUtil.intToBytes(crc)
    val crcStr = ByteUtil.parseByte2HexStr(crcB)
    val crcN = CRC.crc32(bytesUpgrade, 0, bytesUpgrade.size)
    val crcBN = ByteUtil.intToBytes(crcN)
    val crcStrN = ByteUtil.parseByte2HexStr(crcBN)
    dTag("upgrade_crc_bytes", ByteUtil.parseByte2HexStr(ByteUtil.intToBytesTest(crc)))
    dTag("upgrade_crc_hex", crcStr)
    dTag("upgrade_crc_hex-new", crcStrN)

    val totalBagCount =
      length.toBigDecimal().divide(CHUNK_LEN.toBigDecimal(), 0, BigDecimal.ROUND_UP).toInt()
    if (totalBagCount < 1) {
      mainHandler.post { callback.onUpgradeResult(false, "升级文件出错, 请重新下载升级文件", null) }
      return
    }
    var lastBagLen = length % CHUNK_LEN
    if (lastBagLen == 0) {
      lastBagLen = CHUNK_LEN
    }
    val response = byteArrayOf().pack(HEAD_TO_SENSOR, RH205Consts.CMD_UPGRADE_DATA_RESULT)
    val bagCountB: ByteArray = ByteUtil.intToBytes(totalBagCount)
    val dataOrigin = ByteArray(16)
    val maxRetryCount = 50 // 最大重传次数
    var time = 0L // 用来计时用的
    val retryCount = AtomicInteger(0) // 用来统计重传次数的
    System.arraycopy(snB, 0, dataOrigin, 0, 4)
    System.arraycopy(lengthB, 0, dataOrigin, 4, 4)
    System.arraycopy(crcB, 0, dataOrigin, 8, 4)
    System.arraycopy(bagCountB, 0, dataOrigin, 12, 4)
    val dataPrepare = dataOrigin.pack(HEAD_TO_SENSOR, RH205Consts.CMD_PREPARE_UPGRADE)

    // 进度和速率
    val completeCount = AtomicInteger(0)
    var mills = 0L

    /**
     * 传送升级文件
     */
    fun upgradeData(_bagIndex: Int) {
      mainHandler.post {
        if (time == 0L) {
          time = System.currentTimeMillis()
          mills = System.currentTimeMillis()
        }
        dTag("upgrade_index", _bagIndex.toString())
        val bagIndexB = ByteUtil.intToBytes(_bagIndex)
        val upgradeBag: ByteArray = Arrays.copyOfRange(
          bytesUpgrade, _bagIndex * CHUNK_LEN, (_bagIndex + 1) * CHUNK_LEN
        )
        val upgradeDataOrigin = Arrays.copyOf(bagIndexB, bagIndexB.size + upgradeBag.size)
        System.arraycopy(upgradeBag, 0, upgradeDataOrigin, 4, upgradeBag.size)
        // addHeadCmdLengthAndCs(upgradeDataOrigin, CMD_UPGRADE_DATA)
        val upgradeData = upgradeDataOrigin.pack(HEAD_TO_SENSOR, RH205Consts.CMD_UPGRADE_DATA)

        dTag("upgradeX", "start send $_bagIndex")
        write(upgradeData, object : BleWriteCallback() {
          override fun onWriteSuccess(current: Int, total: Int, justWrite: ByteArray?) {
            val complete = completeCount.incrementAndGet()
            // b/ms , kb/s
            val speed = if (complete < totalBagCount) {
              CHUNK_LEN.toBigDecimal()
                .divide((System.currentTimeMillis() - mills).toBigDecimal(), 2, BigDecimal.ROUND_UP)
                .toFloat()
            } else if(complete == totalBagCount) (
              lastBagLen.toBigDecimal()
                .divide((System.currentTimeMillis() - mills).toBigDecimal(), 2, BigDecimal.ROUND_UP)
                .toFloat()
            ) else {
              0f
            }
            mills = System.currentTimeMillis()
            callback.onProgress(complete, totalBagCount, speed)
            dTag("upgradeX", "send $_bagIndex success")
          }

          override fun onWriteFailure(exception: BleException?) {
            dTag("upgradeX", "send $_bagIndex fail_${exception?.description}")
          }
        })
      }
    }

    fun notifyResult() {
      notify { data ->
        dTag("notify_upgrade_result", data?.toString())
        isReceivedResult.set(true)
        if (isTimeoutResult.get()) return@notify
        if (data != null && data.size == 125 && data[0] == HEAD_FROM_SENSOR && data[1] == RH205Consts.CMD_UPGRADE_DATA_RESULT) {
          val bytesResult = Arrays.copyOfRange(data, 4, 124)
          dTag("upgrade_result", ByteUtil.bytes2BitStr(bytesResult))
          val indexsResult = getBagIndexFromBit(bytesResult, totalBagCount)
          if (indexsResult.isNotEmpty()) {
            singleExecutor.submit {
              retryCount.getAndIncrement()
              dTag("升级重试", "第${retryCount.get()}次")
              if (retryCount.get() > maxRetryCount) {
                mainHandler.post {
                  callback.onUpgradeResult(false, "升级失败, 请重试", null)
                  mainHandler.removeCallbacksAndMessages(null)
                  BleManager.getInstance().removeNotifyCallback(curBleDevice, UUID_UP)
                }
                return@submit
              }
              doSleep(200)
              indexsResult.forEach {
                doSleep(transferInterval)
                upgradeData(it)
              }
              doRetry(200, { write(response) }, 0, 2, 5000, isReceivedResult, isTimeoutResult) {
                mainHandler.post {
                  callback.onUpgradeResult(false, "请求结果1超时, 升级失败", null)
                  mainHandler.removeCallbacksAndMessages(null)
                  BleManager.getInstance().removeNotifyCallback(curBleDevice, UUID_UP)
                }
              }
            }
          } else {
            val time = System.currentTimeMillis() - time
            dTag("升级耗时", (time / 1000).toString())
            mainHandler.post { callback.onUpgradeResult(true, "升级成功", time) }
          }
        } else {
          dTag("upgradeResult", "失败")
          mainHandler.post {
            callback.onUpgradeResult(false, "升级失败, 返回格式有误", null)
            mainHandler.removeCallbacksAndMessages(null)
            BleManager.getInstance().removeNotifyCallback(curBleDevice, UUID_UP)
          }
        }
      }
    }

    curFuture?.cancel(true)
    mainHandler.removeCallbacksAndMessages(null)
    curFuture = singleExecutor.submit {
      notify { data ->
        dTag("notify_prepare_upgrade", data?.toString())
        isReceivedPrepare.set(true)
        if (isTimeoutPrepare.get()) return@notify
        if (data == null || data.size != 6 || data[4] != 0x01.toByte() || data[0] != HEAD_FROM_SENSOR || data[1] != RH205Consts.CMD_PREPARE_UPGRADE) {
          mainHandler.post { callback.onUpgradeResult(false, "准备升级失败", null) }
        } else {
          singleExecutor.submit {
            doSleep(200)
            notifyResult()
            doSleep(200)
            time = System.currentTimeMillis()
            for (i in 0 until totalBagCount) {
              doSleep(transferInterval)
              upgradeData(i)
            }
            doRetry(200, { write(response) }, 0, 2, 1000, isReceivedResult, isTimeoutResult) {
              mainHandler.post {
                callback.onUpgradeResult(false, "请求结果0超时, 升级失败", null)
                mainHandler.removeCallbacksAndMessages(null)
                BleManager.getInstance().removeNotifyCallback(curBleDevice, UUID_UP)
              }
            }
          }
        }
      }
      doRetry(200, { write(dataPrepare) }, 0, 2, 5000, isReceivedPrepare, isTimeoutPrepare) {
        mainHandler.post {
          callback.onUpgradeResult(false, "升级失败, 超时", null)
          mainHandler.removeCallbacksAndMessages(null)
          BleManager.getInstance().removeNotifyCallback(curBleDevice, UUID_UP)
        }
      }
    }
  }
}
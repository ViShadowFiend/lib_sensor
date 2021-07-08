package com.ronds.eam.lib_sensor

import android.app.Application
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.clj.fastble.BleManager
import com.clj.fastble.callback.BleGattCallback
import com.clj.fastble.callback.BleMtuChangedCallback
import com.clj.fastble.callback.BleNotifyCallback
import com.clj.fastble.callback.BleReadCallback
import com.clj.fastble.callback.BleScanCallback
import com.clj.fastble.callback.BleWriteCallback
import com.clj.fastble.data.BleDevice
import com.clj.fastble.exception.BleException
import com.clj.fastble.scan.BleScanRuleConfig
import com.ronds.eam.lib_sensor.consts.UUID_DOWN
import com.ronds.eam.lib_sensor.consts.UUID_SERVICE
import com.ronds.eam.lib_sensor.consts.UUID_UP
import com.ronds.eam.lib_sensor.utils.ByteUtil
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.experimental.or

abstract class ABleMgr {
  protected companion object {
    const val TAG = "BleMgr"
    var isDebug: Boolean = false

    const val TIMEOUT = 1000 * 10L
  }

  internal abstract val TIP_DISCONNECT: String
  internal abstract val TIP_TIMEOUT: String

  protected val mainHandler = Handler(Looper.getMainLooper())

  protected val bleDevices = mutableListOf<BleDevice>()
  protected var curBleDevice: BleDevice? = null

  protected var curFuture: Future<*>? = null
  protected val singleExecutor: ExecutorService = Executors.newSingleThreadExecutor()

  protected var isScanning = false

  protected val ruleBuilder: BleScanRuleConfig.Builder = BleScanRuleConfig.Builder()
    //      .setServiceUuids(serviceUuids)      // 只扫描指定的服务的设备，可选
    //      .setDeviceName(true, names)         // 只扫描指定广播名的设备，可选
    //      .setDeviceMac(mac)                  // 只扫描指定mac的设备，可选
    //      .setAutoConnect(isAutoConnect)      // 连接时的autoConnect参数，可选，默认false
    .setScanTimeOut(TIMEOUT)              // 扫描超时时间，可选，默认10秒

  protected var onLog: ((String) -> Unit)? = null

  fun setLogCallback(c: (s: String) -> Unit) {
    this.onLog = c
  }

  fun removeLogCallback() {
    this.onLog = null
  }

  /**
   * 初始化
   *
   * @param application
   */
  fun init(application: Application) {
    isDebug = BuildConfig.DEBUG
    BleManager.getInstance().run {
      init(application)
      enableLog(isDebug)
      setReConnectCount(1, 5000)
      operateTimeout = TIMEOUT.toInt()
      initScanRule(ruleBuilder.build())
    }
  }

  fun scan(
    scanCallback: ScanCallback,
    buildScanRule: (BleScanRuleConfig.Builder.() -> Unit)? = null
  ) {
    if (buildScanRule != null) {
      BleManager.getInstance()
        .initScanRule(BleScanRuleConfig.Builder().apply(buildScanRule).build())
    }
    curFuture?.cancel(true)
    mainHandler.removeCallbacksAndMessages(null)
    curFuture = singleExecutor.submit {
      mainHandler.post {
        scanCallback.onScanStart()
      }
      doSleep(1000)
      BleManager.getInstance().scan(object : BleScanCallback() {
        override fun onScanFinished(scanResultList: MutableList<BleDevice>?) {
          isScanning = false
          bleDevices.clear()
          bleDevices.addAll(scanResultList ?: emptyList())
          mainHandler.post { scanCallback.onScanEnd() }
        }

        override fun onScanStarted(success: Boolean) {
          if (success) isScanning = true
          bleDevices.clear()
          val connectedDevices = getAllConnectDevices()
          bleDevices.addAll(connectedDevices)
          mainHandler.post { scanCallback.onScanResult(bleDevices) }
        }

        override fun onScanning(bleDevice: BleDevice?) {
          if (bleDevice?.name != null && bleDevices.none { it.mac == bleDevice.mac }) {
            bleDevices.add(bleDevice)
          }
          mainHandler.post { scanCallback.onScanResult(bleDevices) }
        }
      })
    }
  }

  fun stopScan() {
    isScanning = false
    singleExecutor.submit {
      try {
        doSleep(1000)
        BleManager.getInstance().cancelScan()
        doSleep(1000)
      }
      catch (e: Exception) {
        dTag("stop_scan", e)
      }
    }
  }

  fun isConnected(bleDevice: BleDevice?): Boolean {
    return bleDevice != null && BleManager.getInstance().isConnected(bleDevice)
  }

  fun isConnected(): Boolean {
    return isConnected(curBleDevice)
  }

  fun isConnected(mac: String?): Boolean {
    return mac != null && BleManager.getInstance().isConnected(mac)
  }

  fun disConnectAllDevices(disconnectCallback: DisconnectCallback) {
    curFuture?.cancel(true)
    mainHandler.removeCallbacksAndMessages(null)
    curFuture = singleExecutor.submit {
      mainHandler.post { disconnectCallback?.onDisconnectStart() }
      doSleep(1000)
      BleManager.getInstance().disconnectAllDevice()
      doSleep(1000)
      mainHandler.post { disconnectCallback?.onDisconnectEnd() }
    }
  }

  fun disconnect(bleDevice: BleDevice?, disconnectCallback: DisconnectCallback) {
    singleExecutor.submit {
      mainHandler.post {
        disconnectCallback?.onDisconnectStart()
      }
      doSleep(1000)
      if (!isConnected(bleDevice)) {
        mainHandler.post {
          disconnectCallback?.onDisconnectEnd()
        }
        return@submit
      }
      BleManager.getInstance().disconnect(bleDevice)
      doSleep(1000)
      mainHandler.post {
        disconnectCallback?.onDisconnectEnd()
      }
    }
  }

  fun getAllConnectDevices(): MutableList<BleDevice> {
    return BleManager.getInstance().allConnectedDevice ?: mutableListOf()
  }

  fun connect(bleDevice: BleDevice?, connectStatusCallback: ConnectStatusCallback) {
    if (bleDevice == null) {
      connectStatusCallback?.onConnectFail(null, "ble 设备为空")
      return
    }
    curFuture?.cancel(true)
    mainHandler.removeCallbacksAndMessages(null)
    //		disposableReconnect?.dispose()
    curFuture = singleExecutor.submit {
      if (isConnected(bleDevice)) {
        mainHandler.post {
          connectStatusCallback?.onConnectSuccess(bleDevice)
        }
        return@submit
      }
      doSleep(300)
      BleManager.getInstance().connect(bleDevice, object : BleGattCallback() {
        override fun onStartConnect() {
          //					toast("正在连接")
          connectStatusCallback?.onConnectStart()
          dTag("connect", "开始连接${bleDevice.mac}")
        }

        override fun onDisConnected(
          isActiveDisConnected: Boolean,
          device: BleDevice?,
          gatt: BluetoothGatt?,
          status: Int
        ) {
          connectStatusCallback?.onDisconnected(device)
        }

        override fun onConnectSuccess(bleDevice: BleDevice?, gatt: BluetoothGatt?, status: Int) {
          //						canReconnect.set(true)
          singleExecutor.execute {
            curBleDevice = bleDevice
            doSleep(200)
            BleManager.getInstance().setMtu(bleDevice, 250, object : BleMtuChangedCallback() {
              override fun onMtuChanged(mtu: Int) {
                dTag("setMtu_success", mtu)
              }

              override fun onSetMTUFailure(exception: BleException?) {
                dTag("setMtu_fail", exception?.description)
              }
            })
            doSleep(200)
            mainHandler.post {
              connectStatusCallback?.onConnectSuccess(bleDevice)
            }
          }
        }

        override fun onConnectFail(bleDevice: BleDevice?, exception: BleException?) {
          curBleDevice = null
          connectStatusCallback?.onConnectFail(bleDevice, exception?.description)
        }
      })
    }
  }

  fun isLocationEnable(context: Context?): Boolean {
    context ?: return false
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val networkProvider = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    val gpsProvider = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    return networkProvider || gpsProvider
  }

  /**
   * 判断 Android 手机是否支持 ble
   *
   * @return true - 支持, false - 不支持
   */
  fun isSupportBle(): Boolean {
    return BleManager.getInstance().isSupportBle
  }

  /**
   * 判断蓝牙是否开启
   *
   * @return true - 启用, false - 关闭
   */
  fun isBluetoothEnabled(): Boolean {
    return BleManager.getInstance().isBlueEnable
  }

  fun onDestroy() {
    mainHandler.removeCallbacksAndMessages(null)
    BleManager.getInstance().clearCharacterCallback(curBleDevice)
  }

  protected fun write(
    bleDevice: BleDevice?,
    uuid_service: String?,
    uuid_write: String?,
    write_data: ByteArray?,
    bleWriteCallback: BleWriteCallback?
  ) {
    if (bleDevice == null || uuid_service == null || uuid_write == null || write_data == null) return
    onLog?.invoke("WRITE ${ByteUtil.byteArray2HexString(write_data)}")
    BleManager.getInstance().write(bleDevice, uuid_service, uuid_write, write_data, false,
      object : BleWriteCallback() {
        override fun onWriteSuccess(current: Int, total: Int, justWrite: ByteArray?) {
          onLog?.invoke("WRITE 成功 ${ByteUtil.byteArray2HexString(write_data)}")
          bleWriteCallback?.onWriteSuccess(current, total, justWrite)
        }

        override fun onWriteFailure(exception: BleException?) {
          onLog?.invoke("WRITE 失败 ${exception}")
          bleWriteCallback?.onWriteFailure(exception)
        }
      })
  }

  protected fun write(write_data: ByteArray?, bleWriteCallback: BleWriteCallback? = null) {
    write(curBleDevice, UUID_SERVICE, UUID_DOWN, write_data, bleWriteCallback)
  }

  protected fun read(
    bleDevice: BleDevice?,
    uuid_service: String?,
    uuid_read: String?,
    bleReadCallback: BleReadCallback?
  ) {
    if (bleDevice == null) return
    BleManager.getInstance().read(bleDevice, uuid_service, uuid_read, object : BleReadCallback() {
      override fun onReadSuccess(data: ByteArray?) {
        onLog?.invoke("READ 成功 ${ByteUtil.byteArray2HexString(data)}")
        bleReadCallback?.onReadSuccess(data)
      }

      override fun onReadFailure(exception: BleException?) {
        onLog?.invoke("READ 失败 ${exception}")
        bleReadCallback?.onReadFailure(exception)
      }
    })
  }

  protected fun read(bleReadCallback: BleReadCallback) {
    read(curBleDevice, UUID_SERVICE, UUID_DOWN, bleReadCallback)
  }

  protected fun notify(onReceived: (data: ByteArray?) -> Unit) {
    BleManager.getInstance().clearCharacterCallback(curBleDevice)
    BleManager.getInstance().notify(
      curBleDevice, UUID_SERVICE, UUID_UP, object : BleNotifyCallback() {
      override fun onCharacteristicChanged(data: ByteArray?) {
        onLog?.invoke("Notify 收到 ${ByteUtil.byteArray2HexString(data)}")
        onReceived(data)
      }

      override fun onNotifyFailure(exception: BleException?) {
        onLog?.invoke("Notify 失败 ${exception}")
        dTag("notify_", exception)
      }

      override fun onNotifySuccess() {
        onLog?.invoke("Notify 成功.")
      }
    })
  }

  protected fun doRetry(
    delayBefore: Long,
    action: (() -> Unit),
    delayAfter: Long,
    retryCount: Int,
    retryTimeout: Long,
    isReceived: AtomicBoolean,
    isTimeout: AtomicBoolean,
    toDoWhenTimeout: (() -> Unit)
  ) {
    isReceived.set(false)
    isTimeout.set(false)
    thread {
      if (delayBefore > 0) {
        doSleep(delayBefore)
      }
      mainHandler.post(action)
      if (delayAfter > 0) {
        doSleep(delayAfter)
      }

      doSleep(retryTimeout)
      if (retryCount <= 0) {
        if (!isReceived.get()) {
          isTimeout.set(true)
          toDoWhenTimeout()
        }
      }
      else {
        for (a in 0 until retryCount) {
          if (!isReceived.get()) {
            action()
            doSleep(retryTimeout)
          }
        }
        if (!isReceived.get()) {
          isTimeout.set(true)
          toDoWhenTimeout()
        }
      }
    }
  }

  private var timerFuture: Future<*>? = null

  protected fun doTimeout(
    delayBefore: Long,
    action: (() -> Unit)? = null,
    delayAfter: Long,
    retryCount: Int = 2,
    retryTimeout: Int = 500,
    isReceived: AtomicBoolean = AtomicBoolean(false),
    isTimeout: AtomicBoolean = AtomicBoolean(false),
    toDoWhenTimeout: (() -> Unit)? = null
  ) {
    timerFuture?.cancel(true)
    timerFuture = singleExecutor.submit {
      isReceived.set(false)
      isTimeout.set(false)
      if (delayBefore > 0) {
        doSleep(delayBefore)
      }

      action?.invoke()

      if (delayAfter > 0) {
        doSleep(delayAfter)
      }

      val sleepUnit = 10L // 每次休眠10 ms
      val sleepCount: Int = retryTimeout / sleepUnit.toInt()

      var index = 0
      var count = retryCount

      while (!isReceived.get() && !isTimeout.get()) {
        index++
        doSleep(sleepUnit)
        if (index == sleepCount) {
          if (count <= 0) {
            isTimeout.set(true)
            //                        stopNotify()
            //                        toastLong("响应超时")
            toDoWhenTimeout?.invoke()
            break
          }
          else {
            count--
            action?.invoke()
            index = 0
          }
        }
      }
    }
  }

  protected fun updateBit(bytes: ByteArray?, bitIndex: Int) {
    if (bytes == null) {
      dTag("updateBit_", "bytes null")
      return
    }
    if (bitIndex < 0) {
      dTag("updateBit_", "index < 0")
      return
    }
    if (bitIndex > bytes.size * 8 - 1) {
      dTag("updateBit_", "out of bounds")
      return
    }
    val byteIndex = (bitIndex / 8).toInt()
    val bitIndexPerByte = (bitIndex % 8).toInt()
    bytes[byteIndex] = bytes[byteIndex] or flags[bitIndexPerByte]
  }

  protected fun getBagIndexFromBit(bytes: ByteArray, totalBagCount: Int): List<Int> {
    if (totalBagCount <= 0 || totalBagCount > bytes.size * 8) {
      dTag("getBagIndexFromBit", "out of bounds")
    }
    val ret = mutableListOf<Int>()
    var index = 0
    for (element in bytes) {
      for (j in 0 until 8) {
        if (element.toInt().shr(j) and 0x01 == 0x00) {
          ret.add(index)
        }
        index++
        if (index >= totalBagCount) {
          return ret
        }
      }
    }
    return ret
  }

  protected val flags = byteArrayOf(
    0x01.toByte(), 0x02.toByte(), 0x04.toByte(), 0x08.toByte(),
    0x10.toByte(), 0x20.toByte(), 0x40.toByte(), 0x80.toByte()
  )

  protected fun stopNotify() {
    BleManager.getInstance().stopNotify(curBleDevice, UUID_SERVICE, UUID_UP)
  }

  protected fun clearCharacterCallback() {
    BleManager.getInstance().clearCharacterCallback(curBleDevice)
  }

  protected fun release() {
    curFuture?.cancel(true)
    // stopNotify()
    clearCharacterCallback()
    mainHandler.removeCallbacksAndMessages(null)
  }

  protected fun doSleep(mills: Long = 100) {
    try {
      Thread.sleep(mills)
    }
    catch (e: Exception) {
      d(e)
    }
  }

  protected fun d(log: String?) {
    if (isDebug && log != null) {
      Log.d(TAG, log)
    }
  }

  protected fun dTag(tag: String?, log: Any?) {
    if (isDebug && log != null) {
      // Log.d("${TAG}_$tag", log.toString())
      println("${tag}_${log}")
    }
  }

  protected fun d(exception: java.lang.Exception?) {
    if (isDebug && exception != null) {
      Log.d(TAG, exception.message ?: "")
    }
  }
}
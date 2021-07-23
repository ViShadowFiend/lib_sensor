package com.ronds.eam.lib_sensor

import com.ronds.eam.lib_sensor.utils.ByteUtil
import com.ronds.eam.lib_sensor.utils.MathUtil
import com.ronds.eambletoolkit.Spectrum
import com.ronds.eambletoolkit.VibDataProcessUtil
import java.util.Arrays

class VibResult private constructor(private val builder: Builder) {

  /**
   * 第 1 步:
   * 原始数据转为 shortArray
   */
  private fun ByteArray.toShortArray(): ShortArray {
    return ByteUtil.bytesToShorts(this)
  }

  /**
   * 第 2 步:
   * 去平均
   *
   * @param accCoe 加速度系数
   */
  private fun ShortArray.avg(accCoe: Float): DoubleArray {
    if (isEmpty()) {
      return doubleArrayOf()
    }
    val unit: Double = average()
    return map { (it - unit) * accCoe }.toDoubleArray()
  }

  /**
   * 求总值
   * @param paraType 0 - 有效值
   *                 1 - 峰值
   *                 2 - 峰峰值
   *                 3 - 峭度值
   */
  private fun DoubleArray.totalValue(paraType: Int): Double {
    return when (paraType) {
      0 -> MathUtil.value(this)
      1 -> MathUtil.fValue(this)
      2 -> MathUtil.ffValue(this)
      3 -> MathUtil.qdValue(this)
      else -> 0.0
    }
  }

  /**
   * 加速度波形转为速度波形
   *
   * @param f 采样频率
   * @param fMin 下限频率
   * @param fMax 上限频率
   */
  private fun DoubleArray.toVel(f: Float, fMin: Float, fMax: Float): DoubleArray {
    if (isEmpty()) {
      return doubleArrayOf()
    }
    val r = VibDataProcessUtil.accToVel(this, f.toDouble(), fMin.toDouble(), fMax.toDouble())
    return r.map { it * 1000 }.toDoubleArray()
  }

  /**
   * 加速度波形转为位移波形
   *
   * @param f 采样频率
   * @param fMin 下限频率
   * @param fMax 上限频率
   */
  private fun DoubleArray.toDist(f: Float, fMin: Float, fMax: Float): DoubleArray {
    if (isEmpty()) {
      return doubleArrayOf()
    }
    var r: DoubleArray
    if (fMax == 100f || fMax == 200f) {
      r = VibDataProcessUtil.accToDist(this, f.toDouble() * 5, fMin.toDouble(), fMax.toDouble() * 5)
      r = DoubleArray(r.size / 5) { r[5 * it] }
    } else {
      r = VibDataProcessUtil.accToDist(this, f.toDouble(), fMin.toDouble(), fMax.toDouble())
    }
    return r.map { it * 100_0000 }.toDoubleArray()
  }

  /**
   * 波形数据转换为频谱
   *
   * @param freq 采样频率
   */
  private fun DoubleArray.toSpectrum(freq: Float): DoubleArray {
    if (isEmpty()) {
      return doubleArrayOf()
    }
    val spectrum = Spectrum()
    VibDataProcessUtil.fft(spectrum, this, freq.toDouble())
    var r = spectrum.amplitude
    // val dx = builder.freqSample / builder.len.toDouble()
    // val range = builder.freqAnalysis / dx
    // val range = builder.freqAnalysis * builder.len.toDouble() / builder.freqSample
    val range = builder.len.toDouble() / builder.freqCoe

    // kotlin 的这个 copyOfRange 不会取两个 arr 的最小 len, 会直接 IndexOutOfBoundsException
    // r = r.copyOfRange(0, range.toInt())
    r = Arrays.copyOfRange(r, 0, range.toInt())
    return r
  }

  /**
   * 转换系数
   */
  private fun DoubleArray.convertCoe(): Double {
    return MathUtil.absMaxValue(this) / 16384
  }

  private fun DoubleArray.toSavedBytes(): ByteArray {
    val coe = convertCoe()
    val ret = ByteArray(size * 2)
    for ((i, e) in this.withIndex()) {
      val s = (e / coe).toFloat().toInt()
      //注意在此处因服务端格式问题进行了高低位转换
      ret[2 * i + 0] = (s shr 8 and 0xff).toByte()
      ret[2 * i + 1] = (s and 0xff).toByte()
    }
    return ret
  }

  // 波形数据
  private var waveData: DoubleArray = doubleArrayOf()

  // 频谱数据
  private var spectrumData: DoubleArray = doubleArrayOf()

  // 波形数据
  private fun waveData(): DoubleArray {
    if (waveData.isNotEmpty()) {
      return waveData
    }
    waveData = with(builder) {
      val d: DoubleArray = data.toShortArray().avg(accCoe)
      when (signalType) {
        1 -> d.toVel(freqSample, freqLower, freqUpper)
        2 -> d.toDist(freqSample, freqLower, freqUpper)
        else -> d
      }
    }
    return waveData
  }

  // 频谱数据
  private fun spectrumData(waveData: DoubleArray): DoubleArray {
    if (spectrumData.isNotEmpty()) {
      return spectrumData
    }
    spectrumData = waveData.toSpectrum(builder.freqSample)
    return spectrumData
  }

  /**
   * 总值
   */
  fun totalValue(): Double {
    return waveData().totalValue(builder.paraType)
  }

  /**
   * 波形或频谱图 x 轴最小间隔
   * samplingMode - 0 - 时域波形 - 单位为 ms
   * samplingMode - 1 - 频谱 - 单位为 Hz
   */
  fun dx(): Double {
    return when(builder.samplingMode) {
      1 -> {
        val size = data().size
        return if (size <= 1) {
          1.0
        } else {
          builder.freqSample / builder.len.toDouble()
        }
      }
      else -> 1000.0 / builder.freqSample
    }
  }

  /**
   * 波形或频谱图 x 轴最大值
   */
  fun xMax(): Double {
    return when(builder.samplingMode) {
      1 -> (data().size - 1) * dx()
      else -> (builder.len) / (builder.freqSample) * 1000.0
    }
  }

  /**
   * 最小
   */
  fun min(): Double {
    return MathUtil.min(data())
  }

  /**
   * 最大
   */
  fun max(): Double {
    return MathUtil.max(data())
  }

  /**
   * 波形或频谱数据, 根据 builder 中设置的 samplingMode
   */
  fun data(): DoubleArray {
    val waveData = waveData()
    return when (builder.samplingMode) {
      1 -> spectrumData(waveData)
      else -> waveData
    }
  }

  /**
   * 需上传给 eam 服务器的格式, 经过一定的变换, 用来保存为本地文件
   */
  fun savedBytes(): ByteArray {
    return data().toSavedBytes()
  }

  /**
   * 转换系数, 即 savedBytes 使用的变换系数
   */
  fun convertCoe(): Double {
    return data().convertCoe()
  }

  /**
   * @param freqCoe 分析频率转采样频率的系数. 一般为 2.56, RH205 因为硬件时钟的原因. 采样频率 = 分析频率 * 2.5
   */
  class Builder(val freqCoe: Float = 2.56f) {
    // 原始数据
    lateinit var data: ByteArray

    // 加速度系数
    var accCoe: Float = 0f

    // 0 - 加速度, 1 - 速度, 2 - 位移
    var signalType: Int = 0

    // 0 - 时域波形, 1 - 频谱
    var samplingMode: Int = 0

    // 0 - 有效值, 1 - 峰值, 2 - 峰峰值, 3 - 峭度值
    var paraType: Int = 0

    // 分析频率
    var freqAnalysis: Float = 1000f

    // 采样频率, hz
    // 采样频率 = 分析频率 * 2.56.
    //
    // 下发给下位机的是分析频率.
    // iEAM(RH517) 把上限频率当作分析频率下发, 逻辑中采样频率为 上限频率 * 2.56.
    // RH205 因为硬件时钟的原因. 采样频率 = 分析频率 * 2.5
    internal val freqSample: Float
      get() = freqAnalysis * freqCoe

    // 下限频率, hz
    var freqLower: Float = 10f
      set(value) {
        field = if (value < 10f) 10f else value
      }
      get() {
        if (field < 10f) {
          return 10f
        }
        return field
      }

    // 上限频率, hz
    private var _freqUpper: Float? = null
    var freqUpper: Float
    get() {
      return _freqUpper ?: freqAnalysis
    }
    set(value) {
      _freqUpper = value
    }

    // 采集长度, 多少个点. 1 个点为 2 个 byte(short).
    var len: Int = 1 * 1024

    fun build(): VibResult = VibResult(this)
  }
}
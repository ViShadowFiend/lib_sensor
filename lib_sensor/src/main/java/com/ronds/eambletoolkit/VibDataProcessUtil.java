package com.ronds.eambletoolkit;

/**
 * 516 测振数据处理 jni
 * <p>jni 包名不可修改</p>
 *
 * @author An.Wang 2019/8/9 15:07.
 */
public class VibDataProcessUtil {
  static {
    System.loadLibrary("VibDataProcess");
  }

  /**
   * 加速度转位移
   *
   * @param acc 原始加速度数据
   * @param f 采集频率, Hz
   * @param fMin 下限频率, Hz
   * @param fMax 上限频率, Hz
   * @return 转化的位移数据
   */
  public static native double[] accToDist(double[] acc, double f, double fMin, double fMax);

  /**
   * 加速度转速度
   *
   * @param acc 原始加速度数据
   * @param f 采集频率, Hz
   * @param fMin 下限频率, Hz
   * @param fMax 上限频率, Hz
   * @return 转化的速度数据
   */
  public static native double[] accToVel(double[] acc, double f, double fMin, double fMax);

  /**
   * 波形转频谱
   *
   * @param spectrum 转频谱媒介
   * @param data 原始加速度/速度/位移波形数据
   * @param f 采样频率, Hz
   */
  public static native void fft(Spectrum spectrum, double[] data, double f);
}

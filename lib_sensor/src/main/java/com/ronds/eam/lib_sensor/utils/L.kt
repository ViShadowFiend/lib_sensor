package com.ronds.eam.lib_sensor.utils

import android.util.Log


object L {
  private var tag: String = "lib_sensor"

  fun setGlobalTag(tag: String) {
    this.tag = tag
  }

  fun println(priority: Int, tag: String?, any: Any?, throwable: Throwable? = null) {
    val msg = any?.toString() ?: "__NULL__"
    val t = Log.getStackTraceString(throwable)
    Log.println(priority, tag, "$msg\n$t")
  }

  fun a(any: Any?, tag: String? = this.tag, throwable: Throwable? = null) {
    println(Log.ASSERT, tag, any, throwable)
  }

  fun e(any: Any?, tag: String? = this.tag, throwable: Throwable? = null) {
    println(Log.ERROR, tag, any, throwable)
  }

  fun d(any: Any?, tag: String? = this.tag, throwable: Throwable? = null) {
    println(Log.DEBUG, tag, any, throwable)
  }
}
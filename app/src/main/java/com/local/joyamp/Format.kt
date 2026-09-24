package com.local.joyamp

/** 83_000 -> "1:23", 3_723_000 -> "1:02:03". */
fun fmtTime(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

package com.onsupporter.ptt

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * **해가 떴나.** 지도 색 「자동」 이 이것을 본다 - 해가 지면 어두운 바탕,
 * 뜨면 밝은 바탕.
 *
 * 밖에 묻지 않는다. 일출·일몰은 위도·경도·날짜만 있으면 셈으로 나온다
 * (NOAA 의 일출 공식, 대기 굴절과 해의 크기를 넣은 -0.833°). 몇 분
 * 어긋나도 바탕 색을 바꾸는 데는 넉넉하다.
 */
object Sun {
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    /**
     * 율리우스 일 n 의 해 뜨고 지는 때(epoch ms). 해가 안 지거나 안 뜨는
     * 날(백야·극야)은 null.
     */
    private fun day(n: Long, lat: Double, lon: Double): Pair<Long, Long>? {
        val jStar = n - lon / 360.0
        val m = (357.5291 + 0.98560028 * jStar) % 360.0
        val c = 1.9148 * sin(rad(m)) + 0.02 * sin(rad(2 * m)) + 0.0003 * sin(rad(3 * m))
        val lambda = (m + c + 180.0 + 102.9372) % 360.0
        val transit = 2451545.0 + jStar + 0.0053 * sin(rad(m)) - 0.0069 * sin(rad(2 * lambda))
        val sinD = sin(rad(lambda)) * sin(rad(23.4397))
        val cosD = cos(asin(sinD))
        val cosW = (sin(rad(-0.833)) - sin(rad(lat)) * sinD) / (cos(rad(lat)) * cosD)
        if (cosW < -1 || cosW > 1) return null
        val w = deg(acos(cosW)) / 360.0
        fun ms(j: Double) = ((j - 2440587.5) * 86_400_000.0).roundToLong()
        return ms(transit - w) to ms(transit + w)
    }

    /**
     * 지금 밤인가. 어제·오늘·내일 셋을 다 본다 - 「오늘」 을 UTC 로
     * 자르면 한국의 이른 아침이 어제로 읽혀, 해 뜬 뒤에도 밤으로 나온다.
     * 셋 중 어느 낮에도 안 들면 밤이다.
     */
    fun dark(lat: Double, lon: Double, now: Long = System.currentTimeMillis()): Boolean {
        val n = (now / 86_400_000.0 + 2440587.5 - 2451545.0 + 0.0008).roundToLong()
        var known = false
        for (d in n - 1..n + 1) {
            val (rise, set) = day(d, lat, lon) ?: continue
            known = true
            if (now in rise..set) return false
        }
        return known
    }
}

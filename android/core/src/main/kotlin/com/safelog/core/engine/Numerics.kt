package com.safelog.core.engine

import kotlin.math.abs
import kotlin.math.floor

/**
 * NumPy-equivalent helpers. They reproduce the exact semantics the Python engine relies on (pairwise summation,
 * np.interp, np.unwrap, np.convolve 'same', scipy median_filter mode='nearest', nanpercentile 'linear'),
 * so the Kotlin port gives the same judgments as the reference engine (see ParityTest).
 */
internal object Np {
    fun isFin(x: Double) = !x.isNaN() && !x.isInfinite()

    /** numpy pairwise summation (used by np.sum / np.mean / np.std on contiguous float64 arrays). */
    fun pairwiseSum(a: DoubleArray, from: Int = 0, to: Int = a.size): Double {
        val n = to - from
        if (n < 8) {
            var r = 0.0
            for (i in from until to) r += a[i]
            return r
        } else if (n <= 128) {
            val r = DoubleArray(8) { a[from + it] }
            var i = from + 8
            while (i < from + n - (n % 8)) {
                for (k in 0 until 8) r[k] += a[i + k]
                i += 8
            }
            var res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]))
            while (i < to) { res += a[i]; i++ }
            return res
        } else {
            var n2 = n / 2
            n2 -= n2 % 8
            return pairwiseSum(a, from, from + n2) + pairwiseSum(a, from + n2, to)
        }
    }

    fun sum(a: DoubleArray) = pairwiseSum(a)
    fun mean(a: DoubleArray): Double = if (a.isEmpty()) Double.NaN else pairwiseSum(a) / a.size
    fun std(a: DoubleArray): Double {
        if (a.isEmpty()) return Double.NaN
        val m = mean(a)
        val d = DoubleArray(a.size) { (a[it] - m) * (a[it] - m) }
        return kotlin.math.sqrt(pairwiseSum(d) / a.size)
    }

    /** Mean of a boolean mask (numpy: mean of bool -> float64; counts are exact). */
    fun meanMask(m: BooleanArray): Double = if (m.isEmpty()) Double.NaN else m.count { it }.toDouble() / m.size

    fun nanToNum(a: DoubleArray, nan: Double = 0.0) = DoubleArray(a.size) { if (a[it].isNaN()) nan else a[it] }

    fun cumsum(a: DoubleArray): DoubleArray {
        val out = DoubleArray(a.size)
        var s = 0.0
        for (i in a.indices) { s += a[i]; out[i] = s }
        return out
    }

    /** np.convolve(x, ones(k)/k, mode='same') for odd or even k (zero padding). */
    fun movingAverageSame(x: DoubleArray, k: Int): DoubleArray {
        val n = x.size
        val w = 1.0 / k
        val start = (k - 1) / 2
        val out = DoubleArray(n)
        for (i in 0 until n) {
            // full[m] = sum_j x[m-j] * w[j]; same[i] = full[i + start]
            val m = i + start
            var s = 0.0
            // numpy correlates with the reversed kernel and BLAS ddot sums short vectors in index order:
            // add x[m-k+1] .. x[m] ascending, exactly like np.convolve (bit-identical results).
            for (j in k - 1 downTo 0) {
                val idx = m - j
                if (idx in 0 until n) s += x[idx] * w
            }
            out[i] = s
        }
        return out
    }

    /** np.convolve(x, ones(k), 'same') > 0 for a 0/1 signal: any non-zero within the window. */
    fun anyWithinSame(x: BooleanArray, k: Int): BooleanArray {
        val n = x.size
        val start = (k - 1) / 2
        val pref = IntArray(n + 1)
        for (i in 0 until n) pref[i + 1] = pref[i] + if (x[i]) 1 else 0
        return BooleanArray(n) { i ->
            val m = i + start
            val lo = maxOf(0, m - k + 1)
            val hi = minOf(n - 1, m)
            hi >= lo && pref[hi + 1] - pref[lo] > 0
        }
    }

    /** scipy.ndimage.median_filter(x, size=k (odd), mode='nearest'). Sliding sorted window, exact. */
    fun medianFilterNearest(x: DoubleArray, k: Int): DoubleArray {
        val n = x.size
        if (n == 0) return DoubleArray(0)
        val h = k / 2
        fun at(i: Int) = x[i.coerceIn(0, n - 1)]
        val win = DoubleArray(k)
        for (j in 0 until k) win[j] = at(-h + j)
        win.sort()
        val out = DoubleArray(n)
        for (i in 0 until n) {
            out[i] = win[h]
            if (i + 1 < n) {
                val old = at(i - h)
                val nw = at(i + 1 + h)
                // remove old
                var p = java.util.Arrays.binarySearch(win, old)
                if (p < 0) p = win.indexOfFirst { it == old || (it.isNaN() && old.isNaN()) }
                System.arraycopy(win, p + 1, win, p, k - 1 - p)
                // insert new into win[0..k-2]
                var q = 0
                while (q < k - 1 && win[q] <= nw) q++
                System.arraycopy(win, q, win, q + 1, k - 1 - q)
                win[q] = nw
            }
        }
        return out
    }

    /** np.interp(x, xp, fp) with xp increasing (ints here). */
    fun interp(x: Int, xp: IntArray, fp: DoubleArray, slopes: DoubleArray, jHint: IntArray): Double {
        val last = xp.size - 1
        if (x <= xp[0]) return if (x == xp[0]) fp[0] else fp[0]
        if (x >= xp[last]) return fp[last]
        var j = jHint[0]
        while (j < last && xp[j + 1] <= x) j++
        while (j > 0 && xp[j] > x) j--
        jHint[0] = j
        if (x == xp[j]) return fp[j]
        var r = slopes[j] * (x - xp[j]) + fp[j]
        if (r.isNaN()) {
            r = slopes[j] * (x - xp[j + 1]) + fp[j + 1]
            if (r.isNaN() && fp[j] == fp[j + 1]) r = fp[j]
        }
        return r
    }

    /** derive.fill_gaps: linear interpolation across gaps no longer than maxAgeS; longer gaps stay NaN. */
    fun fillGaps(x: DoubleArray, hz: Double, maxAgeS: Double): DoubleArray {
        val n = x.size
        val vi = (0 until n).filter { isFin(x[it]) }.toIntArray()
        if (vi.size < 2) return DoubleArray(n) { Double.NaN }
        val fv = DoubleArray(vi.size) { x[vi[it]] }
        val slopes = DoubleArray(vi.size - 1) { (fv[it + 1] - fv[it]) / (vi[it + 1] - vi[it]).toDouble() }
        val hint = IntArray(1)
        val out = DoubleArray(n)
        var p = 0 // position in vi for searchsorted
        for (idx in 0 until n) {
            out[idx] = interp(idx, vi, fv, slopes, hint)
            while (p < vi.size && vi[p] < idx) p++          // searchsorted(vi, idx, 'left')
            val nxt = p.coerceAtMost(vi.size - 1)
            val prv = (p - 1).coerceIn(0, vi.size - 1)
            val gap = (vi[nxt] - vi[prv]) / hz
            val valid = isFin(x[idx])
            if (gap > maxAgeS && !valid) out[idx] = Double.NaN
            if (idx < vi[0] || idx > vi[vi.size - 1]) out[idx] = Double.NaN
        }
        return out
    }

    /** derive.centred_diff */
    fun centredDiff(x: DoubleArray, hz: Double, spanS: Double = 1.0): DoubleArray {
        val h = maxOf(1, pyRound(spanS * hz / 2).toInt())
        val d = DoubleArray(x.size) { Double.NaN }
        val den = 2.0 * h / hz
        for (i in h until x.size - h) d[i] = (x[i + h] - x[i - h]) / den
        return d
    }

    /** Python's round() for floats: round half to even. */
    fun pyRound(x: Double): Double = Math.rint(x)

    /** numpy float modulo (sign of divisor). */
    fun npMod(a: Double, b: Double): Double {
        var m = a % b   // fmod
        if (m != 0.0) {
            if ((b < 0) != (m < 0)) m += b
        } else {
            m = Math.copySign(0.0, b)
        }
        return m
    }

    /** np.rad2deg(np.unwrap(np.deg2rad(x))) */
    fun unwrapDeg(xDeg: DoubleArray): DoubleArray {
        val n = xDeg.size
        if (n == 0) return xDeg
        val p = DoubleArray(n) { Math.toRadians(xDeg[it]) }
        val pi = Math.PI
        val corr = DoubleArray(n - 1)
        for (i in 0 until n - 1) {
            val dd = p[i + 1] - p[i]
            var ddmod = npMod(dd + pi, 2 * pi) - pi
            if (ddmod == -pi && dd > 0) ddmod = pi
            var c = ddmod - dd
            if (abs(dd) < pi) c = 0.0
            corr[i] = c
        }
        val cs = cumsum(corr)
        val up = p.copyOf()
        for (i in 1 until n) up[i] = p[i] + cs[i - 1]
        return DoubleArray(n) { Math.toDegrees(up[it]) }
    }

    /** np.nanpercentile(x, q) with the default 'linear' method. */
    fun nanPercentile(x: DoubleArray, q: Double): Double {
        val v = x.filter { !it.isNaN() }.sorted()
        if (v.isEmpty()) return Double.NaN
        val rank = q / 100.0 * (v.size - 1)
        val lo = floor(rank).toInt()
        val hi = minOf(lo + 1, v.size - 1)
        val t = rank - lo
        val a = v[lo]
        val b = v[hi]
        val diff = b - a
        return if (t >= 0.5) b - diff * (1 - t) else a + diff * t
    }

    /** np.nanmedian on float32 data: the average of two middle values is computed in float32. */
    fun nanMedianF32(x: DoubleArray): Double {
        val v = x.filter { !it.isNaN() }.sorted()
        if (v.isEmpty()) return Double.NaN
        val m = v.size / 2
        return if (v.size % 2 == 1) v[m] else ((v[m - 1].toFloat() + v[m].toFloat()) / 2f).toDouble()
    }

    /** np.sign */
    fun sign(x: Double): Double = when { x > 0 -> 1.0; x < 0 -> -1.0; x == 0.0 -> 0.0; else -> Double.NaN }

    fun runs(mask: BooleanArray, minLen: Int = 1): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        val n = mask.size
        while (i < n) {
            if (mask[i]) {
                var e = i
                while (e < n && mask[e]) e++
                if (e - i >= minLen) out.add(intArrayOf(i, e))
                i = e
            } else i++
        }
        return out
    }

    fun nanMax(a: DoubleArray, from: Int, to: Int): Double {
        var m = Double.NaN
        for (i in maxOf(0, from) until minOf(a.size, to)) if (!a[i].isNaN() && (m.isNaN() || a[i] > m)) m = a[i]
        return m
    }

    fun nanMin(a: DoubleArray, from: Int, to: Int): Double {
        var m = Double.NaN
        for (i in maxOf(0, from) until minOf(a.size, to)) if (!a[i].isNaN() && (m.isNaN() || a[i] < m)) m = a[i]
        return m
    }

    fun nanMaxAbs(a: DoubleArray, from: Int, to: Int): Double {
        var m = Double.NaN
        for (i in maxOf(0, from) until minOf(a.size, to)) { val v = abs(a[i]); if (!v.isNaN() && (m.isNaN() || v > m)) m = v }
        return m
    }

    /** np.searchsorted(a, v, side='left') for an ascending array. */
    fun searchLeft(a: DoubleArray, v: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

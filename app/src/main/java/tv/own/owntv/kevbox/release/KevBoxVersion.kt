// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

/**
 * The KevBox Live version rule. A release is named A.B.C.K: A.B.C is the upstream OwnTV version it
 * is built on and K counts KevBox releases on that base. The version code is
 * A*1000000 + B*10000 + C*100 + K. tools/kevbox/version.sh applies the same rule for release.sh.
 */
object KevBoxVersion {
    data class Parts(val a: Int, val b: Int, val c: Int, val k: Int)

    private const val MAX_SEGMENT = 99

    /** Null unless [name] is exactly A.B.C.K with A, B, C in 0..99 and K in 1..99. */
    fun parse(name: String): Parts? {
        val segments = name.split('.')
        if (segments.size != 4) return null
        val numbers = segments.map { segment ->
            // One or two digits, no leading zero, so 05 never aliases 5.
            if (segment.isEmpty() || segment.length > 2 || !segment.all { it in '0'..'9' }) return null
            if (segment.length == 2 && segment[0] == '0') return null
            segment.toInt()
        }
        if (numbers.any { it > MAX_SEGMENT }) return null
        val (a, b, c, k) = numbers
        if (k < 1) return null
        return Parts(a, b, c, k)
    }

    /** A*1000000 + B*10000 + C*100 + K, or null when [parse] fails. */
    fun code(name: String): Int? {
        val p = parse(name) ?: return null
        return p.a * 1_000_000 + p.b * 10_000 + p.c * 100 + p.k
    }
}

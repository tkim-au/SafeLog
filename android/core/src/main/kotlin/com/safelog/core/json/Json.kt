package com.safelog.core.json

/**
 * Minimal JSON reader/writer so the core module needs no dependencies (it runs inside the Android app,
 * in unit tests and on any JVM). Values: Map<String, Any?> (insertion ordered), List<Any?>, String,
 * Double (all numbers), Boolean, null.
 */
object Json {
    fun parse(text: String): Any? = Parser(text).run { val v = value(); ws(); require(i == s.length) { "trailing data at $i" }; v }

    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Map<String, Any?> = parse(text) as Map<String, Any?>

    fun write(value: Any?, pretty: Boolean = false): String = StringBuilder().also { w(it, value, if (pretty) 0 else -1) }.toString()

    private fun w(sb: StringBuilder, v: Any?, indent: Int) {
        val nl = indent >= 0
        fun pad(k: Int) { if (nl) { sb.append('\n'); repeat(k) { sb.append("  ") } } }
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null")
                else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong().toString())
                else sb.append(d.toString())
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    pad(indent + 1)
                    str(sb, k.toString()); sb.append(if (nl) ": " else ":")
                    w(sb, x, if (nl) indent + 1 else -1)
                }
                if (!first) pad(indent)
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    pad(indent + 1)
                    w(sb, x, if (nl) indent + 1 else -1)
                }
                if (!first) pad(indent)
                sb.append(']')
            }
            is DoubleArray -> w(sb, v.toList(), indent)
            is IntArray -> w(sb, v.toList(), indent)
            is Array<*> -> w(sb, v.toList(), indent)
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("bad char '$c' at $i")
            }
        }
        fun lit(word: String, v: Any?): Any? { require(s.startsWith(word, i)) { "bad literal at $i" }; i += word.length; return v }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
        fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = string()
                ws(); require(s[i] == ':') { "expected : at $i" }; i++
                m[k] = value()
                ws()
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> error("expected , or } at ${i - 1}") }
            }
        }
        fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                ws()
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> error("expected , or ] at ${i - 1}") }
            }
        }
    }
}

/** Small typed accessors for parsed JSON maps. */
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(k: String): Map<String, Any?> = this[k] as? Map<String, Any?> ?: emptyMap()
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(k: String): List<Any?> = this[k] as? List<Any?> ?: emptyList()
fun Map<String, Any?>.num(k: String, d: Double = Double.NaN): Double = (this[k] as? Number)?.toDouble() ?: d
fun Map<String, Any?>.int(k: String, d: Int = 0): Int = (this[k] as? Number)?.toInt() ?: d
fun Map<String, Any?>.str(k: String, d: String = ""): String = this[k] as? String ?: d
fun Map<String, Any?>.strOrNull(k: String): String? = this[k] as? String
fun Map<String, Any?>.bool(k: String, d: Boolean = false): Boolean = this[k] as? Boolean ?: d

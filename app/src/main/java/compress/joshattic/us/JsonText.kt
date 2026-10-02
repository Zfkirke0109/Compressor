package compress.joshattic.us

/**
 * A minimal JSON emitter with no Android dependency, so the diagnostics manifest can be built and
 * unit-tested on the JVM (org.json is an unmocked stub there). Handles what the manifest needs:
 * null, booleans, numbers, strings, lists and string-keyed maps, with proper escaping.
 */
object JsonText {

    fun render(value: Any?, indent: Int = 2): String = StringBuilder().also { write(it, value, indent, 0) }.toString()

    private fun write(out: StringBuilder, value: Any?, indent: Int, depth: Int) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Number -> out.append(if (value is Double || value is Float) value.toString() else value.toString())
            is String -> quote(out, value)
            is Map<*, *> -> {
                if (value.isEmpty()) { out.append("{}"); return }
                out.append("{\n")
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(",\n")
                    first = false
                    pad(out, indent, depth + 1)
                    quote(out, k.toString())
                    out.append(": ")
                    write(out, v, indent, depth + 1)
                }
                out.append("\n"); pad(out, indent, depth); out.append("}")
            }
            is Iterable<*> -> {
                val items = value.toList()
                if (items.isEmpty()) { out.append("[]"); return }
                out.append("[\n")
                items.forEachIndexed { i, v ->
                    if (i > 0) out.append(",\n")
                    pad(out, indent, depth + 1)
                    write(out, v, indent, depth + 1)
                }
                out.append("\n"); pad(out, indent, depth); out.append("]")
            }
            else -> quote(out, value.toString())
        }
    }

    private fun pad(out: StringBuilder, indent: Int, depth: Int) { repeat(indent * depth) { out.append(' ') } }

    private fun quote(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}

package com.music.bitchord.data.feedback

/**
 * Fills the developer's replies in on this device.
 *
 * The developer writes "Hola {{nombre}}, gracias" and the server keeps it exactly like that; the
 * name comes from here, so the server never learns it. The rules are the server's
 * (backend/feedback/template.go) and the dashboard preview's, and all three are tested against
 * backend/feedback/testdata/template_vectors.json:
 *
 * - `{{name}}` or `{{name|fallback}}`; names are letters and underscores, any case, spaces
 *   allowed inside the braces. Anything that does not parse is left as written.
 * - A variable with no value and no fallback leaves no gap: "Hola {{nombre}}, gracias" becomes
 *   "Hola, gracias" and "¡Listo, {{nombre}}!" becomes "¡Listo!".
 * - The result is plain text, never markup.
 */
object FeedbackTemplate {
    /** The variables the app fills: see [FeedbackCenter.variablesFor]. */
    val VARIABLES = listOf("nombre", "version", "plataforma", "ticket", "titulo")

    private val TOKEN = Regex("""\{\{\s*([A-Za-z_]{1,24})\s*(?:\|([^{}\n]{0,60}))?\}\}""")

    fun render(body: String, vars: Map<String, String>): String {
        val out = StringBuilder()
        var rest = body
        var skipSpace = false
        fun dropLeadingSpace(s: String) = if (skipSpace && s.startsWith(" ")) s.substring(1) else s
        while (true) {
            val m = TOKEN.find(rest) ?: return out.append(dropLeadingSpace(rest)).toString()
            out.append(dropLeadingSpace(rest.substring(0, m.range.first)))
            skipSpace = false
            val name = m.groupValues[1].lowercase()
            var value = vars[name].orEmpty().trim()
            val fallback = m.groups[2]
            if (value.isEmpty() && fallback != null) value = fallback.value.trim()
            rest = rest.substring(m.range.last + 1)
            if (value.isNotEmpty()) {
                out.append(value)
                continue
            }
            // Empty: close the gap it leaves.
            if (rest.isEmpty() || rest[0] in ",.!?;:)") {
                when {
                    out.endsWith(", ") -> out.setLength(out.length - 2)
                    out.endsWith(" ") -> out.setLength(out.length - 1)
                }
            } else if (rest[0] == ' ' && (out.isEmpty() || out.endsWith(" ") || out.endsWith("\n"))) {
                skipSpace = true
            }
        }
    }

    /** What is wrong with [body]: "unknown:<name>" or "malformed". Empty when it is fine. */
    fun lint(body: String): List<String> {
        val problems = sortedSetOf<String>()
        for (m in TOKEN.findAll(body)) {
            val name = m.groupValues[1].lowercase()
            if (name !in VARIABLES) problems += "unknown:$name"
        }
        if (TOKEN.replace(body, "").contains("{{")) problems += "malformed"
        return problems.toList()
    }
}

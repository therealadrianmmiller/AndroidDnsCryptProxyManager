package au.com.dnscryptmonitor
private val patternRegex = Regex("\\t([^\\t]*\\*[^\\t]*?)(?:\\s*\\(alias for\\s+\\[[^\\]]*\\]\\))?(?:\\t|$)")
private val aliasRegex = Regex("\\s\\(alias for\\s+\\[([^\\]]+)\\]\\)")
// NEW: matches a random UUID prefix like c607ffc7-4f43-4692-9843-30728d279915-
private val randomPrefixRegex = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-",
    RegexOption.IGNORE_CASE
)
// NEW
private fun normalize(domain: String): String = domain.trim().replace(randomPrefixRegex, "")

data class BlockedDomain(val domain: String, val pattern: String?)
object LogParser {
    fun parse(line: String): List<BlockedDomain> {
        val pattern = patternRegex.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        val aliases = aliasRegex.findAll(line).map { it.groupValues[1].trim() }.toList()
        if (aliases.isNotEmpty()) return aliases.map { BlockedDomain(normalize(it), pattern) }   // CHANGED
        val parts: List<String> = line.trim().split("\t")
        if (parts.size >= 3) { val d = normalize(parts[2]); if ('.' in d && ' ' !in d) return listOf(BlockedDomain(d, pattern)) }   // CHANGED
        return emptyList()
    }
}

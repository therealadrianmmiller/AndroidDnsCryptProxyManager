package au.com.dnscryptmonitor
private val patternRegex = Regex("\\t([^\\t]*\\*[^\\t]*?)(?:\\s*\\(alias for\\s+\\[[^\\]]*\\]\\))?(?:\\t|$)")
private val aliasRegex = Regex("\\s\\(alias for\\s+\\[([^\\]]+)\\]\\)")
data class BlockedDomain(val domain: String, val pattern: String?)
object LogParser {
    fun parse(line: String): List<BlockedDomain> {
        val pattern = patternRegex.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        val aliases = aliasRegex.findAll(line).map { it.groupValues[1].trim() }.toList()
        if (aliases.isNotEmpty()) return aliases.map { BlockedDomain(it, pattern) }
        val parts = line.trim().split('\\t')
        if (parts.size >= 3) { val d = parts[2].trim(); if ('.' in d && ' ' !in d) return listOf(BlockedDomain(d, pattern)) }
        return emptyList()
    }
}

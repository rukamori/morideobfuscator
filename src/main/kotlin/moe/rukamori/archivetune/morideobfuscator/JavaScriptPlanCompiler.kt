/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.morideobfuscator

import java.security.MessageDigest

internal class JavaScriptPlanCompiler {
    fun compile(
        script: PlayerScript,
        nowMillis: Long,
    ): TransformPlan {
        val signatureFunction = findFunctionName(script.source, signatureCallPatterns)
        val nFunction = findFunctionName(script.source, nCallPatterns)
        val signatureProgram = signatureFunction?.let { buildProgram(script.source, it) }
        val nProgram = nFunction?.let { buildProgram(script.source, it) }

        if (signatureProgram == null && nProgram == null) {
            throw MoriCipherException("No supported YouTube player transforms were discovered")
        }

        return TransformPlan(
            playerId = script.playerId,
            playerUrl = script.url,
            sourceSha256 = script.source.sha256(),
            signatureTimestamp = findSignatureTimestamp(script.source),
            signatureProgram = signatureProgram,
            signatureFunction = signatureFunction,
            nProgram = nProgram,
            nFunction = nFunction,
            createdAtMillis = nowMillis,
        )
    }

    private fun findFunctionName(
        source: String,
        patterns: List<Regex>,
    ): String? =
        patterns.firstNotNullOfOrNull { regex ->
            regex
                .find(source)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf(IDENTIFIER_PATTERN::matches)
        }

    private fun findSignatureTimestamp(source: String): Int? =
        signatureTimestampPatterns.firstNotNullOfOrNull { regex ->
            regex
                .find(source)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }

    private fun buildProgram(
        source: String,
        rootName: String,
    ): String? {
        val declarations = LinkedHashMap<String, String>()
        val pending = ArrayDeque<String>()
        pending += rootName

        while (pending.isNotEmpty() && declarations.size < MAX_DECLARATIONS) {
            val name = pending.removeFirst()
            if (name in declarations || name in ignoredIdentifiers) continue
            val declaration = findDeclaration(source, name) ?: continue
            declarations[name] = declaration

            dependencyPattern
                .findAll(declaration)
                .map { it.groupValues[1] }
                .filter(IDENTIFIER_PATTERN::matches)
                .filterNot { it in declarations || it in ignoredIdentifiers }
                .forEach(pending::addLast)

            propertyOwnerPattern
                .findAll(declaration)
                .map { it.groupValues[1] }
                .filter(IDENTIFIER_PATTERN::matches)
                .filterNot { it in declarations || it in ignoredIdentifiers }
                .forEach(pending::addLast)
        }

        if (rootName !in declarations) return null
        val program = declarations.values.reversed().joinToString(separator = "\n")
        return program.takeIf { it.length <= MAX_PROGRAM_LENGTH }
    }

    private fun findDeclaration(
        source: String,
        name: String,
    ): String? {
        val escaped = Regex.escape(name)
        val candidates =
            listOf(
                Regex("""function\s+$escaped\s*\(""") to DeclarationKind.FUNCTION,
                Regex("""(?:^|[;,])\s*(?:var|let|const)?\s*$escaped\s*=\s*function\s*\(""") to DeclarationKind.ASSIGNMENT,
                Regex("""(?:^|[;,])\s*(?:var|let|const)?\s*$escaped\s*=\s*\{""") to DeclarationKind.OBJECT,
                Regex("""(?:^|[;,])\s*(?:var|let|const)?\s*$escaped\s*=\s*\[""") to DeclarationKind.ARRAY,
                Regex("""(?:^|[;,])\s*(?:var|let|const)?\s*$escaped\s*=\s*[A-Za-z_$][\w$]*\s*;""") to DeclarationKind.SIMPLE,
            )

        for ((pattern, kind) in candidates) {
            val match = pattern.find(source) ?: continue
            val start = match.range.first.coerceAtLeast(0)
            val end =
                when (kind) {
                    DeclarationKind.FUNCTION,
                    DeclarationKind.ASSIGNMENT,
                    -> findBalancedDeclarationEnd(source, match.range.last + 1, '{', '}')

                    DeclarationKind.OBJECT -> findBalancedDeclarationEnd(source, match.range.last, '{', '}')

                    DeclarationKind.ARRAY -> findBalancedDeclarationEnd(source, match.range.last, '[', ']')

                    DeclarationKind.SIMPLE -> source.indexOf(';', match.range.last).takeIf { it >= 0 }
                } ?: continue
            return source.substring(start, (end + 1).coerceAtMost(source.length)).trimStart(',', ';')
        }
        return null
    }

    private fun findBalancedDeclarationEnd(
        source: String,
        searchStart: Int,
        open: Char,
        close: Char,
    ): Int? {
        val opening = source.indexOf(open, searchStart)
        if (opening < 0) return null
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var lineComment = false
        var blockComment = false
        var index = opening

        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when {
                lineComment -> {
                    if (current == '\n') lineComment = false
                }

                blockComment -> {
                    if (current == '*' && next == '/') {
                        blockComment = false
                        index++
                    }
                }

                quote != null -> {
                    when {
                        escaped -> escaped = false
                        current == '\\' -> escaped = true
                        current == quote -> quote = null
                    }
                }

                current == '/' && next == '/' -> {
                    lineComment = true
                    index++
                }

                current == '/' && next == '*' -> {
                    blockComment = true
                    index++
                }

                current == '\'' || current == '"' || current == '`' -> {
                    quote = current
                }

                current == open -> {
                    depth++
                }

                current == close -> {
                    depth--
                    if (depth == 0) {
                        val semicolon = source.indexOf(';', index).takeIf { it in index..(index + 2) }
                        return semicolon ?: index
                    }
                }
            }
            index++
        }
        return null
    }

    private enum class DeclarationKind {
        FUNCTION,
        ASSIGNMENT,
        OBJECT,
        ARRAY,
        SIMPLE,
    }

    private companion object {
        const val MAX_DECLARATIONS = 32
        const val MAX_PROGRAM_LENGTH = 500_000
        val IDENTIFIER_PATTERN = Regex("""^[A-Za-z_$][\w$]*$""")
        val dependencyPattern = Regex("""\b([A-Za-z_$][\w$]*)\s*\(""")
        val propertyOwnerPattern = Regex("""\b([A-Za-z_$][\w$]*)\s*(?:\.|\[)""")
        val signatureCallPatterns =
            listOf(
                Regex("""(?:signature|sig)\s*[,=:]\s*([A-Za-z_$][\w$]*)\("""),
                Regex("""\.set\(\s*["'](?:signature|sig)["']\s*,\s*([A-Za-z_$][\w$]*)\("""),
                Regex("""["']signature["']\s*,\s*([A-Za-z_$][\w$]*)\("""),
                Regex("""\bc\s*&&\s*\(\s*c\s*=\s*([A-Za-z_$][\w$]*)\(decodeURIComponent"""),
            )
        val nCallPatterns =
            listOf(
                Regex("""\.get\(\s*["']n["']\s*\)\s*\)\s*&&\s*\([^=]+=\s*([A-Za-z_$][\w$]*)\("""),
                Regex("""\.set\(\s*["']n["']\s*,\s*([A-Za-z_$][\w$]*)\("""),
                Regex("""\bn\s*&&\s*\(\s*n\s*=\s*([A-Za-z_$][\w$]*)\(n\)"""),
            )
        val signatureTimestampPatterns =
            listOf(
                Regex("""signatureTimestamp["']?\s*[:=]\s*(\d{4,8})"""),
                Regex("""["']STS["']\s*:\s*(\d{4,8})"""),
                Regex("""\bsts\s*[:=]\s*(\d{4,8})"""),
            )
        val ignoredIdentifiers =
            setOf(
                "Array",
                "Boolean",
                "Date",
                "Error",
                "JSON",
                "Math",
                "Number",
                "Object",
                "RegExp",
                "String",
                "decodeURIComponent",
                "encodeURIComponent",
                "parseInt",
                "isNaN",
                "return",
                "if",
                "for",
                "while",
                "switch",
                "catch",
                "function",
            )
    }
}

internal fun String.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

package com.iptvapp.util

object M3uParser {

    data class M3uChannel(
        val name: String,
        val groupTitle: String,
        val logoUrl: String?,
        val tvgId: String?,
        val streamUrl: String
    )

    fun parse(content: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        val lines = content.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:")) {
                val name = line.substringAfterLast(",").trim().ifBlank { "Unknown" }
                val groupTitle = extractAttr(line, "group-title") ?: "General"
                val logoUrl = extractAttr(line, "tvg-logo")
                val tvgId = extractAttr(line, "tvg-id")

                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].trimStart().startsWith("#"))) j++
                if (j < lines.size) {
                    val url = lines[j].trim()
                    if (url.startsWith("http") || url.startsWith("rtsp") || url.startsWith("rtmp")) {
                        channels.add(M3uChannel(name, groupTitle, logoUrl, tvgId, url))
                    }
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return channels
    }

    private fun extractAttr(line: String, attr: String): String? =
        Regex("""$attr="([^"]*)"""").find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
}

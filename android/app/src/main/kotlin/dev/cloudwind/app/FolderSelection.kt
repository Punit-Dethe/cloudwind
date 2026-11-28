package dev.cloudwind.app

/**
 * Pure folder selection and SAF path normalization rules (JVM unit-tested, Android-free).
 */
object FolderSelection {
    /** Normalize a MediaStore RELATIVE_PATH (e.g. "DCIM/Camera/" -> "DCIM/Camera"). */
    fun norm(relPath: String): String = relPath.trim().replace('\\', '/').trim('/')

    /** Friendly display name for a folder path (e.g. "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images" -> "WhatsApp Images"). */
    fun displayName(relPath: String, bucketName: String? = null): String {
        val b = bucketName?.trim().orEmpty()
        if (b.isNotEmpty() && b != "0") return b
        val n = norm(relPath)
        if (n.isEmpty()) return "Internal Storage"
        return n.substringAfterLast('/')
    }

    /** Short parent subtitle (e.g. "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images" -> "WhatsApp / Media"). */
    fun subtitlePath(relPath: String): String {
        val n = norm(relPath)
        if (n.isEmpty()) return ""
        return when {
            n.startsWith("Android/media/com.whatsapp/", ignoreCase = true) ->
                n.removePrefix("Android/media/com.whatsapp/").removePrefix("Android/media/com.whatsapp/")
                    .substringBeforeLast('/', "").replace('/', ' ').trim().ifEmpty { "WhatsApp" }
            '/' in n -> n.substringBeforeLast('/').replace("/", " / ")
            else -> ""
        }
    }

    /**
     * Whether a folder should be backed up under the current settings.
     *
     * - Once the user explicitly manages folders ([explicit] == true), [selected] is authoritative.
     * - Before that (fresh install or upgraded user), falls back to the legacy behavior:
     *   all folders if [legacyAllFolders] is true, otherwise camera folders (`DCIM` / `DCIM/...`).
     */
    fun isSelected(
        relPath: String,
        explicit: Boolean,
        selected: Set<String>,
        legacyAllFolders: Boolean,
    ): Boolean {
        val n = norm(relPath)
        if (n.isEmpty()) return false
        if (explicit) return n in selected
        if (legacyAllFolders) return true
        return n == "DCIM" || n.startsWith("DCIM/")
    }

    /**
     * Extract a normalized relative storage path from a SAF tree document ID
     * (e.g. "primary:Pictures/Canva" -> "Pictures/Canva", "primary:" -> null).
     */
    fun relativePathFromTreeDocId(docId: String?): String? {
        if (docId.isNullOrBlank()) return null
        val afterColon = if (':' in docId) docId.substringAfter(':') else docId
        val n = norm(afterColon)
        return n.ifEmpty { null }
    }

    /**
     * Stable negative ID for SAF-only documents (e.g. inside `.nomedia` folders) so they never
     * collide with positive Android MediaStore `_ID` values in `photos.db`.
     */
    fun safMediaId(docUri: String): Long {
        // FNV-1a 64-bit hash, forced strictly negative (< 0).
        var h = -3750763034362895579L // 0xcbf29ce484222325
        for (b in docUri.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xffL)
            h *= 1099511628211L
        }
        val positive = h and Long.MAX_VALUE
        return if (positive == 0L) -1L else -positive
    }

    /** Clean 1-level PC destination subfolder name for a phone folder (works cleanly across LibrePhotos, Immich, PhotoPrism). */
    fun defaultPcFolder(relPath: String, bucketName: String? = null): String {
        val n = norm(relPath)
        return when {
            n.equals("DCIM", ignoreCase = true) || n.equals("DCIM/Camera", ignoreCase = true) -> "Camera"
            n.equals("Download", ignoreCase = true) -> "Downloads"
            else -> sanitizePcFolder(displayName(n, bucketName)).ifEmpty { "Photos" }
        }
    }

    /** Sanitizes a user-typed PC destination folder name into a single safe 1-level folder name. */
    fun sanitizePcFolder(raw: String): String {
        val cleaned = raw
            .replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimStart('.')
            .trimEnd('.', ' ')
            .trim()
        return cleaned.take(60).trimEnd('.', ' ')
    }

    /**
     * Quick-pick PC folder suggestions for a folder sheet:
     * - Smart group name when obvious (e.g. "WhatsApp Images" / "WhatsApp Video" -> "WhatsApp")
     * - The folder's own default name
     * - Existing destination folder names already in use by other selected folders (so grouping takes 1 tap)
     */
    fun suggestedPcFolders(
        relPath: String,
        displayName: String,
        existingTargets: Collection<String>,
    ): List<String> {
        val out = LinkedHashSet<String>()
        val def = defaultPcFolder(relPath, displayName)
        if (def.startsWith("WhatsApp ", ignoreCase = true)) out += "WhatsApp"
        if (def.startsWith("Telegram ", ignoreCase = true)) out += "Telegram"
        out += def
        existingTargets.map { sanitizePcFolder(it) }.filter { it.isNotEmpty() }.forEach { out += it }
        return out.toList()
    }

    /** True if a SAF file MIME type or extension is a photo or video worth backing up. */
    fun isSupportedMedia(mime: String?, name: String): Boolean {
        if (name.startsWith(".")) return false
        val m = mime?.lowercase().orEmpty()
        if (m.startsWith("image/") || m.startsWith("video/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in MEDIA_EXTS
    }

    private val MEDIA_EXTS = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "dng", "raw", "avif",
        "mp4", "mov", "mkv", "webm", "3gp", "m4v",
    )
}

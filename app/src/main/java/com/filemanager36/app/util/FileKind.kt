package com.filemanager36.app.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, PDF, DOC, SHEET, ARCHIVE, APK, CODE, OTHER }

object FileKinds {

    private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "ico")
    private val videos = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "wmv", "m4v", "mpg", "mpeg", "ts")
    private val audios = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus", "wma", "amr", "mid")
    private val docs = setOf("doc", "docx", "odt", "rtf", "txt", "md", "epub")
    private val sheets = setOf("xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp")
    private val archives = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "jar")
    private val code = setOf("java", "kt", "js", "ts", "py", "c", "cpp", "h", "html", "css", "json", "xml", "sh", "yml", "yaml")

    fun of(name: String, isDirectory: Boolean): FileKind {
        if (isDirectory) return FileKind.FOLDER
        return when (name.substringAfterLast('.', "").lowercase()) {
            in images -> FileKind.IMAGE
            in videos -> FileKind.VIDEO
            in audios -> FileKind.AUDIO
            "pdf" -> FileKind.PDF
            in docs -> FileKind.DOC
            in sheets -> FileKind.SHEET
            in archives -> FileKind.ARCHIVE
            "apk" -> FileKind.APK
            in code -> FileKind.CODE
            else -> FileKind.OTHER
        }
    }

    fun icon(kind: FileKind): ImageVector = when (kind) {
        FileKind.FOLDER -> Icons.Filled.Folder
        FileKind.IMAGE -> Icons.Filled.Image
        FileKind.VIDEO -> Icons.Filled.VideoFile
        FileKind.AUDIO -> Icons.Filled.AudioFile
        FileKind.PDF -> Icons.Filled.PictureAsPdf
        FileKind.DOC -> Icons.Filled.Description
        FileKind.SHEET -> Icons.Filled.TableChart
        FileKind.ARCHIVE -> Icons.Filled.Archive
        FileKind.APK -> Icons.Filled.Android
        FileKind.CODE -> Icons.Filled.Code
        FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

    fun color(kind: FileKind): Color = when (kind) {
        FileKind.FOLDER -> Color(0xFF4C8DF6)
        FileKind.IMAGE -> Color(0xFF2FA36B)
        FileKind.VIDEO -> Color(0xFFE0603C)
        FileKind.AUDIO -> Color(0xFF9B59D0)
        FileKind.PDF -> Color(0xFFD64545)
        FileKind.DOC -> Color(0xFF3A6FD8)
        FileKind.SHEET -> Color(0xFF2E9E63)
        FileKind.ARCHIVE -> Color(0xFFC98A20)
        FileKind.APK -> Color(0xFF63B75A)
        FileKind.CODE -> Color(0xFF6C7A89)
        FileKind.OTHER -> Color(0xFF8A94A0)
    }

    /**
     * Fichiers que la visionneuse intégrée sait afficher comme du texte.
     *
     * Beaucoup de téléphones n'ont aucune application capable d'ouvrir un .txt, un
     * .json ou un .log : le sélecteur système restait vide et rien ne se passait.
     */
    private val texts = setOf(
        "txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yml", "yaml",
        "ini", "conf", "cfg", "properties", "env", "toml", "gradle", "pro",
        "kt", "kts", "java", "js", "mjs", "ts", "tsx", "jsx", "py", "rb", "php",
        "c", "cpp", "cc", "h", "hpp", "cs", "go", "rs", "swift", "m", "lua", "pl",
        "sh", "bash", "bat", "cmd", "ps1", "sql", "html", "htm", "css", "scss",
        "srt", "vtt", "sub", "nfo", "readme", "gitignore", "info", "list", "diff", "patch"
    )

    fun isText(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return false
        return ext in texts
    }

    /**
     * Formats textuels qu'une application dédiée rend bien mieux que du texte brut :
     * une page HTML doit s'ouvrir dans le navigateur, un tableau CSV dans un tableur.
     * La visionneuse intégrée ne les reprend que si rien sur le téléphone ne les gère.
     */
    private val richText = setOf("html", "htm", "csv", "tsv", "md", "markdown")

    fun prefersExternalApp(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in richText

    /** Archives que l'application sait ouvrir elle-même (extraction intégrée). */
    fun isZip(name: String): Boolean = name.substringAfterLast('.', "").equals("zip", true)

    /** Types pour lesquels on peut produire une miniature. */
    fun previewable(kind: FileKind): Boolean =
        kind == FileKind.IMAGE || kind == FileKind.VIDEO || kind == FileKind.PDF ||
            kind == FileKind.AUDIO || kind == FileKind.APK
}

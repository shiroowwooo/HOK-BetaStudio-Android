package com.hokbetastudio.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class PackageScanner(private val context: Context) {
    private val resolver = context.contentResolver
    private val records = mutableListOf<JSONObject>()
    private val errors = mutableListOf<String>()
    private var visited = 0

    fun scan(inputs: List<Pair<Uri, Boolean>>): JSONObject {
        records.clear()
        errors.clear()
        visited = 0

        for ((uri, isTree) in inputs) {
            try {
                if (isTree) {
                    scanTree(uri)
                } else {
                    scanFile(uri)
                }
            } catch (e: Exception) {
                errors.add("${uri}: ${e.message ?: "Unable to read document"}")
            }
        }

        records.sortWith(
            compareBy<JSONObject>(
                { it.optString("heroId") },
                { it.optString("skinId") },
                { it.optString("name") }
            )
        )

        return JSONObject().apply {
            put("files", JSONArray(records))
            put("errors", JSONArray(errors))
            put("visited", visited)
        }
    }

    private fun scanTree(treeUri: Uri) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, rootId
        )
        walk(treeUri, rootDocument, treeUri.toString(), rootId, 0)
    }

    private fun walk(
        treeUri: Uri,
        directoryUri: Uri,
        root: String,
        parentId: String,
        depth: Int
    ) {
        if (depth > 64) {
            errors.add("$directoryUri: Maximum folder depth reached")
            return
        }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(directoryUri)
        )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )
            val nameCol = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            val mimeCol = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            val sizeCol = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_SIZE
            )
            val modifiedCol = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )

            while (cursor.moveToNext()) {
                try {
                    val id = cursor.getString(idCol)
                    val name = cursor.getString(nameCol) ?: "unnamed"
                    val mime = cursor.getString(mimeCol) ?: ""
                    val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol))
                        cursor.getLong(sizeCol) else -1L
                    val modified = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol))
                        cursor.getLong(modifiedCol) else 0L

                    val childUri =
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(treeUri, childUri, root, id, depth + 1)
                    } else {
                        visited++
                        addFile(childUri, name, root, id.substringBeforeLast("/", parentId), size, modified)
                    }
                } catch (e: Exception) {
                    errors.add("$directoryUri: ${e.message ?: "Unable to inspect entry"}")
                }
            }
        } ?: errors.add("$directoryUri: Cannot list folder contents")
    }

    private fun scanFile(uri: Uri) {
        var name = "unnamed"
        var size = -1L
        var modified = 0L

        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)

                if (nameCol >= 0) name = cursor.getString(nameCol) ?: name
                if (sizeCol >= 0 && !cursor.isNull(sizeCol)) {
                    size = cursor.getLong(sizeCol)
                }
            }
        }

        visited++
        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            uri.lastPathSegment ?: name
        }

        addFile(
            uri, name, uri.toString(),
            documentId.substringBeforeLast("/", documentId),
            size, modified
        )
    }

    private fun addFile(
        uri: Uri,
        name: String,
        root: String,
        parentId: String,
        reportedSize: Long,
        modified: Long
    ) {
        val signature = detect(uri, reportedSize)
        val identity = identify(name, signature, parentId) ?: return

        val actualSize = if (reportedSize >= 0) reportedSize else -1L
        val id = sha256(uri.toString().uppercase()).take(24)

        records.add(JSONObject().apply {
            put("id", id)
            put("path", uri.toString())
            put("name", name)
            put("root", root)
            put("heroId", identity.heroId)
            put("skinId", identity.skinId)
            put("stem", identity.stem)
            put("shard", identity.shard ?: JSONObject.NULL)
            put("bytes", actualSize)
            put("fingerprint", "$actualSize:$modified")
            put("signature", signature)
        })
    }

    private data class Identity(
        val heroId: String,
        val skinId: String,
        val stem: String,
        val shard: String?
    )

    private fun identify(
        filename: String,
        signature: String,
        parentId: String
    ): Identity? {
        val numeric = Regex(
            "^([0-9]{5,})(?:_([0-9]+))?(\\.db)?$",
            RegexOption.IGNORE_CASE
        ).matchEntire(filename)

        if (numeric != null &&
            (numeric.groupValues[3].isNotEmpty() || signature != "unknown")
        ) {
            val stem = numeric.groupValues[1]
            val code = stem.takeLast(5)
            return Identity(
                code.take(3),
                code,
                stem,
                numeric.groupValues[2].ifEmpty { null }
            )
        }

        val isDb = filename.endsWith(".db", ignoreCase = true)
        if (!isDb && signature == "unknown") return null

        val withoutExtension =
            if (isDb) filename.dropLast(3) else filename

        val packageMatch = Regex("^(.+?)(?:_([0-9]+))?$")
            .matchEntire(withoutExtension) ?: return null

        val stem = packageMatch.groupValues[1]
        val shard = packageMatch.groupValues[2].ifEmpty { null }
        val unknownId = "unknown:" +
            sha256("$parentId|$stem").take(24)

        return Identity("", unknownId, stem, shard)
    }

    private fun detect(uri: Uri, reportedSize: Long): String {
        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(48)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read <= 0) break
                    count += read
                }
                buffer.copyOf(count)
            } ?: return "unknown"
        } catch (_: Exception) {
            return "unknown"
        }

        if (bytes.size >= 8 &&
            bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(1, 0, 0, 2, 1, 2, 3, 4)
            )
        ) return "QTS VFS"

        if (bytes.size >= 7 &&
            String(bytes, 0, 7, Charsets.US_ASCII) == "UnityFS"
        ) return "UnityFS"

        if (bytes.size >= 20) {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val version = buffer.getInt(8)

            if (version in 9..22 && bytes[16].toInt() in 0..1 &&
                (version < 22 || bytes.size >= 48)
            ) {
                val fileSize: Long
                val dataOffset: Long

                if (version == 22) {
                    fileSize = buffer.getLong(24)
                    dataOffset = buffer.getLong(32)
                } else {
                    fileSize = Integer.toUnsignedLong(buffer.getInt(4))
                    dataOffset = Integer.toUnsignedLong(buffer.getInt(12))
                }

                if (fileSize > 0 &&
                    (reportedSize < 0 || fileSize == reportedSize) &&
                    dataOffset >= (if (version == 22) 48 else 20) &&
                    dataOffset <= fileSize
                ) {
                    return "SerializedFile"
                }
            }
        }

        return "unknown"
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02X".format(it) }
    }
}

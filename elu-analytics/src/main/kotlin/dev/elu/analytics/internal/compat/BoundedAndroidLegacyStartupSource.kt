package dev.elu.analytics.internal.compat

import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import org.xmlpull.v1.XmlPullParser

/**
 * One unchanged app/site cohort. No directory-wide token discovery, source writes,
 * host adoption or network access is permitted here. The exact Events directory
 * supplies a complete bounded manifest; other pending queue formats remain refused.
 */
internal class BoundedAndroidLegacyStartupSource(
    private val dataRoot: File,
    private val filesRoot: File,
    private val cacheRoot: File,
    private val siteKey: String,
    private val parser: () -> XmlPullParser,
    private val assertCurrent: () -> Unit,
    private val fileAccess: AndroidLegacyFileAccess = AndroidOsLegacyFileAccess,
) : AndroidLegacyStartupSource {
    override fun observe(): AndroidLegacyStartupSnapshot? {
        try {
            assertCurrent()
            val first = observeOnce()
            assertCurrent()
            val second = observeOnce()
            assertCurrent()
            if (first?.fingerprint != second?.fingerprint || first?.publicToken != second?.publicToken) {
                legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_CHANGED)
            }
            return second
        } catch (error: AndroidLegacyStartupException) { throw error }
        catch (error: InterruptedException) { throw error }
        catch (_: java.io.IOException) { legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_UNAVAILABLE) }
        catch (_: SecurityException) { legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_UNAVAILABLE) }
    }

    private fun observeOnce(): AndroidLegacyStartupSnapshot? {
        requireRoot(dataRoot)
        requireRoot(filesRoot)
        requireRoot(cacheRoot)
        if (filesRoot != File(dataRoot, "files") || cacheRoot != File(dataRoot, "cache")) unavailable()
        val ownPreferences = File(dataRoot, "shared_prefs/dev.elu.analytics.xml")
        val own = read(ownPreferences, MAX_PREFERENCES_BYTES)
        if (read(File(ownPreferences.parentFile, "dev.elu.analytics.xml.bak"), MAX_PREFERENCES_BYTES) != null) unavailable()
        // The old fsSafe transform is injective only for this unchanged ASCII cohort.
        if (!Regex("[A-Za-z0-9_.-]{1,512}").matches(siteKey)) {
            if (own != null) legacyStartupRefuse(AndroidLegacyStartupRefusal.MAPPING_UNAVAILABLE)
            return null
        }
        val configPath = File(filesRoot, "elu/config-$siteKey.json")
        if (read(File(configPath.parentFile, configPath.name + ".tmp"), MAX_CONFIG_BYTES) != null) unavailable()
        val configBytes = read(configPath, MAX_CONFIG_BYTES)
        if (configBytes == null) {
            if (own != null) legacyStartupRefuse(AndroidLegacyStartupRefusal.MAPPING_UNAVAILABLE)
            return null
        }
        val config = try { V1StrictCanonicalJson.parse(utf8(configBytes)) as? V1StrictCanonicalJson.Value.ObjectValue }
            catch (_: IllegalArgumentException) { null }
            ?: legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)
        val enabled = (config.member("enabled") as? V1StrictCanonicalJson.Value.BooleanValue)?.value
            ?: legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)
        val token = (config.member("publicToken") as? V1StrictCanonicalJson.Value.StringValue)?.value?.trim()
            ?.takeIf { Regex("[A-Za-z0-9_-]{1,512}").matches(it) }
            ?: legacyStartupRefuse(AndroidLegacyStartupRefusal.MAPPING_UNAVAILABLE)
        // Cached host is deliberately unused. It supplies no destination transport authority.
        val providerPath = File(dataRoot, "shared_prefs/posthog-android-$token.xml")
        if (read(File(providerPath.parentFile, providerPath.name + ".bak"), MAX_PREFERENCES_BYTES) != null) unavailable()
        val provider = read(providerPath, MAX_PREFERENCES_BYTES) ?: unavailable()
        val values = LegacyAndroidPreferencesXml.decode(utf8(provider), parser())
        val hash = MessageDigest.getInstance("SHA-256")
        fun frame(label: String, bytes: ByteArray?) {
            val name = label.toByteArray(StandardCharsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(name.size).array()); hash.update(name)
            hash.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
            if (bytes != null) hash.update(bytes)
        }
        frame("siteKey", siteKey.toByteArray(StandardCharsets.UTF_8))
        frame("config", configBytes); frame("eluPreferences", own); frame("providerPreferences", provider)
        val history = historyDirectory(File(cacheRoot, "posthog-disk-queue/$token"), ::frame)
        for ((name, path) in listOf(
            "replay" to File(cacheRoot, "posthog-disk-replay-queue/$token"),
            "replayBuffer" to File(cacheRoot, "posthog-disk-replay-queue-buffer/$token"),
            "logs" to File(cacheRoot, "posthog-disk-logs-queue/$token"),
            // Context.getDir prefixes app_ to the exact legacy argument app_posthog-disk-queue.
            // This older whole directory must be empty; no unknown old layout is inferred.
            "olderEvents" to File(dataRoot, "app_app_posthog-disk-queue"),
        )) frame(name, if (emptyDirectory(path)) byteArrayOf(1) else null)
        val fingerprint = hash.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return AndroidLegacyStartupSnapshot(token, fingerprint, values, configurationDisabled = !enabled, pendingHistory = history)
    }

    private fun utf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE) }

    private fun requireRoot(path: File) {
        if (!path.isAbsolute || path.canonicalFile != path) unavailable()
        val info = fileAccess.lstat(path) ?: unavailable()
        if (info.kind != AndroidLegacyFileKind.DIRECTORY) unavailable()
    }

    // Retain each parent's inode as well as validating its kind. Source paths below
    // the Context root may not contain aliases, dot segments or symbolic links.
    private fun parents(path: File): List<Pair<File, AndroidLegacyFileStat>>? {
        if (!path.isAbsolute) unavailable()
        val relative = if (path == dataRoot) "" else {
            val prefix = dataRoot.path + File.separator
            if (!path.path.startsWith(prefix)) unavailable()
            path.path.substring(prefix.length)
        }
        val parts = if (relative.isEmpty()) emptyList() else relative.split(File.separatorChar)
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) unavailable()
        val result = ArrayList<Pair<File, AndroidLegacyFileStat>>()
        var current = dataRoot
        for (part in listOf("") + parts) {
            if (part.isNotEmpty()) current = File(current, part)
            val info = fileAccess.lstat(current) ?: return null
            if (info.kind != AndroidLegacyFileKind.DIRECTORY) unavailable()
            result.add(current to info)
        }
        return result
    }

    private fun sameParents(before: List<Pair<File, AndroidLegacyFileStat>>): Boolean =
        before.all { (path, info) -> fileAccess.lstat(path) == info }

    private fun read(path: File, maximum: Int): ByteArray? {
        assertCurrent()
        val parentSnapshot = parents(path.parentFile ?: unavailable()) ?: return null
        val before = fileAccess.lstat(path) ?: return null
        if (before.kind != AndroidLegacyFileKind.REGULAR) unavailable()
        if (before.size > maximum || before.size < 0) legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_TOO_LARGE)
        val output = ByteArrayOutputStream()
        fileAccess.open(path, directory = false).use { opened ->
            if (opened.stat() != before || !sameParents(parentSnapshot)) changed()
            val buffer = ByteArray(8192)
            while (true) {
                assertCurrent()
                val size = opened.read(buffer)
                if (size == 0) break // POSIX read: zero is EOF.
                if (size < 0 || size > buffer.size || output.size() + size > maximum) {
                    legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_TOO_LARGE)
                }
                output.write(buffer, 0, size)
            }
            if (opened.stat() != before || !sameParents(parentSnapshot) || fileAccess.lstat(path) != before) changed()
        }
        if (!sameParents(parentSnapshot) || fileAccess.lstat(path) != before || output.size().toLong() != before.size) changed()
        return output.toByteArray()
    }

    private fun historyDirectory(path: File, frame: (String, ByteArray?) -> Unit): List<AndroidLegacyHistoryEntry> {
        assertCurrent()
        val parentSnapshot = parents(path.parentFile ?: unavailable())
        val before = if (parentSnapshot == null) null else fileAccess.lstat(path)
        if (before == null) { frame("events", null); return emptyList() }
        if (before.kind != AndroidLegacyFileKind.DIRECTORY) unavailable()
        val records = ArrayList<AndroidLegacyHistoryEntry>()
        fileAccess.open(path, directory = true).use { opened ->
            if (opened.stat() != before || !sameParents(parentSnapshot!!)) changed()
            // API23 File.list materializes the complete name array. This exact-directory
            // enumeration is not streaming, recursive, or token discovery. Never take a prefix.
            val names = fileAccess.listDirectory(path)
            if (names.size > LegacyAndroidPendingHistory.MAXIMUM_RECORDS) {
                legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_TOO_LARGE)
            }
            if (names.size != names.toSet().size) changed()
            if (names.any { !Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.event").matches(it) }) {
                legacyStartupRefuse(AndroidLegacyStartupRefusal.QUEUE_NOT_EMPTY)
            }
            val entries = names.map { name ->
                val file = File(path, name)
                val info = fileAccess.lstat(file) ?: changed()
                if (info.kind != AndroidLegacyFileKind.REGULAR) unavailable()
                if (info.size !in 1..LegacyAndroidPendingHistory.MAXIMUM_EVENT_BYTES.toLong()) {
                    legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_TOO_LARGE)
                }
                val modified = fileAccess.lastModified(file)
                if (modified <= 0) changed()
                Triple(file, info, modified)
            }.sortedBy { it.third } // Exactly the genuine old File.lastModified ordering.
            if (entries.zipWithNext().any { (first, second) -> first.third >= second.third }) {
                throw AndroidLegacyHistoryException(AndroidLegacyHistoryRefusal.AMBIGUOUS_ORDER)
            }
            frame("events", byteArrayOf(1))
            var total = 0L
            for ((file, info, modified) in entries) {
                assertCurrent()
                total = Math.addExact(total, info.size)
                if (total > LegacyAndroidPendingHistory.MAXIMUM_SOURCE_BYTES) {
                    legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_TOO_LARGE)
                }
                fun load(): ByteArray {
                    assertCurrent()
                    if (fileAccess.lstat(file) != info || fileAccess.lastModified(file) != modified) changed()
                    val bytes = read(file, LegacyAndroidPendingHistory.MAXIMUM_EVENT_BYTES) ?: changed()
                    if (fileAccess.lstat(file) != info || fileAccess.lastModified(file) != modified) changed()
                    return bytes
                }
                val bytes = load()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                frame("events/" + file.name + "/modified", ByteBuffer.allocate(8).putLong(modified).array())
                frame("events/" + file.name, bytes)
                records.add(AndroidLegacyHistoryEntry.observed(file.name, modified) {
                    val current = load()
                    if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(current), digest)) changed()
                    current
                })
            }
            assertCurrent()
            if (fileAccess.listDirectory(path).toSet() != names.toSet() || opened.stat() != before ||
                !sameParents(parentSnapshot!!) || fileAccess.lstat(path) != before) changed()
        }
        if (!sameParents(parentSnapshot!!) || fileAccess.lstat(path) != before) changed()
        return java.util.Collections.unmodifiableList(records)
    }

    private fun emptyDirectory(path: File): Boolean {
        assertCurrent()
        val parentSnapshot = parents(path.parentFile ?: unavailable()) ?: return false
        val before = fileAccess.lstat(path) ?: return false
        if (before.kind != AndroidLegacyFileKind.DIRECTORY) unavailable()
        fileAccess.open(path, directory = true).use { opened ->
            if (opened.stat() != before || !sameParents(parentSnapshot)) changed()
            // API23 File.list materializes names. This is deliberately limited to the
            // four remaining known private queue directories: no recursion or token discovery.
            // It is NOT a bounded streaming enumeration; any entry refuses migration.
            if (fileAccess.hasDirectoryEntry(path)) legacyStartupRefuse(AndroidLegacyStartupRefusal.QUEUE_NOT_EMPTY)
            assertCurrent()
            if (opened.stat() != before || !sameParents(parentSnapshot) || fileAccess.lstat(path) != before) changed()
        }
        if (!sameParents(parentSnapshot) || fileAccess.lstat(path) != before) changed()
        return true
    }

    private fun changed(): Nothing = legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_CHANGED)
    private fun unavailable(): Nothing = legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_UNAVAILABLE)

    companion object {
        private const val MAX_CONFIG_BYTES = 65_536
        private const val MAX_PREFERENCES_BYTES = 262_144

        /** API1 Context directories retain whichever storage context the caller selected. */
        fun contextDirectories(files: File, cache: File): AndroidLegacyDirectories {
            if (!files.isAbsolute || !cache.isAbsolute || files.name != "files" || cache.name != "cache") {
                legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_UNAVAILABLE)
            }
            // Canonicalize only the app-root alias (e.g. /data/data), not child aliases.
            val root = files.parentFile?.canonicalFile
                ?: legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_UNAVAILABLE)
            if (cache.parentFile?.canonicalFile != root) legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_UNAVAILABLE)
            return AndroidLegacyDirectories(root, File(root, "files"), File(root, "cache"))
        }
    }
}

internal data class AndroidLegacyDirectories(val data: File, val files: File, val cache: File)
internal enum class AndroidLegacyFileKind { REGULAR, DIRECTORY, OTHER }
internal data class AndroidLegacyFileStat(
    val device: Long, val inode: Long, val kind: AndroidLegacyFileKind,
    val mode: Int, val links: Long, val uid: Int, val gid: Int, val size: Long,
    val modifiedSeconds: Long, val changedSeconds: Long,
)

/** The host tests replace only OS calls; the source parser and admission checks stay real. */
internal interface AndroidLegacyFileAccess {
    fun lstat(path: File): AndroidLegacyFileStat?
    fun open(path: File, directory: Boolean): AndroidLegacyOpenedFile
    fun hasDirectoryEntry(path: File): Boolean
    fun listDirectory(path: File): List<String> =
        if (hasDirectoryEntry(path)) legacyStartupRefuse(AndroidLegacyStartupRefusal.QUEUE_NOT_EMPTY) else emptyList()
    fun lastModified(path: File): Long =
        legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_UNAVAILABLE)
}
internal interface AndroidLegacyOpenedFile : Closeable {
    fun stat(): AndroidLegacyFileStat
    /** POSIX convention: zero means EOF. */
    fun read(buffer: ByteArray): Int
}

/** Every android.system member used here is public since API21. */
internal object AndroidOsLegacyFileAccess : AndroidLegacyFileAccess {
    override fun lstat(path: File): AndroidLegacyFileStat? = try {
        snapshot(Os.lstat(path.path))
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) null else unavailable()
    }

    override fun open(path: File, directory: Boolean): AndroidLegacyOpenedFile {
        val flags = OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK
        val descriptor = try { Os.open(path.path, flags, 0) } catch (_: ErrnoException) { unavailable() }
        var admitted = false
        try {
            // O_DIRECTORY is not a public Android OsConstants member. Check the
            // actual opened descriptor before returning either kind of handle.
            val opened = try { snapshot(Os.fstat(descriptor)) } catch (_: ErrnoException) { unavailable() }
            val expected = if (directory) AndroidLegacyFileKind.DIRECTORY else AndroidLegacyFileKind.REGULAR
            if (opened.kind != expected) unavailable()
            val handle = object : AndroidLegacyOpenedFile {
                override fun stat(): AndroidLegacyFileStat = try { snapshot(Os.fstat(descriptor)) }
                    catch (_: ErrnoException) { unavailable() }
                override fun read(buffer: ByteArray): Int = try { Os.read(descriptor, buffer, 0, buffer.size) }
                    catch (_: ErrnoException) { unavailable() }
                override fun close() { try { Os.close(descriptor) } catch (_: ErrnoException) { unavailable() } }
            }
            admitted = true
            return handle
        } finally {
            if (!admitted) try { Os.close(descriptor) } catch (_: ErrnoException) { unavailable() }
        }
    }

    override fun hasDirectoryEntry(path: File): Boolean =
        (path.list() ?: unavailable()).isNotEmpty()

    override fun listDirectory(path: File): List<String> = (path.list() ?: unavailable()).toList()
    override fun lastModified(path: File): Long = path.lastModified().also { if (it <= 0) unavailable() }

    private fun snapshot(value: StructStat) = AndroidLegacyFileStat(
        value.st_dev, value.st_ino,
        when {
            OsConstants.S_ISREG(value.st_mode) -> AndroidLegacyFileKind.REGULAR
            OsConstants.S_ISDIR(value.st_mode) -> AndroidLegacyFileKind.DIRECTORY
            else -> AndroidLegacyFileKind.OTHER
        },
        value.st_mode, value.st_nlink, value.st_uid, value.st_gid, value.st_size,
        // API21 exposes second-resolution times. Full bytes are independently read twice;
        // nanosecond fields require API27 and are deliberately not referenced.
        value.st_mtime, value.st_ctime,
    )
    private fun unavailable(): Nothing = legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_UNAVAILABLE)
}

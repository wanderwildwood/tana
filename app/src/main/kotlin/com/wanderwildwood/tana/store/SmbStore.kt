package com.wanderwildwood.tana.store

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * A server's shared drive, over SMB — which is what Samba serves and what the servers here
 * already share their drives as, so nothing on a server has to change for this to reach it.
 *
 * One connection per server, opened on first use and opened again when it has gone stale:
 * a phone drops off the network in a pocket, and a share that answered a minute ago may not
 * now. A call that finds the connection dead retries once on a fresh one, and only then says
 * the server cannot be reached.
 */
class SmbStore(val server: Server) : Store {
    override val id: String = server.storeId

    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    @Synchronized
    private fun share(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        close()
        try {
            val conn = client.connect(server.host)
            val auth = if (server.user.isBlank()) {
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(server.user, server.password.toCharArray(), server.domain.ifBlank { null })
            }
            val sess = conn.authenticate(auth)
            val disk = sess.connectShare(server.share) as? DiskShare
                ?: throw StoreException(StoreException.Reason.NO_SUCH_SHARE, server.share)
            connection = conn
            session = sess
            share = disk
            return disk
        } catch (e: StoreException) {
            throw e
        } catch (e: SMBApiException) {
            throw translate(e, server.share)
        } catch (e: IOException) {
            throw StoreException(StoreException.Reason.SERVER_UNREACHABLE, server.host, e)
        } catch (e: SMBRuntimeException) {
            throw StoreException(StoreException.Reason.SERVER_UNREACHABLE, server.host, e)
        }
    }

    /**
     * [force] is for a connection that has stopped answering. A polite close sends a logoff
     * and waits for the reply, which from a dead connection never comes; and until the socket
     * is actually shut, smbj keeps handing the same dead connection back to the next connect.
     * Otherwise the connection is shared with any other store on the same server, and a polite
     * close only lets go of this store's hold on it.
     */
    @Synchronized
    fun close(force: Boolean = false) {
        if (force) {
            runCatching { connection?.close(true) }
        } else {
            runCatching { share?.close() }
            runCatching { session?.close() }
            runCatching { connection?.close() }
        }
        share = null
        session = null
        connection = null
    }

    /** Runs [block] against the share, once more on a fresh connection if the first one had died. */
    private fun <T> onShare(subject: String, block: (DiskShare) -> T): T {
        var lastProblem: Exception? = null
        repeat(2) { attempt ->
            val disk = share()
            try {
                return block(disk)
            } catch (e: SMBApiException) {
                throw translate(e, subject)
            } catch (e: SMBRuntimeException) {
                lastProblem = e
                close(force = true)
            } catch (e: IOException) {
                lastProblem = e
                close(force = true)
            }
        }
        throw StoreException(StoreException.Reason.SERVER_UNREACHABLE, server.host, lastProblem)
    }

    override fun list(path: String): List<Entry> = onShare(path.substringAfterLast('/')) { disk ->
        disk.list(smb(path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val isFolder = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                Entry(
                    loc = Loc(id, join(path, info.fileName)),
                    isFolder = isFolder,
                    size = if (isFolder) 0 else info.endOfFile,
                    modified = info.lastWriteTime.toEpochMillis(),
                )
            }
    }

    override fun stat(path: String): Entry? {
        if (path.isEmpty()) return Entry(Loc(id, ""), isFolder = true, size = 0, modified = 0)
        return try {
            onShare(path.substringAfterLast('/')) { disk ->
                val info = disk.getFileInformation(smb(path))
                Entry(
                    loc = Loc(id, path),
                    isFolder = info.standardInformation.isDirectory,
                    size = if (info.standardInformation.isDirectory) 0 else info.standardInformation.endOfFile,
                    modified = info.basicInformation.lastWriteTime.toEpochMillis(),
                )
            }
        } catch (e: StoreException) {
            if (e.reason == StoreException.Reason.GONE) null else throw e
        }
    }

    override fun openRead(path: String): InputStream = onShare(path.substringAfterLast('/')) { disk ->
        val file = disk.openFile(
            smb(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        // Closing the stream has to close the handle too, or the server holds the file open.
        object : FilterInputStream(file.inputStream) {
            override fun close() {
                try { super.close() } finally { runCatching { file.close() } }
            }
        }
    }

    override fun openWrite(path: String): OutputStream = onShare(path.substringAfterLast('/')) { disk ->
        val file = disk.openFile(
            smb(path),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        object : FilterOutputStream(file.outputStream) {
            // FilterOutputStream writes one byte at a time unless told otherwise, which over a
            // network is one round trip per byte.
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
            }

            override fun close() {
                try { super.close() } finally { runCatching { file.close() } }
            }
        }
    }

    override fun makeFolder(path: String) = onShare(path.substringAfterLast('/')) { disk ->
        if (disk.folderExists(smb(path)) || disk.fileExists(smb(path))) {
            throw StoreException(StoreException.Reason.ALREADY_THERE, path.substringAfterLast('/'))
        }
        disk.mkdir(smb(path))
    }

    override fun rename(from: String, to: String) = onShare(from.substringAfterLast('/')) { disk ->
        disk.open(
            smb(from),
            EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { entry ->
            // false: never replace what is already at the new name.
            entry.rename(smb(to), false)
        }
    }

    override fun deleteFile(path: String) = onShare(path.substringAfterLast('/')) { disk ->
        disk.rm(smb(path))
    }

    override fun deleteFolder(path: String) = onShare(path.substringAfterLast('/')) { disk ->
        disk.rmdir(smb(path), false)
    }

    override fun setModified(path: String, time: Long) {
        if (time <= 0) return
        runCatching {
            onShare(path.substringAfterLast('/')) { disk ->
                disk.open(
                    smb(path),
                    EnumSet.of(AccessMask.FILE_WRITE_ATTRIBUTES),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                ).use { entry ->
                    val old = entry.fileInformation.basicInformation
                    val stamp = FileTime.ofEpochMillis(time)
                    entry.setFileInformation(
                        FileBasicInformation(old.creationTime, old.lastAccessTime, stamp, old.changeTime, old.fileAttributes),
                    )
                }
            }
        }
        // A date that did not carry over is not worth stopping a copy for; the copy itself
        // is intact either way.
    }

    private fun translate(e: SMBApiException, subject: String): StoreException {
        val reason = when (e.status) {
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
            -> StoreException.Reason.GONE

            NtStatus.STATUS_OBJECT_NAME_COLLISION -> StoreException.Reason.ALREADY_THERE
            NtStatus.STATUS_ACCESS_DENIED -> StoreException.Reason.NOT_ALLOWED
            NtStatus.STATUS_LOGON_FAILURE,
            NtStatus.STATUS_ACCOUNT_DISABLED,
            NtStatus.STATUS_PASSWORD_EXPIRED,
            -> StoreException.Reason.LOGIN_REFUSED

            NtStatus.STATUS_BAD_NETWORK_NAME -> StoreException.Reason.NO_SUCH_SHARE
            NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> StoreException.Reason.CANNOT_DELETE
            else -> StoreException.Reason.SERVER_ERROR
        }
        return StoreException(reason, subject, e)
    }

    companion object {
        /**
         * A timeout on every request, and none on the socket.
         *
         * Each request waits at most 15 seconds for its answer, so a connection that died in a
         * pocket fails a copy rather than hanging it forever. The socket itself has no read
         * timeout on purpose: smbj reads it on a thread of its own that sits idle between
         * requests, and a socket timeout there is taken as a fatal error — the thread ends,
         * the socket stays open, and smbj goes on thinking it is connected while nothing reads
         * the replies. Every connection left alone for longer than the timeout was dead that
         * way, and the next press cost a full timeout to find out.
         */
        private val client = SMBClient(
            SmbConfig.builder()
                .withSecurityProvider(BCSecurityProvider())
                .withTimeout(15, TimeUnit.SECONDS)
                .withSoTimeout(0)
                .withBufferSize(1 shl 20)
                // smbj 0.15 holds a lease on each folder it lists and answers the next listing
                // of it from memory. A change this app makes itself does not break its own
                // lease, so a folder it had just made was missing from the very next listing,
                // and lease traffic left unanswered on an idle connection stalled the next
                // call for the length of a timeout. Every listing goes to the server instead:
                // on a home network it costs a few milliseconds.
                .withDirectoryLeasingEnabled(false)
                .build(),
        )

        /** SMB separates with a backslash, and names the share root as the empty path. */
        fun smb(path: String): String = path.replace('/', '\\')

        fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
    }
}

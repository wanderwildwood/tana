package com.wanderwildwood.tana.work

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Loc
import com.wanderwildwood.tana.store.Stores

/** Internal storage, or the SD card: a place on the phone with a root and a size. */
data class Volume(
    val loc: Loc,
    val isPrimary: Boolean,
    val label: String,
    val free: Long,
    val total: Long,
)

object Volumes {

    /**
     * What is mounted now. Asked for fresh each time the start page shows, because an SD
     * card comes and goes.
     *
     * The label is Android's own description for anything that is not internal storage:
     * the Kompakt's settings show its SD card as 0 B and yet it mounts and reads perfectly
     * well, so the system's description of a volume is trusted and its sums are not —
     * the sizes come from StatFs on the mounted directory itself.
     */
    fun list(context: Context, primaryLabel: String, cardLabel: String): List<Volume> {
        val manager = context.getSystemService(StorageManager::class.java)
        return manager.storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .mapNotNull { volume ->
                val dir = volume.directory ?: return@mapNotNull null
                val stat = runCatching { StatFs(dir.path) }.getOrNull()
                Volume(
                    loc = Loc(LocalStore.ID, Stores.phone.pathOf(dir)),
                    isPrimary = volume.isPrimary,
                    label = if (volume.isPrimary) primaryLabel else volume.getDescription(context)?.takeIf { it.isNotBlank() } ?: cardLabel,
                    free = stat?.availableBytes ?: 0,
                    total = stat?.totalBytes ?: 0,
                )
            }
            .sortedByDescending { it.isPrimary }
    }

    fun downloads(): Loc = Loc(
        LocalStore.ID,
        Stores.phone.pathOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)),
    )
}

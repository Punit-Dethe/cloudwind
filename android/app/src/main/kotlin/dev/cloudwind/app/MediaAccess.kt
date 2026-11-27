package dev.cloudwind.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * How much of the photo library we can see. On Android 14+ the user can share just a few photos;
 * a backup app that ignores that would look healthy while silently skipping everything else.
 */
enum class MediaAccess {
    FULL, PARTIAL, NONE;

    companion object {
        /** Pure so it can be unit-tested. `legacyStorage` is READ_EXTERNAL_STORAGE (Android 12 and older). */
        fun from(sdk: Int, images: Boolean, video: Boolean, selected: Boolean, legacyStorage: Boolean): MediaAccess = when {
            sdk < 33 -> if (legacyStorage) FULL else NONE
            images && video -> FULL
            images || video -> PARTIAL
            sdk >= 34 && selected -> PARTIAL
            else -> NONE
        }

        fun current(ctx: Context): MediaAccess {
            fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
            return from(
                Build.VERSION.SDK_INT,
                images = granted(Manifest.permission.READ_MEDIA_IMAGES),
                video = granted(Manifest.permission.READ_MEDIA_VIDEO),
                selected = granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"),
                legacyStorage = granted(Manifest.permission.READ_EXTERNAL_STORAGE),
            )
        }

        /** Permissions to ask for. On Android 14+ the "selected photos" one must be requested together. */
        fun requestList(sdk: Int): Array<String> = when {
            sdk >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS,
            )
            sdk >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }
}

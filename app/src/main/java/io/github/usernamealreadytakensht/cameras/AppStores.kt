package io.github.usernamealreadytakensht.cameras

import android.content.Context

/**
 * The data stores, one set for the whole process. Created by the first screen that asks, then
 * kept: a screen built again finds them as they are, with the address database already open.
 */
class AppStores private constructor(context: Context) {
    val files = DataFiles(context)
    val addresses = AddressStore(context, files)
    val radars = RadarStore(files)
    val cameras = CameraStore(files)

    companion object {
        @Volatile
        private var instance: AppStores? = null

        fun get(context: Context): AppStores =
            instance ?: synchronized(this) {
                instance ?: AppStores(context.applicationContext).also { instance = it }
            }
    }
}

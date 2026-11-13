package io.github.returnaction.fitness.mobile

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec
import java.util.UUID

class MainActivity : FlutterActivity() {
    private var profileStorageChannel: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        // Same default data preferences as pinned flutter_secure_storage 11.0.0.
        // Keep this aligned if the plugin version or storageNamespace changes.
        val preferences = applicationContext.getSharedPreferences("FlutterSecureStorage", MODE_PRIVATE)
        profileStorageChannel = MethodChannel(
            messenger,
            "fitness/profile_storage",
            StandardMethodCodec.INSTANCE,
            messenger.makeBackgroundTaskQueue(),
        ).also { channel ->
            channel.setMethodCallHandler { call, result ->
                if (call.method != "flush") {
                    result.notImplemented()
                } else {
                    try {
                        // A fresh, non-sensitive value forces a new disk snapshot, including
                        // the preceding encrypted write/delete. commit also waits for apply.
                        val committed = preferences.edit()
                            .putString("profile_disk_barrier", UUID.randomUUID().toString())
                            .commit()
                        if (committed) {
                            result.success(true)
                        } else {
                            result.error("profile_storage_flush_failed", "Could not persist profile.", null)
                        }
                    } catch (_: Exception) {
                        result.error("profile_storage_flush_failed", "Could not persist profile.", null)
                    }
                }
            }
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        profileStorageChannel?.setMethodCallHandler(null)
        profileStorageChannel = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}

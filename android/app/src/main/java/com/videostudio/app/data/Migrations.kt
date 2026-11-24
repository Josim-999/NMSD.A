package com.videostudio.app.data

import android.content.Context

/**
 * Keeps data from older versions working after an update. Runs once at start-up.
 *
 * Rules for every future change, so updates never lose videos or settings:
 * - never rename or remove a key in project.json or Settings; add new ones with defaults;
 * - readers use optString/optInt with defaults (see Project.fromJson);
 * - when data must change shape, bump [CURRENT] and add a step to [run].
 */
object Migrations {
    /** Version of the stored data this app writes. 1 = Video Studio 1.0 to 1.3. */
    const val CURRENT = 1

    fun run(context: Context, store: ProjectStore) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val from = prefs.getInt("data_version", 0)
        if (from >= CURRENT) return
        if (from < 1) {
            // 1.1 kept an Upload-Post key and accounts; 1.2 removed that service.
            prefs.edit()
                .remove("social_api_key").remove("social_profile").remove("social_connected")
                .remove("social_names").remove("social_targets")
                .apply()
        }
        // Future steps go here: if (from < 2) { ... }
        store.reload()
        prefs.edit().putInt("data_version", CURRENT).apply()
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import androidx.annotation.StringRes
import app.morphe.manager.R
import app.morphe.manager.domain.manager.SortModeSpec
import app.morphe.manager.ui.model.HomeAppItem
import app.morphe.manager.util.enumByNameOrNull

enum class HomeAppFilterMode(
    @param:StringRes override val labelRes: Int
) : SortModeSpec {
    ALL(R.string.home_category_all_apps),
    PATCHED(R.string.patched),
    NOT_PATCHED(R.string.home_not_patched_yet),
    INSTALLED(R.string.installed),
    UNINSTALLED(R.string.uninstalled),
    CLONES(R.string.home_app_filter_clones),
    ALL_INSTALLED(R.string.home_app_filter_all_installed),
    USER_APPS(R.string.home_app_filter_user_apps),
    SYSTEM_APPS(R.string.home_app_filter_system_apps),
    LUCKY(R.string.home_app_filter_lucky_supported);

    val isActive: Boolean get() = this != ALL

    fun matches(item: HomeAppItem): Boolean = when (this) {
        // The default list keeps system apps out; ALL_INSTALLED brings them back.
        ALL -> !item.isSystemApp
        PATCHED -> item.installedApp != null
        NOT_PATCHED -> item.installedApp == null
        INSTALLED -> item.isInstalledOnDevice && !item.isDeleted
        UNINSTALLED -> item.installedApp != null && (!item.isInstalledOnDevice || item.isDeleted)
        CLONES -> item.isClone
        ALL_INSTALLED -> true
        USER_APPS -> !item.isSystemApp && item.isInstalledOnDevice && !item.isDeleted
        SYSTEM_APPS -> item.isSystemApp
        LUCKY -> item.hasLuckyPatch && !item.isSystemApp
    }

    companion object {
        /** Parse a persisted enum name; unknown or missing values fall back to [ALL]. */
        fun fromPreference(value: String?): HomeAppFilterMode =
            enumByNameOrNull(value) ?: ALL
    }
}

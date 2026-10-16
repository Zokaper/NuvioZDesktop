package com.nuvio.app.features.tray

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the window's close button does. [Ask] until the user answers the first-close prompt. */
internal enum class DesktopCloseAction { Ask, HideToTray, Quit }

internal data class DesktopTrayPreferencesState(
    val closeAction: DesktopCloseAction = DesktopCloseAction.Ask,
    val popupsEnabled: Boolean = true,
    val popupFriendRequests: Boolean = true,
    /** Party invites and requests to join your playback: the two "someone is waiting on you" kinds. */
    val popupParties: Boolean = true,
    /** Still gated per friend by "Notify me when they start watching" on their profile. */
    val popupFriendWatching: Boolean = true,
    /** Off by default: a game or a fullscreen video should not get a card drawn over it. */
    val popupsOverFullscreen: Boolean = false,
)

/**
 * Tray and pop-up settings (Social settings -> Pop-ups, In the background).
 *
 * **Per machine, not per profile, and not synced.** Whether closing the window hides Nuvio, and what may
 * pop up over this desktop, are facts about this computer - the same reasoning as
 * [com.nuvio.app.features.player.desktop.DesktopWindowModeStorage].
 */
internal object DesktopTrayPreferences {
    private const val CloseActionKey = "close_action"
    private const val PopupsEnabledKey = "popups_enabled"
    private const val PopupFriendRequestsKey = "popup_friend_requests"
    private const val PopupPartiesKey = "popup_parties"
    private const val PopupFriendWatchingKey = "popup_friend_watching"
    private const val PopupsOverFullscreenKey = "popups_over_fullscreen"

    private val store = DesktopStorage.store("nuvio_tray")

    private val _state = MutableStateFlow(load())
    val state: StateFlow<DesktopTrayPreferencesState> = _state.asStateFlow()

    val current: DesktopTrayPreferencesState get() = _state.value

    fun setCloseAction(action: DesktopCloseAction) = update { it.copy(closeAction = action) }
    fun setPopupsEnabled(enabled: Boolean) = update { it.copy(popupsEnabled = enabled) }
    fun setPopupFriendRequests(enabled: Boolean) = update { it.copy(popupFriendRequests = enabled) }
    fun setPopupParties(enabled: Boolean) = update { it.copy(popupParties = enabled) }
    fun setPopupFriendWatching(enabled: Boolean) = update { it.copy(popupFriendWatching = enabled) }
    fun setPopupsOverFullscreen(enabled: Boolean) = update { it.copy(popupsOverFullscreen = enabled) }

    private fun update(transform: (DesktopTrayPreferencesState) -> DesktopTrayPreferencesState) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        store.putString(CloseActionKey, next.closeAction.name)
        store.putBoolean(PopupsEnabledKey, next.popupsEnabled)
        store.putBoolean(PopupFriendRequestsKey, next.popupFriendRequests)
        store.putBoolean(PopupPartiesKey, next.popupParties)
        store.putBoolean(PopupFriendWatchingKey, next.popupFriendWatching)
        store.putBoolean(PopupsOverFullscreenKey, next.popupsOverFullscreen)
    }

    private fun load(): DesktopTrayPreferencesState {
        val defaults = DesktopTrayPreferencesState()
        return DesktopTrayPreferencesState(
            closeAction = store.getString(CloseActionKey)
                ?.let { name -> DesktopCloseAction.entries.firstOrNull { it.name == name } }
                ?: defaults.closeAction,
            popupsEnabled = store.getBoolean(PopupsEnabledKey) ?: defaults.popupsEnabled,
            popupFriendRequests = store.getBoolean(PopupFriendRequestsKey) ?: defaults.popupFriendRequests,
            popupParties = store.getBoolean(PopupPartiesKey) ?: defaults.popupParties,
            popupFriendWatching = store.getBoolean(PopupFriendWatchingKey) ?: defaults.popupFriendWatching,
            popupsOverFullscreen = store.getBoolean(PopupsOverFullscreenKey) ?: defaults.popupsOverFullscreen,
        )
    }
}

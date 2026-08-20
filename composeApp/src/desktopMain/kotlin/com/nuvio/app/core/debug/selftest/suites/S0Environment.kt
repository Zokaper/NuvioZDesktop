package com.nuvio.app.core.debug.selftest.suites

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.debug.DesktopDebugLog
import com.nuvio.app.core.debug.selftest.SelfTestContext
import com.nuvio.app.core.debug.selftest.SelfTestFixtures
import com.nuvio.app.core.debug.selftest.SelfTestRedaction
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.debrid.DebridCredentialValidator
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import java.awt.GraphicsEnvironment
import java.awt.Toolkit

/**
 * What this machine and this install actually are, before anything is asked of them.
 *
 * Runs first and cheaply because everything after it depends on the answers. A run where S1 through
 * S8 all skipped is meaningless without the line saying *"no enabled addons"* - and that is a real
 * possibility here, since a debug-channel build keeps its own `%APPDATA%\Nuvio Z Debug` folder and
 * therefore its own addons, keys and tokens, entirely separate from the release app's.
 */
internal object S0Environment {

    suspend fun run(context: SelfTestContext) {
        collectEnvironment(context)

        context.check("S0.1", "A profile and addons are configured") { scope ->
            AddonRepository.initialize()
            AddonRepository.awaitManifestsLoaded()
            val addons = AddonRepository.uiState.value.addons
            val active = addons.filter { it.isActive }

            scope.value("profile id", ProfileRepository.activeProfileId)
            scope.value("addons installed", addons.size)
            scope.value("addons active", active.size)
            active.forEach { addon ->
                scope.redactedValue(
                    "  ${SelfTestRedaction.addonLabel(addon.manifestUrl)}",
                    addon.manifest?.name?.let(SelfTestRedaction::text).orEmpty(),
                )
            }
            addons.filterNot { it.isActive }.forEach { addon ->
                scope.redactedValue(
                    "  ${SelfTestRedaction.addonLabel(addon.manifestUrl)} (inactive)",
                    addon.errorMessage?.let(SelfTestRedaction::text) ?: "disabled",
                )
            }

            context.addonsConfigured = active.isNotEmpty()
            scope.require(active.isNotEmpty()) {
                "No active addons. A debug-channel build has its own app-data folder " +
                    "(${DesktopStorage.rootDir}) and does not inherit the release app's addons - " +
                    "configure it once, or the source and playback checks cannot run."
            }
            scope.summary = "${active.size} active addon(s) of ${addons.size} installed."
        }

        context.check("S0.2", "Debrid credentials are live") { scope ->
            DebridSettingsRepository.ensureLoaded()
            val settings = DebridSettingsRepository.snapshot()
            scope.value("debrid enabled", settings.enabled)
            scope.value("preferred resolver", settings.preferredResolverProviderId.ifBlank { "(none)" })

            if (!settings.enabled || !settings.hasAnyApiKey) {
                context.debridConfigured = false
                scope.skip("No debrid provider is configured on this install.")
            }

            // ⚠ Keys are read to be *used*, never recorded. The report says which provider
            // answered, and nothing at all about what it was asked with.
            val validations = DebridProviders.all().mapNotNull { provider ->
                val key = settings.apiKeyFor(provider.id).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val valid = runCatching { DebridCredentialValidator.validateProvider(provider.id, key) }
                    .getOrDefault(false)
                scope.value("${provider.id} credentials valid", valid)
                provider.id to valid
            }

            context.debridConfigured = validations.any { it.second }
            scope.require(context.debridConfigured) {
                "Keys are configured for ${validations.joinToString { it.first }} but none validated. " +
                    "Either they have expired or the provider is unreachable from here."
            }
            scope.summary = "Validated: ${validations.filter { it.second }.joinToString { it.first }}."
        }

        context.check("S0.3", "Trakt token refreshes") { scope ->
            TraktAuthRepository.ensureLoaded(ProfileRepository.activeProfileId)
            if (!TraktAuthRepository.hasRequiredCredentials()) {
                scope.skip("Trakt is not connected on this install.")
            }
            // `authorizedHeaders` performs a real refresh when the access token has expired, which
            // is exactly the path worth checking - a stale refresh token fails silently everywhere
            // else and just looks like an empty library.
            val headers = TraktAuthRepository.authorizedHeaders(ProfileRepository.activeProfileId)
            scope.require(headers != null) {
                "Trakt is connected but returned no authorised headers - the refresh token is " +
                    "rejected, so every Trakt-backed row is silently empty."
            }
            scope.summary = "Token valid or refreshed."
        }

        context.check("S0.4", "The native player bridge loads") { scope ->
            // Touching the object runs its `init`, which is where the DLL is loaded. If libmpv or
            // the bridge is missing this throws here rather than on the first play, which is the
            // difference between a named failure and a black screen.
            val controlsPage = runCatching { NativePlayerBridge.controlsPageUrl }.getOrNull()
            scope.require(!controlsPage.isNullOrBlank()) {
                "The player bridge did not produce a controls page URL - libmpv, the bridge DLL " +
                    "or the WebView2 runtime is missing from this install."
            }
            scope.summary = "Bridge loaded."
        }
    }

    private fun collectEnvironment(context: SelfTestContext) {
        val environment = context.environment
        environment["app version"] = AppVersionConfig.DESKTOP_VERSION_NAME
        environment["app version code"] = AppVersionConfig.DESKTOP_VERSION_CODE.toString()
        environment["debug channel"] = AppVersionConfig.DESKTOP_DEBUG_CHANNEL.toString()
        environment["app data"] = DesktopStorage.rootDir.toString()
        environment["debug log"] = DesktopDebugLog.currentLogFile?.toString() ?: "(not installed)"
        environment["os"] = "${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
            System.getProperty("os.arch")
        environment["jvm"] = "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})"
        environment["fixture overrides"] = SelfTestFixtures.overridePath()

        runCatching {
            val screen = Toolkit.getDefaultToolkit().screenSize
            environment["screen"] = "${screen.width}x${screen.height}"
        }
        runCatching {
            val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            environment["display"] = device.iDstring
            // The OS scale factor, which is upstream of the app's own `desktopUiScale`. A layout
            // fault that only shows at 150% Windows scaling is invisible without this line.
            environment["display scale"] = device.defaultConfiguration.defaultTransform.scaleX.toString()
        }
        runCatching {
            PlayerSettingsRepository.ensureLoaded()
            // Playback settings are the ones a later check writes and restores, so their starting
            // values belong in the report whether or not that check runs.
            environment["playback settings"] = SelfTestRedaction.text(
                PlayerSettingsRepository.uiState.value.toString(),
            )
        }
    }
}

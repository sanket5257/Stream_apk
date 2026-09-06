package com.streamforge.app.ui.shell

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.streamforge.app.BuildConfig
import com.streamforge.app.StreamActivity
import com.streamforge.app.auth.AuthManager
import com.streamforge.app.auth.DeviceHelper
import com.streamforge.app.auth.LoginActivity
import com.streamforge.app.billing.LicenseManager
import com.streamforge.app.billing.Tier
import com.streamforge.app.overlay.OverlayItem
import com.streamforge.app.ui.screens.DestinationsScreen
import com.streamforge.app.ui.screens.DiagnosticsScreen
import com.streamforge.app.ui.screens.GraphicsScreen
import com.streamforge.app.ui.screens.HomeScreen
import com.streamforge.app.ui.screens.PackEditorScreen
import com.streamforge.app.ui.screens.ProfileScreen
import com.streamforge.app.ui.screens.QualityScreen
import com.streamforge.app.ui.screens.ScenesScreen
import com.streamforge.app.ui.screens.SupportContact
import com.streamforge.app.ui.screens.ThemeChoice
import com.streamforge.app.ui.screens.UpgradeScreen
import com.streamforge.app.ui.theme.StreamForgeTheme
import com.streamforge.app.update.UpdateFlow
import com.streamforge.app.util.CrashDialog
import com.streamforge.app.util.CrashReporter
import com.streamforge.app.util.runGuarded
import com.streamforge.app.util.safeAction
import com.streamforge.app.util.safeAction1
import com.streamforge.app.util.safeAction2
import com.streamforge.app.util.safeAction3
import com.streamforge.app.util.safeGet
import com.streamforge.app.util.safeLaunch

/**
 * The whole app outside the studio, in one activity.
 *
 * Replaces the old HomeActivity / MainActivity / ProfileActivity trio. Those were three
 * activities sharing state through DataStore reads on every onResume; a single Compose
 * navigation graph over one view model means a change made on one screen is visible on the
 * next without a round-trip through disk.
 *
 * The studio stays a separate activity because it needs a different orientation, a different
 * theme, and a camera lifecycle that has nothing to do with these screens.
 *
 * ## Every callback handed to a screen is wrapped
 *
 * The screens below are pure Compose and take plain lambdas. Those lambdas run on the main
 * thread with nothing above them but the framework's uncaught-exception handler, so a throw
 * in any of them ends the process — which the user experiences as the app vanishing to the
 * launcher when they press a button. Wrapping each one in [safeAction] turns the worst case
 * into "that button didn't appear to do anything, and a message said so".
 */
class ShellActivity : AppCompatActivity() {

    private val authManager by lazy { AuthManager(this) }
    private val licenseManager by lazy { LicenseManager(this) }
    private val uiPrefs by lazy { getSharedPreferences("ui_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            StreamForgeTheme {
                ShellNavigation(
                    activity = this,
                    authManager = authManager,
                    licenseManager = licenseManager,
                    themeChoice = savedThemeChoice(),
                    onThemeChange = ::applyThemeChoice,
                )
            }
        }

        // If the last session ended in a crash, say so here rather than letting the user guess
        // why they were suddenly back at the login screen.
        CrashDialog.showIfCrashed(this)

        // Sideloaded builds have nobody to tell them an update exists, so check here — quietly,
        // at most twice a day, and never while the user is mid-stream (this screen isn't).
        runGuarded(TAG, "checking for updates") { UpdateFlow.checkSilently(this) }

        // Re-check the licence quietly on launch. Failures leave the cached entitlement alone,
        // so a bad connection never downgrades a paying customer.
        lifecycleScope.safeLaunch(TAG, "refreshing licence") { licenseManager.refresh() }
    }

    /**
     * Guarded: this is read during composition, so an unreadable preferences file would take
     * the whole screen down before it ever drew.
     */
    private fun savedThemeChoice(): ThemeChoice = safeGet(TAG, "reading the saved theme", ThemeChoice.SYSTEM) {
        when (uiPrefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)) {
            AppCompatDelegate.MODE_NIGHT_NO -> ThemeChoice.LIGHT
            AppCompatDelegate.MODE_NIGHT_YES -> ThemeChoice.DARK
            else -> ThemeChoice.SYSTEM
        }
    }

    private fun applyThemeChoice(choice: ThemeChoice) {
        val mode = when (choice) {
            ThemeChoice.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeChoice.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeChoice.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        uiPrefs.edit().putInt(KEY_NIGHT_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    companion object {
        private const val TAG = "ShellActivity"
        const val KEY_NIGHT_MODE = "night_mode"
    }
}

/** Tag for everything logged out of the navigation graph below. */
private const val NAV_TAG = "ShellNavigation"

/** What the user is told when a guarded action failed and there is nothing more specific. */
private const val GENERIC_FAILURE = "Something went wrong. That didn't go through — please try again."

private object Routes {
    const val HOME = "home"
    const val DESTINATIONS = "destinations"
    const val GRAPHICS = "graphics"
    const val PACK_EDITOR = "pack/{overlayId}"
    const val SCENES = "scenes"
    const val QUALITY = "quality"
    const val PROFILE = "profile"
    const val DIAGNOSTICS = "diagnostics"
    const val UPGRADE = "upgrade"

    fun packEditor(overlayId: String) = "pack/$overlayId"
}

@Composable
private fun ShellNavigation(
    activity: AppCompatActivity,
    authManager: AuthManager,
    licenseManager: LicenseManager,
    themeChoice: ThemeChoice,
    onThemeChange: (ThemeChoice) -> Unit,
) {
    val viewModel: ShellViewModel = viewModel()
    val navController = rememberNavController()
    val context = LocalContext.current

    val destinations by viewModel.destinations.collectAsStateWithLifecycle()
    val overlays by viewModel.overlays.collectAsStateWithLifecycle()
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val entitlement by viewModel.entitlement.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var currentTheme by remember { mutableStateOf(themeChoice) }
    var activating by remember { mutableStateOf(false) }
    var activationError by remember { mutableStateOf<String?>(null) }

    val snackbarHost = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Surface view-model messages (save failures, tier limits) as a snackbar rather than
    // silently doing nothing when an action is refused.
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(text)
        viewModel.consumeMessage()
    }

    /** Show a message without ever being the thing that fails. */
    val notify: (String) -> Unit = { text ->
        scope.safeLaunch(NAV_TAG, "showing a message") { snackbarHost.showSnackbar(text) }
    }

    // Handed to every guarded callback: the action already failed, so the only job left is to
    // tell the user rather than leaving a button that silently does nothing.
    val onFailure: (Throwable) -> Unit = { notify(GENERIC_FAILURE) }

    // Navigation itself can throw — a route that no longer exists in the graph, or a pop
    // racing the activity's teardown. Route every move through these two.
    val navigate = safeAction1<String>(NAV_TAG, "opening a screen", onFailure) { navController.navigate(it) }
    val goBack = safeAction(NAV_TAG, "going back", onFailure) { navController.popBackStack() }

    val tier = entitlement.effectiveTier()
    val contact = SupportContact(
        whatsApp = BuildConfig.SUPPORT_WHATSAPP,
        phone = BuildConfig.SUPPORT_PHONE,
        email = BuildConfig.SUPPORT_EMAIL,
    )

    androidx.compose.material3.Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { _ ->
        NavHost(navController = navController, startDestination = Routes.HOME) {

            composable(Routes.HOME) {
                // Coming back from the studio, destinations or overlays may have changed.
                LaunchedEffect(Unit) { viewModel.refresh() }
                HomeScreen(
                    destinations = destinations,
                    overlays = overlays,
                    scenes = scenes,
                    tier = tier,
                    resolutionLabel = "${config.height}p · ${config.fps} fps · ${config.videoBitrateKbps} kbps",
                    onGoLive = safeAction(NAV_TAG, "opening the studio", onFailure) {
                        activity.startActivity(Intent(activity, StreamActivity::class.java))
                    },
                    onDestinations = { navigate(Routes.DESTINATIONS) },
                    onGraphics = { navigate(Routes.GRAPHICS) },
                    onScenes = { navigate(Routes.SCENES) },
                    onQuality = { navigate(Routes.QUALITY) },
                    onProfile = { navigate(Routes.PROFILE) },
                    onUpgrade = { navigate(Routes.UPGRADE) },
                )
            }

            composable(Routes.DESTINATIONS) {
                DestinationsScreen(
                    destinations = destinations,
                    tier = tier,
                    onBack = goBack,
                    onSave = safeAction1(NAV_TAG, "saving a destination", onFailure, viewModel::saveDestination),
                    onToggle = safeAction1(NAV_TAG, "toggling a destination", onFailure, viewModel::toggleDestination),
                    onDelete = safeAction1(NAV_TAG, "deleting a destination", onFailure, viewModel::removeDestination),
                    onUpgrade = { navigate(Routes.UPGRADE) },
                )
            }

            composable(Routes.GRAPHICS) {
                GraphicsScreen(
                    catalog = catalog,
                    overlays = overlays,
                    tier = tier,
                    onBack = goBack,
                    onAdd = safeAction1(NAV_TAG, "adding a graphics pack", onFailure, viewModel::addPack),
                    onEdit = safeAction1(NAV_TAG, "opening the pack editor", onFailure) {
                        navController.navigate(Routes.packEditor(it))
                    },
                    onRemove = safeAction1(NAV_TAG, "removing a graphic", onFailure, viewModel::removeOverlay),
                    onUpgrade = { navigate(Routes.UPGRADE) },
                )
            }

            composable(Routes.PACK_EDITOR) { entry ->
                // Guarded: a malformed route argument must not take the screen down on the way
                // to rendering it.
                val overlay = safeGet<OverlayItem.Pack?>(NAV_TAG, "resolving the pack being edited", null) {
                    val overlayId = entry.arguments?.getString("overlayId")
                    overlays.firstOrNull { it.id == overlayId } as? OverlayItem.Pack
                }
                val definition = overlay?.let {
                    safeGet(NAV_TAG, "resolving the pack definition", null) { viewModel.packById(it.packId) }
                }

                // The overlay can vanish underneath this screen (deleted from the studio's
                // overlay sheet), so pop rather than rendering a half-empty editor.
                if (overlay == null || definition == null) {
                    LaunchedEffect(Unit) { goBack() }
                } else {
                    PackEditorScreen(
                        overlay = overlay,
                        definition = definition,
                        onBack = goBack,
                        onValuesChange = safeAction1(NAV_TAG, "editing pack values", onFailure) {
                            viewModel.updatePackValues(overlay.id, it)
                        },
                        onThemeChange = safeAction1(NAV_TAG, "changing the pack theme", onFailure) {
                            viewModel.updatePackTheme(overlay.id, it)
                        },
                        onRemove = safeAction(NAV_TAG, "removing a pack", onFailure) {
                            viewModel.removeOverlay(overlay.id)
                            navController.popBackStack()
                        },
                    )
                }
            }

            composable(Routes.SCENES) {
                ScenesScreen(
                    scenes = scenes,
                    overlays = overlays,
                    catalog = catalog,
                    tier = tier,
                    onBack = goBack,
                    onAdd = safeAction1(NAV_TAG, "adding a scene", onFailure, viewModel::addScene),
                    onRename = safeAction2(NAV_TAG, "renaming a scene", onFailure, viewModel::renameScene),
                    onRemove = safeAction1(NAV_TAG, "removing a scene", onFailure, viewModel::removeScene),
                    onSetVisibility = safeAction3(
                        NAV_TAG, "changing what a scene shows", onFailure, viewModel::setSceneVisibility,
                    ),
                    onUpgrade = { navigate(Routes.UPGRADE) },
                )
            }

            composable(Routes.QUALITY) {
                QualityScreen(
                    config = config,
                    tier = tier,
                    activeDestinationCount = safeGet(NAV_TAG, "counting active destinations", 0) {
                        viewModel.activeDestinations().size
                    },
                    onBack = goBack,
                    onSave = safeAction1(NAV_TAG, "saving quality settings", onFailure, viewModel::saveConfig),
                    onUpgrade = { navigate(Routes.UPGRADE) },
                )
            }

            composable(Routes.PROFILE) {
                ProfileScreen(
                    // Read through safeGet: this touches stored credentials during composition,
                    // and an unreadable store would otherwise crash on the way to drawing.
                    username = safeGet(NAV_TAG, "reading the username", "Account") {
                        authManager.getUsername() ?: "Account"
                    },
                    deviceName = safeGet(NAV_TAG, "reading the device name", "This device") {
                        DeviceHelper.getDeviceName()
                    },
                    versionName = BuildConfig.VERSION_NAME,
                    tier = tier,
                    entitlement = entitlement,
                    themeChoice = currentTheme,
                    updateSubtitle = "You're on v${BuildConfig.VERSION_NAME}",
                    onBack = goBack,
                    onThemeChange = safeAction1(NAV_TAG, "changing the theme", onFailure) {
                        currentTheme = it
                        onThemeChange(it)
                    },
                    onUpgrade = { navigate(Routes.UPGRADE) },
                    onCheckUpdate = safeAction(NAV_TAG, "checking for updates", onFailure) {
                        UpdateFlow.checkManually(activity)
                    },
                    onContactSupport = safeAction(NAV_TAG, "opening support", onFailure) {
                        context.openSupport(contact, null)
                    },
                    onDiagnostics = { navigate(Routes.DIAGNOSTICS) },
                    onLogout = {
                        // Logging out must always land the user at the login screen. If
                        // clearing the session throws (an unreadable keystore, a backend that
                        // won't answer), the sign-out still has to complete locally rather
                        // than stranding them on a screen for an account they've left.
                        scope.safeLaunch(NAV_TAG, "logging out") {
                            try {
                                authManager.logout()
                            } catch (t: Throwable) {
                                android.util.Log.e(NAV_TAG, "Clearing the session failed", t)
                                
                                    CrashReporter.recordNonFatal(NAV_TAG, "logging out", t)
                            }
                            runGuarded(NAV_TAG, "returning to the login screen") {
                                activity.startActivity(
                                    Intent(activity, LoginActivity::class.java).addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    )
                                )
                                activity.finish()
                            }
                        }
                    },
                )
            }

            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(
                    // Read during composition, so guarded: the log lives in a file that may be
                    // missing, empty or unreadable, and none of those is worth a dead screen.
                    report = safeGet(NAV_TAG, "reading the diagnostics log", "") {
                        buildString {
                            CrashReporter.readNonFatals(context)?.let {
                                appendLine("== Errors the app recovered from ==")
                                appendLine(it)
                            }
                            CrashReporter.readReport(context)?.let {
                                appendLine("== Last crash ==")
                                appendLine(it)
                            }
                        }.trim()
                    },
                    onBack = goBack,
                    onCopy = safeAction1(NAV_TAG, "copying the diagnostics log", onFailure) { text: String ->
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("StreamForge diagnostics", text))
                    },
                    onClear = safeAction(NAV_TAG, "clearing the diagnostics log", onFailure) {
                        CrashReporter.clearAll(context)
                    },
                )
            }

            composable(Routes.UPGRADE) {
                UpgradeScreen(
                    currentTier = tier,
                    entitlement = entitlement,
                    contact = contact,
                    activating = activating,
                    activationError = activationError,
                    onBack = goBack,
                    onWhatsApp = safeAction1(NAV_TAG, "opening WhatsApp", onFailure) { plan: Tier ->
                        context.openWhatsApp(contact, plan)
                    },
                    onCall = safeAction(NAV_TAG, "opening the dialler", onFailure) { context.openDialer(contact) },
                    onEmail = safeAction1(NAV_TAG, "opening email", onFailure) { plan: Tier ->
                        context.openEmail(contact, plan)
                    },
                    onActivate = { code ->
                        activationError = null
                        activating = true
                        // Guarded end to end: a licence check hits the network and the
                        // keystore, either of which can throw. Leaving the spinner up forever
                        // — or killing the app — are both worse than showing the error.
                        scope.safeLaunch(NAV_TAG, "activating a licence") {
                            try {
                                when (val result = licenseManager.activate(code)) {
                                    is LicenseManager.Result.Success -> {
                                        viewModel.setEntitlement(result.entitlement)
                                        activating = false
                                        snackbarHost.showSnackbar(
                                            "${result.entitlement.tier.displayName} activated. Enjoy!"
                                        )
                                        navController.popBackStack()
                                    }
                                    is LicenseManager.Result.Failure -> {
                                        activating = false
                                        activationError = result.message
                                    }
                                }
                            } catch (t: Throwable) {
                                android.util.Log.e(NAV_TAG, "Activating a licence failed", t)
                                
                                    CrashReporter.recordNonFatal(NAV_TAG, "activating a licence", t)
                                activating = false
                                activationError =
                                    "Couldn't check that code. Check your connection and try again."
                            }
                        }
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Support hand-off
//
// There is no payment gateway: buying a licence means talking to a person. These build the
// message so the customer doesn't have to explain what they want, and so you get the plan and
// their device in the first message rather than after three back-and-forths.
// ---------------------------------------------------------------------------------------

private fun purchaseMessage(plan: Tier?): String = buildString {
    append("Hi! I'd like to get StreamForge")
    if (plan != null) append(" ${plan.displayName} (${plan.priceLabel})")
    append(".")
    append("\n\nDevice: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    append("\nApp version: ${BuildConfig.VERSION_NAME}")
}

private fun android.content.Context.openWhatsApp(contact: SupportContact, plan: Tier?) {
    if (contact.whatsApp.isBlank()) return
    val text = Uri.encode(purchaseMessage(plan))
    // wa.me works whether or not WhatsApp is installed — it falls back to the browser, which
    // then offers to open the app. That is more reliable than the whatsapp:// scheme, which
    // simply fails on a device without it.
    val uri = Uri.parse("https://wa.me/${contact.whatsApp}?text=$text")
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app available to open WhatsApp", Toast.LENGTH_SHORT).show()
    }
}

private fun android.content.Context.openDialer(contact: SupportContact) {
    if (contact.phone.isBlank()) return
    // ACTION_DIAL, not ACTION_CALL: it opens the dialer with the number filled in and lets the
    // user press call, which needs no permission and never places a call by accident.
    try {
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phone}")))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No dialer available on this device", Toast.LENGTH_SHORT).show()
    }
}

private fun android.content.Context.openEmail(contact: SupportContact, plan: Tier?) {
    if (contact.email.isBlank()) return
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${contact.email}")).apply {
        putExtra(Intent.EXTRA_SUBJECT, "StreamForge ${plan?.displayName ?: "licence"}")
        putExtra(Intent.EXTRA_TEXT, purchaseMessage(plan))
    }
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No email app set up on this device", Toast.LENGTH_SHORT).show()
    }
}

/** Generic support hand-off: WhatsApp if configured, else email, else the dialer. */
private fun android.content.Context.openSupport(contact: SupportContact, plan: Tier?) {
    when {
        contact.whatsApp.isNotBlank() -> openWhatsApp(contact, plan)
        contact.email.isNotBlank() -> openEmail(contact, plan)
        contact.phone.isNotBlank() -> openDialer(contact)
        else -> Toast.makeText(
            this,
            "No support contact is set up in this build.",
            Toast.LENGTH_LONG,
        ).show()
    }
}

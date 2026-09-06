package com.streamforge.app.ui.shell

import android.content.ActivityNotFoundException
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
import com.streamforge.app.util.safeLaunch
import kotlinx.coroutines.launch

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
        UpdateFlow.checkSilently(this)

        // Re-check the licence quietly on launch. Failures leave the cached entitlement alone,
        // so a bad connection never downgrades a paying customer.
        lifecycleScope.safeLaunch(TAG, "refreshing licence") { licenseManager.refresh() }
    }

    private fun savedThemeChoice(): ThemeChoice = when (
        uiPrefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    ) {
        AppCompatDelegate.MODE_NIGHT_NO -> ThemeChoice.LIGHT
        AppCompatDelegate.MODE_NIGHT_YES -> ThemeChoice.DARK
        else -> ThemeChoice.SYSTEM
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

private object Routes {
    const val HOME = "home"
    const val DESTINATIONS = "destinations"
    const val GRAPHICS = "graphics"
    const val PACK_EDITOR = "pack/{overlayId}"
    const val SCENES = "scenes"
    const val QUALITY = "quality"
    const val PROFILE = "profile"
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
                    onGoLive = { activity.startActivity(Intent(activity, StreamActivity::class.java)) },
                    onDestinations = { navController.navigate(Routes.DESTINATIONS) },
                    onGraphics = { navController.navigate(Routes.GRAPHICS) },
                    onScenes = { navController.navigate(Routes.SCENES) },
                    onQuality = { navController.navigate(Routes.QUALITY) },
                    onProfile = { navController.navigate(Routes.PROFILE) },
                    onUpgrade = { navController.navigate(Routes.UPGRADE) },
                )
            }

            composable(Routes.DESTINATIONS) {
                DestinationsScreen(
                    destinations = destinations,
                    tier = tier,
                    onBack = { navController.popBackStack() },
                    onSave = viewModel::saveDestination,
                    onToggle = viewModel::toggleDestination,
                    onDelete = viewModel::removeDestination,
                    onUpgrade = { navController.navigate(Routes.UPGRADE) },
                )
            }

            composable(Routes.GRAPHICS) {
                GraphicsScreen(
                    catalog = catalog,
                    overlays = overlays,
                    tier = tier,
                    onBack = { navController.popBackStack() },
                    onAdd = viewModel::addPack,
                    onEdit = { navController.navigate(Routes.packEditor(it)) },
                    onRemove = viewModel::removeOverlay,
                    onUpgrade = { navController.navigate(Routes.UPGRADE) },
                )
            }

            composable(Routes.PACK_EDITOR) { entry ->
                val overlayId = entry.arguments?.getString("overlayId")
                val overlay = overlays.firstOrNull { it.id == overlayId } as? OverlayItem.Pack
                val definition = overlay?.let { viewModel.packById(it.packId) }

                // The overlay can vanish underneath this screen (deleted from the studio's
                // overlay sheet), so pop rather than rendering a half-empty editor.
                if (overlay == null || definition == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    PackEditorScreen(
                        overlay = overlay,
                        definition = definition,
                        onBack = { navController.popBackStack() },
                        onValuesChange = { viewModel.updatePackValues(overlay.id, it) },
                        onThemeChange = { viewModel.updatePackTheme(overlay.id, it) },
                        onRemove = {
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
                    onBack = { navController.popBackStack() },
                    onAdd = viewModel::addScene,
                    onRename = viewModel::renameScene,
                    onRemove = viewModel::removeScene,
                    onSetVisibility = viewModel::setSceneVisibility,
                    onUpgrade = { navController.navigate(Routes.UPGRADE) },
                )
            }

            composable(Routes.QUALITY) {
                QualityScreen(
                    config = config,
                    tier = tier,
                    activeDestinationCount = viewModel.activeDestinations().size,
                    onBack = { navController.popBackStack() },
                    onSave = viewModel::saveConfig,
                    onUpgrade = { navController.navigate(Routes.UPGRADE) },
                )
            }

            composable(Routes.PROFILE) {
                ProfileScreen(
                    username = authManager.getUsername() ?: "Account",
                    deviceName = DeviceHelper.getDeviceName(),
                    versionName = BuildConfig.VERSION_NAME,
                    tier = tier,
                    entitlement = entitlement,
                    themeChoice = currentTheme,
                    updateSubtitle = "You're on v${BuildConfig.VERSION_NAME}",
                    onBack = { navController.popBackStack() },
                    onThemeChange = { currentTheme = it; onThemeChange(it) },
                    onUpgrade = { navController.navigate(Routes.UPGRADE) },
                    onCheckUpdate = { UpdateFlow.checkManually(activity) },
                    onContactSupport = { context.openSupport(contact, null) },
                    onLogout = {
                        scope.launch {
                            authManager.logout()
                            activity.startActivity(
                                Intent(activity, LoginActivity::class.java).addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                )
                            )
                            activity.finish()
                        }
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
                    onBack = { navController.popBackStack() },
                    onWhatsApp = { plan -> context.openWhatsApp(contact, plan) },
                    onCall = { context.openDialer(contact) },
                    onEmail = { plan -> context.openEmail(contact, plan) },
                    onActivate = { code ->
                        activationError = null
                        activating = true
                        scope.launch {
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


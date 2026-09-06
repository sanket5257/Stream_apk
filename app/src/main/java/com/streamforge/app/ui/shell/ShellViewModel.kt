package com.streamforge.app.ui.shell

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streamforge.app.billing.Entitlement
import com.streamforge.app.billing.EntitlementStore
import com.streamforge.app.billing.Tier
import com.streamforge.app.overlay.OverlayItem
import com.streamforge.app.overlay.OverlayStore
import com.streamforge.app.packs.GraphicsPack
import com.streamforge.app.packs.PackCatalog
import com.streamforge.app.scenes.Scene
import com.streamforge.app.scenes.SceneStore
import com.streamforge.app.storage.DestinationStore
import com.streamforge.app.storage.StreamConfig
import com.streamforge.app.storage.StreamPrefs
import com.streamforge.app.stream.Destination
import com.streamforge.app.util.CrashReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * State for every screen outside the studio.
 *
 * One view model rather than one per screen: the screens all read the same four things
 * (destinations, overlays, scenes, entitlement) and edits on one immediately change what
 * another shows — adding a scoreboard on the Graphics screen has to be visible to the studio
 * and the paywall check straight away. Splitting that into per-screen models would mean
 * synchronising them, which is strictly more work for no benefit at this size.
 *
 * ## Every write goes through [work]
 *
 * Nothing in here calls `viewModelScope.launch` directly, and that is the point. A bare
 * `launch` has no exception handler: anything thrown inside it reaches the thread's uncaught
 * handler and ends the process. Every one of these operations touches storage that can fail
 * for reasons outside the app's control — a corrupt DataStore file, a keystore the device
 * won't unlock, a full disk — so a bare launch here meant that tapping "add a scene" or
 * toggling a destination could drop the user out to the launcher. [work] makes that
 * structurally impossible, and puts the storage call on a background thread while it's there.
 */
class ShellViewModel(application: Application) : AndroidViewModel(application) {

    private val overlayStore = OverlayStore(application)
    private val destinationStore = DestinationStore(application)
    private val sceneStore = SceneStore(application)
    private val streamPrefs = StreamPrefs(application)
    private val entitlementStore = EntitlementStore(application)

    private val _destinations = MutableStateFlow<List<Destination>>(emptyList())
    val destinations: StateFlow<List<Destination>> = _destinations.asStateFlow()

    private val _overlays = MutableStateFlow<List<OverlayItem>>(emptyList())
    val overlays: StateFlow<List<OverlayItem>> = _overlays.asStateFlow()

    private val _scenes = MutableStateFlow<List<Scene>>(emptyList())
    val scenes: StateFlow<List<Scene>> = _scenes.asStateFlow()

    private val _catalog = MutableStateFlow<List<GraphicsPack>>(emptyList())
    val catalog: StateFlow<List<GraphicsPack>> = _catalog.asStateFlow()

    private val _config = MutableStateFlow(StreamConfig.DEFAULT)
    val config: StateFlow<StreamConfig> = _config.asStateFlow()

    private val _entitlement = MutableStateFlow(Entitlement())
    val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    /** One-shot user-facing messages (save failures, tier limits). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val tier: Tier get() = _entitlement.value.effectiveTier()

    init {
        refresh()
    }

    /**
     * Run one storage operation off the main thread, surviving any failure.
     *
     * [failureMessage] is what the user is told if it throws. Pass null for work they did not
     * ask for and need not know about (a background refresh); pass a sentence for anything
     * they initiated, so a save that did not happen never looks like it did.
     */
    private fun work(
        what: String,
        failureMessage: String? = null,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "$what failed", t)
                CrashReporter.recordNonFatal(TAG, what, t)
                failureMessage?.let { post(it) }
            }
        }
    }

    /**
     * Reload everything from storage.
     *
     * Each read is separately guarded so one unreadable store does not blank the other five —
     * a corrupt overlay file should not cost the user their destinations too.
     */
    fun refresh() {
        work("refreshing shell state") {
            runCatching { _destinations.value = destinationStore.load() }
                .onFailure { Log.e(TAG, "Loading destinations failed", it) }
            runCatching { _overlays.value = overlayStore.loadOverlays() }
                .onFailure { Log.e(TAG, "Loading overlays failed", it) }
            runCatching { _scenes.value = sceneStore.load() }
                .onFailure { Log.e(TAG, "Loading scenes failed", it) }
            runCatching { _catalog.value = PackCatalog.load(getApplication()).all }
                .onFailure {
                    Log.e(TAG, "Loading pack catalogue failed", it)
                    // Without this the Graphics screen is simply empty, with no crash and no
                    // trace of why — the exact shape of the "graphics don't work" report.
                    CrashReporter.recordNonFatal(TAG, "loading the pack catalogue", it)
                }
            runCatching { _config.value = streamPrefs.load() }
                .onFailure { Log.e(TAG, "Loading stream config failed", it) }
            runCatching { _entitlement.value = entitlementStore.current() }
                .onFailure { Log.e(TAG, "Loading entitlement failed", it) }
        }
    }

    fun consumeMessage() { _message.value = null }

    private fun post(message: String) { _message.value = message }

    // -----------------------------------------------------------------------------------
    // Destinations
    // -----------------------------------------------------------------------------------

    fun saveDestination(destination: Destination) {
        work("saving a destination", "Couldn't save that destination.") {
            val ok = destinationStore.upsert(destination)
            if (!ok) {
                // The keystore is unusable on this device. Say so rather than showing a saved
                // destination that silently won't be there next launch.
                post("Couldn't save securely on this device. The stream key won't be remembered.")
            }
            _destinations.value = destinationStore.load()
        }
    }

    fun removeDestination(id: String) {
        work("removing a destination", "Couldn't remove that destination.") {
            destinationStore.remove(id)
            _destinations.value = destinationStore.load()
        }
    }

    /**
     * Toggle whether a destination is included in the next broadcast, enforcing the tier's
     * multistream limit at the point of the change so the user finds out here, not at Go Live.
     */
    fun toggleDestination(id: String) {
        val current = _destinations.value
        val target = current.firstOrNull { it.id == id } ?: return
        if (!target.enabled) {
            val activeCount = current.count { it.enabled && it.isConfigured }
            if (activeCount >= tier.maxDestinations) {
                post(
                    if (tier == Tier.STUDIO) "You can stream to ${tier.maxDestinations} destinations at once."
                    else "Streaming to more than one destination at a time needs Studio."
                )
                return
            }
        }
        saveDestination(target.copy(enabled = !target.enabled))
    }

    /** Destinations that will actually be dialled, after the tier limit. */
    fun activeDestinations(): List<Destination> =
        _destinations.value.filter { it.enabled && it.isConfigured }.take(tier.maxDestinations)

    // -----------------------------------------------------------------------------------
    // Graphics packs
    // -----------------------------------------------------------------------------------

    fun packById(id: String): GraphicsPack? = _catalog.value.firstOrNull { it.id == id }

    /** Pack overlays currently in the show, newest last. */
    fun packOverlays(): List<OverlayItem.Pack> = _overlays.value.filterIsInstance<OverlayItem.Pack>()

    /**
     * Add a graphics pack to the show, positioned by its declared anchor so it lands
     * somewhere sensible instead of dead centre on top of whatever is already there.
     */
    fun addPack(pack: GraphicsPack) {
        if (!tier.allows(pack.tier)) {
            post("${pack.name} needs ${if (pack.tier.name == "STUDIO") "Studio" else "Pro"}.")
            return
        }
        work("adding a pack", "Couldn't add that graphic.") {
            // Geometry is computed inside the guard: a malformed pack definition (a zero
            // canvas aspect, a missing default) would otherwise throw straight out of the
            // click handler. OverlayRenderer's base overlay width is 20% of the frame at
            // scale 1.0.
            val scale = (pack.defaultWidth / 0.2f).coerceIn(0.5f, 5f)
            val heightFraction = pack.defaultWidth / pack.canvas.aspect
            val (x, y) = pack.anchor.centerFor(pack.defaultWidth, heightFraction)

            val overlay = OverlayItem.Pack(
                id = UUID.randomUUID().toString(),
                packId = pack.id,
                values = pack.defaultValues(),
                x = x,
                y = y,
                scale = scale,
                heightScale = scale,
                zIndex = (_overlays.value.maxOfOrNull { it.zIndex } ?: 0) + 1,
            )
            overlayStore.addOverlay(overlay)
            _overlays.value = overlayStore.loadOverlays()
        }
    }

    fun updatePackValues(overlayId: String, values: Map<String, String>) {
        val existing = _overlays.value.firstOrNull { it.id == overlayId } as? OverlayItem.Pack ?: return
        val updated = existing.copy(values = values)
        _overlays.value = _overlays.value.map { if (it.id == overlayId) updated else it }
        work("saving pack values") { overlayStore.updateOverlay(updated) }
    }

    fun updatePackTheme(overlayId: String, themeKey: String) {
        val existing = _overlays.value.firstOrNull { it.id == overlayId } as? OverlayItem.Pack ?: return
        val updated = existing.copy(themeKey = themeKey)
        _overlays.value = _overlays.value.map { if (it.id == overlayId) updated else it }
        work("saving pack theme") { overlayStore.updateOverlay(updated) }
    }

    fun removeOverlay(id: String) {
        work("removing an overlay", "Couldn't remove that graphic.") {
            overlayStore.removeOverlay(id)
            _overlays.value = overlayStore.loadOverlays()
        }
    }

    // -----------------------------------------------------------------------------------
    // Scenes
    // -----------------------------------------------------------------------------------

    fun addScene(name: String) {
        if (_scenes.value.size >= tier.maxScenes) {
            post("More than one scene needs Pro.")
            return
        }
        work("adding a scene", "Couldn't add that scene.") {
            sceneStore.upsert(Scene(name = name, order = _scenes.value.size))
            _scenes.value = sceneStore.load()
        }
    }

    fun renameScene(id: String, name: String) {
        val existing = _scenes.value.firstOrNull { it.id == id } ?: return
        work("renaming a scene", "Couldn't rename that scene.") {
            sceneStore.upsert(existing.copy(name = name))
            _scenes.value = sceneStore.load()
        }
    }

    fun removeScene(id: String) {
        work("removing a scene", "Couldn't remove that scene.") {
            sceneStore.remove(id)
            _scenes.value = sceneStore.load()
        }
    }

    /** Store which overlays a scene shows, edited from the Scenes screen. */
    fun setSceneVisibility(sceneId: String, overlayId: String, visible: Boolean) {
        val existing = _scenes.value.firstOrNull { it.id == sceneId } ?: return
        val updated = existing.copy(visibility = existing.visibility + (overlayId to visible))
        _scenes.value = _scenes.value.map { if (it.id == sceneId) updated else it }
        work("saving scene visibility") { sceneStore.upsert(updated) }
    }

    // -----------------------------------------------------------------------------------
    // Quality
    // -----------------------------------------------------------------------------------

    /**
     * Save encoder settings, clamped to the tier's resolution ceiling. Clamping here rather
     * than at Go Live means the user sees what they'll actually get while they're choosing it.
     */
    fun saveConfig(config: StreamConfig) {
        val capped = if (config.height > tier.maxOutputHeight) {
            post("1080p output needs Pro. Saved at 720p.")
            config.copy(width = 1280, height = 720)
        } else config
        _config.value = capped
        work("saving stream config", "Couldn't save those settings.") { streamPrefs.save(capped) }
    }

    fun setEntitlement(entitlement: Entitlement) {
        _entitlement.value = entitlement
    }

    private companion object {
        const val TAG = "ShellViewModel"
    }
}

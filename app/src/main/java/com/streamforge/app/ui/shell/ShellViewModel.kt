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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * State for every screen outside the studio.
 *
 * One view model rather than one per screen: the screens all read the same four things
 * (destinations, overlays, scenes, entitlement) and edits on one immediately change what
 * another shows — adding a scoreboard on the Graphics screen has to be visible to the studio
 * and the paywall check straight away. Splitting that into per-screen models would mean
 * synchronising them, which is strictly more work for no benefit at this size.
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

    fun refresh() {
        viewModelScope.launch {
            runCatching { _destinations.value = destinationStore.load() }
                .onFailure { Log.e(TAG, "Loading destinations failed", it) }
            runCatching { _overlays.value = overlayStore.loadOverlays() }
                .onFailure { Log.e(TAG, "Loading overlays failed", it) }
            runCatching { _scenes.value = sceneStore.load() }
                .onFailure { Log.e(TAG, "Loading scenes failed", it) }
            runCatching { _catalog.value = PackCatalog.load(getApplication()).all }
                .onFailure { Log.e(TAG, "Loading pack catalogue failed", it) }
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        // OverlayRenderer's base overlay width is 20% of the frame at scale 1.0.
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
        viewModelScope.launch {
            runCatching {
                overlayStore.addOverlay(overlay)
                _overlays.value = overlayStore.loadOverlays()
            }.onFailure {
                Log.e(TAG, "Adding pack failed", it)
                post("Couldn't add that graphic.")
            }
        }
    }

    fun updatePackValues(overlayId: String, values: Map<String, String>) {
        val existing = _overlays.value.firstOrNull { it.id == overlayId } as? OverlayItem.Pack ?: return
        val updated = existing.copy(values = values)
        _overlays.value = _overlays.value.map { if (it.id == overlayId) updated else it }
        viewModelScope.launch {
            runCatching { overlayStore.updateOverlay(updated) }
                .onFailure { Log.e(TAG, "Saving pack values failed", it) }
        }
    }

    fun updatePackTheme(overlayId: String, themeKey: String) {
        val existing = _overlays.value.firstOrNull { it.id == overlayId } as? OverlayItem.Pack ?: return
        val updated = existing.copy(themeKey = themeKey)
        _overlays.value = _overlays.value.map { if (it.id == overlayId) updated else it }
        viewModelScope.launch {
            runCatching { overlayStore.updateOverlay(updated) }
                .onFailure { Log.e(TAG, "Saving pack theme failed", it) }
        }
    }

    fun removeOverlay(id: String) {
        viewModelScope.launch {
            runCatching {
                overlayStore.removeOverlay(id)
                _overlays.value = overlayStore.loadOverlays()
            }.onFailure { Log.e(TAG, "Removing overlay failed", it) }
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
        val scene = Scene(name = name, order = _scenes.value.size)
        viewModelScope.launch {
            sceneStore.upsert(scene)
            _scenes.value = sceneStore.load()
        }
    }

    fun renameScene(id: String, name: String) {
        val existing = _scenes.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            sceneStore.upsert(existing.copy(name = name))
            _scenes.value = sceneStore.load()
        }
    }

    fun removeScene(id: String) {
        viewModelScope.launch {
            sceneStore.remove(id)
            _scenes.value = sceneStore.load()
        }
    }

    /** Store which overlays a scene shows, edited from the Scenes screen. */
    fun setSceneVisibility(sceneId: String, overlayId: String, visible: Boolean) {
        val existing = _scenes.value.firstOrNull { it.id == sceneId } ?: return
        val updated = existing.copy(visibility = existing.visibility + (overlayId to visible))
        _scenes.value = _scenes.value.map { if (it.id == sceneId) updated else it }
        viewModelScope.launch { sceneStore.upsert(updated) }
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
        viewModelScope.launch {
            runCatching { streamPrefs.save(capped) }
                .onFailure {
                    Log.e(TAG, "Saving stream config failed", it)
                    post("Couldn't save those settings.")
                }
        }
    }

    fun setEntitlement(entitlement: Entitlement) {
        _entitlement.value = entitlement
    }

    private companion object {
        const val TAG = "ShellViewModel"
    }
}

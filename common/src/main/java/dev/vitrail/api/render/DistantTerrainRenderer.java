package dev.vitrail.api.render;

import dev.vitrail.render.DistantDraw;
import dev.vitrail.Vitrail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/** Entry point for mods that provide far-terrain meshes to Vitrail. */
public final class DistantTerrainRenderer {
    private static final CopyOnWriteArrayList<DistantTerrainProvider> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<DistantTerrainProvider, AtomicLongArray> INVOCATIONS = new ConcurrentHashMap<>();

    private DistantTerrainRenderer() {}

    /** Whether distant terrain currently uses Vitrail's shader-pack-independent renderer. */
    public static boolean usesPlainRenderer() {
        return DistantDraw.usesPlainRenderer();
    }

    /** Registers a provider. Register once during mod initialization and unregister on shutdown. */
    public static void registerProvider(DistantTerrainProvider provider) {
        if (provider == null) throw new NullPointerException("provider");
        if (!PROVIDERS.addIfAbsent(provider)) {
            throw new IllegalStateException("Distant terrain provider is already registered");
        }
        INVOCATIONS.put(provider, new AtomicLongArray(2));
    }

    /** Removes a provider previously registered with {@link #registerProvider}. */
    public static void unregisterProvider(DistantTerrainProvider provider) {
        PROVIDERS.remove(provider);
        INVOCATIONS.remove(provider);
    }

    /** @return whether at least one external terrain provider is registered */
    public static boolean hasProviders() {
        return !PROVIDERS.isEmpty();
    }

    /**
     * Returns the greatest known reach of the registered external far-terrain providers.
     * Providers are independent, so the compatible uniform must cover all geometry submitted by
     * any of them.
     */
    public static int providerRenderDistanceBlocks() {
        int distance = -1;
        for (DistantTerrainProvider provider : PROVIDERS) {
            try {
                distance = Math.max(distance, provider.renderDistanceBlocks());
            } catch (RuntimeException | LinkageError e) {
                Vitrail.logger().error("A distant terrain provider failed to report its render distance", e);
            }
        }
        return distance;
    }

    /** @return the nearest positive near plane requested by an external provider, or -1 */
    public static float providerNearPlaneBlocks() {
        float near = -1.0F;
        for (DistantTerrainProvider provider : PROVIDERS) {
            try {
                float value = provider.nearPlaneBlocks();
                if (value > 0.0F && Float.isFinite(value)) {
                    near = near < 0.0F ? value : Math.min(near, value);
                }
            } catch (RuntimeException | LinkageError e) {
                Vitrail.logger().error("A distant terrain provider failed to report its near plane", e);
            }
        }
        return near;
    }

    /** @return the greatest finite far plane requested by an external provider, or -1 */
    public static float providerFarPlaneBlocks() {
        float far = -1.0F;
        for (DistantTerrainProvider provider : PROVIDERS) {
            try {
                float value = provider.farPlaneBlocks();
                if (value > 0.0F && Float.isFinite(value)) far = Math.max(far, value);
            } catch (RuntimeException | LinkageError e) {
                Vitrail.logger().error("A distant terrain provider failed to report its far plane", e);
            }
        }
        return far;
    }

    /** @return whether Vitrail can invoke providers from Distant Horizons' own terrain pass */
    public static boolean usesDistantHorizonsPass() {
        return dev.vitrail.dh.DhLods.usable();
    }

    /** Counts provider calls by pass so clients can detect whether DH supplied this frame's terrain. */
    public static long providerInvocationCount(DistantTerrainProvider provider, boolean opaque) {
        AtomicLongArray counters = INVOCATIONS.get(provider);
        return counters == null ? 0L : counters.get(opaque ? 0 : 1);
    }

    /** @return whether the shader pack rendered the supplied and registered geometry */
    public static boolean drawWithProviders(boolean opaque, List<DistantTerrainSection> sections) {
        // Sodium is called again for shadow maps. Those callbacks must neither collect
        // camera meshes nor write camera colour targets; DistantDraw.shadow owns that pass.
        if (dev.vitrail.render.TerrainDraw.drawingShadow()) return false;
        boolean plain = DistantDraw.usesPlainRenderer();
        if (plain && !opaque) return false; // Both LOD layers run before vanilla terrain.
        List<DistantTerrainSection> water = new ArrayList<>();
        List<DistantTerrainSection> all = new ArrayList<>(sections);
        for (DistantTerrainProvider provider : PROVIDERS) {
            try {
                AtomicLongArray counters = INVOCATIONS.get(provider);
                if (counters != null) counters.incrementAndGet(opaque ? 0 : 1);
                List<DistantTerrainSection> provided = provider.getSections(opaque);
                if (provided != null && !provided.isEmpty()) all.addAll(provided);
                if (plain) {
                    List<DistantTerrainSection> transparent = provider.getSections(false);
                    if (transparent != null) water.addAll(transparent);
                }
            } catch (RuntimeException | LinkageError e) {
                Vitrail.logger().error("A distant terrain provider failed to supply meshes", e);
            }
        }
        boolean drawn = DistantDraw.draw(opaque, List.copyOf(all));
        if (plain) drawn = DistantDraw.draw(false, List.copyOf(water)) || drawn;
        return drawn;
    }

    /**
     * Draws one half of a far-terrain frame through the active shader pack.
     *
     * <p>Call on the render thread while Vitrail's pack chain is active. The provider retains
     * ownership of every buffer. Opaque geometry should be submitted from the solid terrain stage;
     * translucent geometry from the translucent terrain stage. The section mesh layout is defined
     * by {@link DistantTerrainFormat}.</p>
     *
     * @param opaque true for opaque terrain, false for translucent terrain
     * @param sections meshes to draw for this half
     * @return true if an active pack drew the geometry; false if Vitrail could not take it
     */
    public static boolean draw(boolean opaque, List<DistantTerrainSection> sections) {
        return DistantDraw.draw(opaque, List.copyOf(sections));
    }
}

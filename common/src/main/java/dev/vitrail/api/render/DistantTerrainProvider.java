package dev.vitrail.api.render;

import java.util.List;

/** Supplies far-terrain sections to Vitrail while a shader pack's distant-terrain pass is active. */
@FunctionalInterface
public interface DistantTerrainProvider {
    /**
     * The horizontal reach of this provider's far terrain, in blocks.
     *
     * <p>Shader packs use the Distant Horizons-compatible {@code dhRenderDistance} uniform for
     * fog and fading. Returning the real reach keeps externally supplied terrain from being
     * treated as if it ended at Minecraft's ordinary chunk distance.</p>
     *
     * @return a positive distance in blocks, or {@code -1} when unknown
     */
    default int renderDistanceBlocks() {
        return -1;
    }

    /** @return the near plane used for far terrain, in blocks, or {@code -1} when unknown */
    default float nearPlaneBlocks() {
        return -1.0F;
    }

    /** @return the far plane used for far terrain, in blocks, or {@code -1} when unknown */
    default float farPlaneBlocks() {
        return -1.0F;
    }

    /**
     * Returns meshes for one half of the current frame. Implementations are called on the render
     * thread; returned buffers remain owned by the provider and must stay open through the draw.
     *
     * @param opaque true for opaque terrain, false for translucent terrain
     */
    List<DistantTerrainSection> getSections(boolean opaque);
}

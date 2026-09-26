package dev.vitrail.api.render;

import java.util.List;

/** Supplies far-terrain sections to Vitrail while a shader pack's distant-terrain pass is active. */
@FunctionalInterface
public interface DistantTerrainProvider {
    /**
     * Returns meshes for one half of the current frame. Implementations are called on the render
     * thread; returned buffers remain owned by the provider and must stay open through the draw.
     *
     * @param opaque true for opaque terrain, false for translucent terrain
     */
    List<DistantTerrainSection> getSections(boolean opaque);
}

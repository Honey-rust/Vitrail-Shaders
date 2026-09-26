package dev.vitrail.api.render;

import com.mojang.blaze3d.buffers.GpuBuffer;

import java.util.List;

/**
 * A section of far terrain supplied to Vitrail's distant-terrain renderer.
 *
 * <p>The vertex buffer must use Vitrail's distant mesh layout (16-byte vertices), and the index
 * buffer must contain 32-bit indices. The section origin is expressed in world block coordinates;
 * vertex positions are unsigned-short local block coordinates within that section.</p>
 *
 * <p>Buffers remain owned by the provider. Vitrail only borrows them for the current draw and will
 * skip a piece if either buffer has been closed.</p>
 */
public record DistantTerrainSection(int x, int y, int z, List<Piece> pieces) {
    public DistantTerrainSection {
        pieces = List.copyOf(pieces);
    }

    /** One indexed opaque or translucent mesh belonging to this section. */
    public record Piece(GpuBuffer vertices, GpuBuffer indices, int indexCount) {
        public Piece {
            if (vertices == null || indices == null) {
                throw new NullPointerException("Distant terrain buffers must not be null");
            }
            if (indexCount < 0) {
                throw new IllegalArgumentException("indexCount must not be negative");
            }
        }
    }
}

package dev.vitrail.render;

import dev.vitrail.api.render.DistantTerrainFormat;
import dev.vitrail.glsl.DistantVertex;

import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.List;

/**
 * The vertex format Distant Horizons filled its buffers with, declared here so that a program of
 * the pack can be bound over them.
 * <p>
 * <strong>It is DH's layout and not a format of this engine's</strong>, which is the whole
 * difference from {@code EntityMesh}: nothing here is appended, nothing is chosen, and the buffers
 * were written before this engine saw them. What is decided is only which of the elements a stage
 * declares, and {@link DistantVertex} says why that is not a saving but a requirement.
 * <p>
 * <strong>A format with a hole in it keeps the offsets of the elements around the hole</strong>, and
 * that is what the stride form of {@code addAttribute} is for: told a stride wider than the element,
 * the builder leaves the difference unclaimed and puts the next element where DH really wrote it. A
 * hole closed up instead would read the following element out of the wrong bytes, which is a wrong
 * normal and a wrong material rather than a compile failure. The vertex size is DH's as well, the
 * last element's stride carrying whatever is left, so the sixth element nobody declares is paid for
 * by the padding at the end.
 */
public final class DistantMesh {

	/** Sixteen bytes, including DH's unused texture tile in the final two bytes. */
	public static final int STRIDE = DistantTerrainFormat.STRIDE;

	private DistantMesh() {
	}

	/**
	 * The format to bind for a stage declaring those elements, in DH's own order.
	 *
	 * @param carried the elements really declared, which is {@code DistantVertex.carried} over the
	 *                union of what the pack's far terrain programs read
	 */
	static VertexFormat format(List<String> carried) {
		return DistantTerrainFormat.format(carried);
	}
}

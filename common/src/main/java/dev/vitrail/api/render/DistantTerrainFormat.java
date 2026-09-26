package dev.vitrail.api.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.List;
import java.util.Map;

/** Vertex layout accepted by {@link DistantTerrainSection} buffers. */
public final class DistantTerrainFormat {
    public static final String POSITION = "vPosition";
    public static final String META = "meta";
    public static final String COLOUR = "vColor";
    public static final String MATERIAL = "irisMaterial";
    public static final String NORMAL = "irisNormal";
    public static final int STRIDE = 16;

    private static final List<String> ORDER = List.of(POSITION, META, COLOUR, MATERIAL, NORMAL);
    private static final Map<String, Integer> OFFSETS = Map.of(
            POSITION, 0, META, 6, COLOUR, 8, MATERIAL, 12, NORMAL, 13);
    private static final Map<String, GpuFormat> FORMATS = Map.of(
            POSITION, GpuFormat.RGB16_UINT, META, GpuFormat.R16_UINT,
            COLOUR, GpuFormat.RGBA8_UNORM, MATERIAL, GpuFormat.R8_UINT,
            NORMAL, GpuFormat.R8_UINT);

    private DistantTerrainFormat() {}

    /**
     * Builds the exact Vitrail vertex format for the attributes read by a distant-terrain program.
     * Attributes must be a non-empty, ordered subset of {@link #POSITION}, {@link #META},
     * {@link #COLOUR}, {@link #MATERIAL}, and {@link #NORMAL}; position and meta are required.
     */
    public static VertexFormat format(List<String> attributes) {
        if (attributes == null || attributes.isEmpty() || !attributes.contains(POSITION)
                || !attributes.contains(META)) {
            throw new IllegalArgumentException("Distant terrain requires position and meta attributes");
        }
        int previous = -1;
        for (String attribute : attributes) {
            int offset = ORDER.indexOf(attribute);
            if (offset <= previous) {
                throw new IllegalArgumentException("Unknown or out-of-order distant attribute: " + attribute);
            }
            previous = offset;
        }

        VertexFormat.Builder builder = VertexFormat.builder(0);
        for (int index = 0; index < attributes.size(); index++) {
            String element = attributes.get(index);
            int at = OFFSETS.get(element);
            int next = index + 1 < attributes.size()
                    ? OFFSETS.get(attributes.get(index + 1)) : STRIDE;
            builder.addAttribute(element, next - at, FORMATS.get(element));
        }
        return builder.build();
    }
}

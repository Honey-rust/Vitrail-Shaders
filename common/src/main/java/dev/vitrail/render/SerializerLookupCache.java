package dev.vitrail.render;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializer;

/** Thread-local single-pair cache; registrations invalidate it through a registry epoch. */
public final class SerializerLookupCache {
    public VertexFormat source;
    public VertexFormat destination;
    public VertexSerializer serializer;
    public long epoch = -1;
}

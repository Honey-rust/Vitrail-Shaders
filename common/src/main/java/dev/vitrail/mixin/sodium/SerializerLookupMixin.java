package dev.vitrail.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vitrail.render.SerializerLookupCache;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.concurrent.atomic.AtomicLong;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.vertex.serializers.VertexSerializerRegistryImpl", remap = false)
public abstract class SerializerLookupMixin {
    @Unique private final ThreadLocal<SerializerLookupCache> vitrail$lastSerializer =
            ThreadLocal.withInitial(SerializerLookupCache::new);
    @Unique private final AtomicLong vitrail$serializerEpoch = new AtomicLong();

    @WrapMethod(method = "get", remap = false)
    private VertexSerializer vitrail$reuseSerializer(VertexFormat source, VertexFormat destination,
            Operation<VertexSerializer> original) {
        SerializerLookupCache cache = this.vitrail$lastSerializer.get();
        long epoch = this.vitrail$serializerEpoch.get();
        if (cache.source == source && cache.destination == destination
                && cache.epoch == epoch && cache.serializer != null) return cache.serializer;
        VertexSerializer serializer = original.call(source, destination);
        cache.source = source;
        cache.destination = destination;
        cache.serializer = serializer;
        cache.epoch = epoch;
        return serializer;
    }

    @Inject(method = "registerSerializer", at = @At("RETURN"), require = 1, remap = false)
    private void vitrail$invalidateSerializer(VertexFormat source, VertexFormat destination,
            VertexSerializer serializer, CallbackInfo callback) {
        this.vitrail$serializerEpoch.incrementAndGet();
    }
}

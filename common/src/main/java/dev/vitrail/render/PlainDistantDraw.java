package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.api.render.DistantTerrainFormat;
import dev.vitrail.api.render.DistantTerrainSection;
import com.mojang.blaze3d.*;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.*;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/** Basic opaque LOD rendering when no shader pack is active. Called before vanilla terrain. */
final class PlainDistantDraw {
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "plain_lod");
    private static final String VERTEX = """
        #version 460 core
        in uvec3 vPosition;
        in uint meta;
        in vec4 vColor;

        layout(std140) uniform PlainLod { mat4 mvp; vec4 offset; };
        uniform sampler2D Lightmap;
        out vec3 colour;
        void main() {
            gl_Position = mvp * vec4(vec3(vPosition) + offset.xyz, 1.0);
            vec2 lightUv = (vec2((meta >> 4u) & 15u, meta & 15u) + 0.5) / 16.0;
            colour = vColor.rgb * textureLod(Lightmap, lightUv, 0.0).rgb;
        }
        """;
    private static final String FRAGMENT = """
        #version 460 core
        in vec3 colour;
        layout(location=0) out vec4 fragColour;
        void main() { fragColour = vec4(colour, 1.0); }
        """;
    private static final ShaderSource SOURCE = (id, type) -> !SHADER.equals(id) ? null
            : type == ShaderType.VERTEX ? VERTEX : type == ShaderType.FRAGMENT ? FRAGMENT : null;
    private static RenderPipeline pipeline;
    private static GpuFormat colourFormat;
    private static GpuTexture depth;
    private static GpuTextureView depthView;
    private static int width, height, capacity;
    private static MappableRingBuffer uniforms;
    private static boolean reported, failed;

    static boolean draw(boolean opaque, List<DistantTerrainSection> sections) {
        // Water needs composition with vanilla depth; establish opaque geometry first.
        if (!opaque || sections.isEmpty() || failed || TerrainDraw.drawingShadow()) return false;
        Minecraft mc = Minecraft.getInstance();
        GpuDevice device = RenderSystem.tryGetDevice();
        if (mc == null || mc.level == null || device == null) return false;
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        if (target == null || target.getColorTextureView() == null || mc.gameRenderer.lightmap() == null) return false;
        try {
            GeometryHold.flush(() -> "plain distant terrain");
            GpuFormat format = target.getColorTexture().getFormat();
            if (pipeline == null || colourFormat != format) {
                colourFormat = format;
                pipeline = RenderPipeline.builder().withLocation(SHADER)
                        .withVertexShader(SHADER).withFragmentShader(SHADER)
                        .withBindGroupLayout(BindGroupLayout.builder()
                                .withUniform("PlainLod", UniformType.UNIFORM_BUFFER).withSampler("Lightmap").build())
                        .withVertexBinding(0, DistantTerrainFormat.format(List.of("vPosition", "meta", "vColor")))
                        .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
                        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN, true))
                        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(true).build();
                if (!device.precompilePipeline(pipeline, SOURCE).isValid()) {
                    failed = true;
                    Vitrail.logger().error("Plain LOD pipeline compilation failed");
                    return false;
                }
            }
            if (depth == null || width != target.width || height != target.height) {
                if (depthView != null) depthView.close();
                if (depth != null) depth.close();
                width = target.width; height = target.height;
                depth = device.createTexture(() -> "Plain LOD depth", GpuTexture.USAGE_RENDER_ATTACHMENT,
                        GpuFormat.D32_FLOAT, width, height, 1, 1);
                depthView = device.createTextureView(depth);
            }
            int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
            int stride = ((80 + alignment - 1) / alignment) * alignment;
            int needed = Math.multiplyExact(sections.size(), stride);
            if (uniforms == null || capacity < needed) {
                if (uniforms != null) uniforms.close();
                capacity = needed;
                uniforms = new MappableRingBuffer(() -> "Plain LOD transforms",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, capacity);
            }
            var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            // Infinite reversed-Z projection: retain the vanilla FOV and near distance,
            // but do not clip cached LOD terrain at the vanilla far plane.
            Matrix4f projection = new Matrix4f(camera.projectionMatrix);
            float near = projection.m32() / (1.0f + projection.m22());
            projection.m22(0.0f).m32(near);
            Matrix4f mvp = projection.mul(camera.viewRotationMatrix);
            var position = mc.gameRenderer.mainCamera().position();
            try (var mapped = uniforms.currentBuffer().map(false, true)) {
                var data = mapped.data();
                for (int i = 0; i < sections.size(); i++) {
                    var section = sections.get(i);
                    data.position(i * stride);
                    Std140Builder.intoBuffer(data).putMat4f(mvp).putVec4(
                            (float)(section.x() - position.x), (float)(section.y() - position.y),
                            (float)(section.z() - position.z), 0.0f);
                }
            }
            int pieces = 0;
            try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Plain Voxy LOD",
                    target.getColorTextureView(), Optional.empty(), depthView, OptionalDouble.of(0.0))) {
                pass.setPipeline(pipeline);
                pass.bindTexture("Lightmap", mc.gameRenderer.lightmap(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                for (int i = 0; i < sections.size(); i++) {
                    pass.setUniform("PlainLod", uniforms.currentBuffer().slice((long)i * stride, 80));
                    for (var piece : sections.get(i).pieces()) {
                        if (piece.vertices().isClosed() || piece.indices().isClosed() || piece.indexCount() == 0) continue;
                        pass.setVertexBuffer(0, piece.vertices().slice());
                        pass.setIndexBuffer(piece.indices(), IndexType.INT);
                        pass.drawIndexed(piece.indexCount(), 1, 0, 0, 0);
                        pieces++;
                    }
                }
            } finally {
                uniforms.rotate();
            }
            if (!reported && pieces > 0) {
                reported = true;
                Vitrail.logger().info("Plain Voxy LOD: drew {} sections / {} meshes without a shader pack", sections.size(), pieces);
            }
            return pieces > 0;
        } catch (GpuDeviceLossException e) {
            throw e;
        } catch (RuntimeException e) {
            failed = true;
            Vitrail.logger().error("Plain LOD rendering failed", e);
            return false;
        }
    }

    static void close() {
        if (uniforms != null) uniforms.close();
        if (depthView != null) depthView.close();
        if (depth != null) depth.close();
        uniforms = null; depthView = null; depth = null; pipeline = null;
        capacity = 0; reported = false; failed = false;
    }
}


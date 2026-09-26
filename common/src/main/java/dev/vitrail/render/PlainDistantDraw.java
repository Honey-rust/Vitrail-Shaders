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
    private static final Identifier DETAIL_SHADER = Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "plain_lod_detail");
    private static final String VERTEX = """
        #version 460 core
        in uvec3 vPosition;
        in uint meta;
        in vec4 vColor;
        #ifdef TEXTURED
        in vec2 detailUv;
        in uint detailTile;
        in vec4 detailAverage;
        out vec2 uv;
        flat out uint tile;
        flat out vec4 average;
        out float distanceToCamera;
        #endif

        layout(std140) uniform PlainLod { mat4 mvp; vec4 offset; };
        uniform sampler2D Lightmap;
        out vec4 colour;
        void main() {
            gl_Position = mvp * vec4(vec3(vPosition) + offset.xyz, 1.0);
            vec2 lightUv = (vec2((meta >> 4u) & 15u, meta & 15u) + 0.5) / 16.0;
            colour = vec4(vColor.rgb * textureLod(Lightmap, lightUv, 0.0).rgb, vColor.a);
            #ifdef TEXTURED
            uv = detailUv;
            tile = detailTile;
            average = detailAverage;
            distanceToCamera = length(vec3(vPosition) + offset.xyz);
            #endif
        }
        """;
    private static final String FRAGMENT = """
        #version 460 core
        in vec4 colour;
        #ifdef TEXTURED
        uniform sampler2D LodAtlas;
        in vec2 uv;
        flat in uint tile;
        flat in vec4 average;
        in float distanceToCamera;
        #endif
        layout(location=0) out vec4 fragColour;
        void main() {
            fragColour = colour;
            #ifdef TEXTURED
            vec2 pixel = vec2(tile % 16u, tile / 16u) * 16.0 + clamp(fract(uv) * 16.0, 0.5, 15.5);
            vec4 sampleColour = textureLod(LodAtlas, pixel / vec2(256.0, 1536.0), 0.0);
            if (sampleColour.a <= 1.0 / 255.0) discard;
            vec3 ratio = sampleColour.rgb / max(average.rgb, vec3(1.0 / 255.0));
            float detail = 1.0 - smoothstep(96.0, 256.0, distanceToCamera);
            fragColour.rgb *= mix(vec3(1.0), ratio, detail);
            fragColour.a *= clamp(sampleColour.a / max(average.a, 1.0 / 255.0), 0.0, 1.0);
            #endif
        }
        """;
    private static final ShaderSource SOURCE = (id, type) -> {
        if (!SHADER.equals(id) && !DETAIL_SHADER.equals(id)) return null;
        String source = type == ShaderType.VERTEX ? VERTEX : type == ShaderType.FRAGMENT ? FRAGMENT : null;
        return source != null && DETAIL_SHADER.equals(id) ? source.replace("#version 460 core", "#version 460 core\n#define TEXTURED") : source;
    };
    private static RenderPipeline pipeline;
    private static final RenderPipeline[] pipelines = new RenderPipeline[4];
    private static GpuFormat colourFormat;
    private static GpuTexture depth;
    private static GpuTextureView depthView;
    private static int width, height, capacity;
    private static MappableRingBuffer uniforms;
    private static boolean reported, failed;

    static boolean draw(boolean opaque, List<DistantTerrainSection> sections) {
        // Water needs composition with vanilla depth; establish opaque geometry first.
        if (failed || TerrainDraw.drawingShadow() || (!opaque && (sections.isEmpty() || depth == null))) return false;
        Minecraft mc = Minecraft.getInstance();
        GpuDevice device = RenderSystem.tryGetDevice();
        if (mc == null || mc.level == null || device == null) return false;
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        if (target == null || target.getColorTextureView() == null || mc.gameRenderer.lightmap() == null) return false;
        try {
            GeometryHold.flush(() -> "plain distant terrain");
            GpuFormat format = target.getColorTexture().getFormat();
            if (colourFormat != format) java.util.Arrays.fill(pipelines, null);
            for (boolean detailed : new boolean[]{false, true}) {
            int pipelineIndex = (opaque ? 0 : 1) + (detailed ? 2 : 0);
            pipeline = pipelines[pipelineIndex];
            if (pipeline == null) {
                colourFormat = format;
                var layout = BindGroupLayout.builder().withUniform("PlainLod", UniformType.UNIFORM_BUFFER).withSampler("Lightmap");
                if (detailed) layout.withSampler("LodAtlas");
                var builder = RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID,
                                (opaque ? "plain_lod_opaque" : "plain_lod_translucent") + (detailed ? "_detail" : "")))
                        .withVertexShader(detailed ? DETAIL_SHADER : SHADER).withFragmentShader(detailed ? DETAIL_SHADER : SHADER)
                        .withBindGroupLayout(layout.build())
                        .withVertexBinding(0, DistantTerrainFormat.format(List.of("vPosition", "meta", "vColor")))
                        .withColorTargetState(new ColorTargetState(opaque ? Optional.empty() : Optional.of(BlendFunction.TRANSLUCENT), format, ColorTargetState.WRITE_ALL))
                        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN, opaque))
                        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(true);
                if (detailed) builder.withVertexBinding(1, com.mojang.blaze3d.vertex.VertexFormat.builder(0)
                        .addAttribute("detailUv", 8, GpuFormat.RG32_FLOAT)
                        .addAttribute("detailTile", 4, GpuFormat.R32_UINT)
                        .addAttribute("detailAverage", 4, GpuFormat.RGBA8_UNORM).build());
                pipeline = builder.build();
                pipelines[pipelineIndex] = pipeline;
                if (!device.precompilePipeline(pipeline, SOURCE).isValid()) {
                    failed = true;
                    Vitrail.logger().error("Plain LOD pipeline compilation failed");
                    return false;
                }
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
            int needed = Math.multiplyExact(Math.max(1, sections.size()), stride);
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
            if (!opaque) sections = sections.stream().sorted(java.util.Comparator.comparingDouble(
                    (DistantTerrainSection s) -> -((s.x() + 64.0 - position.x) * (s.x() + 64.0 - position.x)
                            + (s.y() + 64.0 - position.y) * (s.y() + 64.0 - position.y)
                            + (s.z() + 64.0 - position.z) * (s.z() + 64.0 - position.z)))).toList();
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
                    target.getColorTextureView(), Optional.empty(), depthView, opaque ? OptionalDouble.of(0.0) : OptionalDouble.empty())) {
                pass.setPipeline(pipeline);
                pass.bindTexture("Lightmap", mc.gameRenderer.lightmap(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                for (int i = 0; i < sections.size(); i++) {
                    pass.setUniform("PlainLod", uniforms.currentBuffer().slice((long)i * stride, 80));
                    for (var piece : sections.get(i).pieces()) {
                        if (piece.vertices().isClosed() || piece.indices().isClosed() || piece.indexCount() == 0) continue;
                        boolean detailed = piece.detail() != null && !piece.detail().isClosed();
                        pass.setPipeline(pipelines[(opaque ? 0 : 1) + (detailed ? 2 : 0)]);
                        if (detailed) {
                            pass.setVertexBuffer(1, piece.detail().slice());
                            pass.bindTexture("LodAtlas", piece.atlas(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                        }
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
        java.util.Arrays.fill(pipelines, null);
        capacity = 0; reported = false; failed = false;
    }
}


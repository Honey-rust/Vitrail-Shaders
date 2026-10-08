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

/** Basic LOD rendering when no shader pack is active. Composited against vanilla world depth. */
public final class PlainDistantDraw {
    private static final Matrix4f terrainProjection = new Matrix4f();
    private static final Matrix4f terrainView = new Matrix4f();
    private static net.minecraft.world.phys.Vec3 terrainPosition;

    /** Captured directly from the terrain invocation, before submitting the provider meshes. */
    public static void captureTerrainView(org.joml.Matrix4fc projection, org.joml.Matrix4fc view,
            double x, double y, double z) {
        terrainProjection.set(projection);
        terrainView.set(view);
        terrainPosition = new net.minecraft.world.phys.Vec3(x, y, z);
    }

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

        layout(std140) uniform PlainLod { mat4 mvp; mat4 vanillaMvp; vec4 offset; };
        uniform sampler2D Lightmap;
        out vec4 colour;
        out vec3 relativePosition;
        flat out uint suppressNear;
        void main() {
            vec3 relative = vec3(vPosition) + offset.xyz;
            gl_Position = mvp * vec4(relative, 1.0);
            relativePosition = relative;
            suppressNear = (meta >> 15u) & 1u;
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
        in vec3 relativePosition;
        flat in uint suppressNear;
        layout(std140) uniform PlainLod { mat4 mvp; mat4 vanillaMvp; vec4 offset; };
        #ifdef TEXTURED
        uniform sampler2D LodAtlas;
        in vec2 uv;
        flat in uint tile;
        flat in vec4 average;
        in float distanceToCamera;
        #endif
        layout(location=0) out vec4 fragColour;
        void main() {
            // Match actual scene depth instead of cutting a square out of the world.
            // The raster projection reaches infinity, while scene geometry uses finite reversed Z.
            // Put fragments inside vanilla's far plane into that exact depth volume. Preserve a
            // monotonic positive tail beyond it so distant surfaces still occlude each other.
            vec4 sceneClip = vanillaMvp * vec4(relativePosition, 1.0);
            float sceneDepth = sceneClip.z / sceneClip.w;
            float infiniteDepth = gl_FragCoord.z;
            gl_FragDepth = sceneDepth > 0.0
                ? max(sceneDepth * 0.9999, 1e-12)
                : max(infiniteDepth * 1e-7, 1e-12);
            // Thin/non-cubic proxies cannot faithfully replace nearby torches and flowers.
            if (suppressNear != 0u && length(relativePosition.xz) < abs(offset.w)) discard;
            fragColour = colour;
            #ifdef TEXTURED
            vec2 pixel = vec2(tile % 16u, tile / 16u) * 16.0 + clamp(fract(uv) * 16.0, 0.5, 15.5);
            vec4 sampleColour = textureLod(LodAtlas, pixel / vec2(256.0, 12288.0), 0.0);
            float detail = 1.0 - smoothstep(96.0, 256.0, distanceToCamera);
            // Preserve the cutout silhouette while it still covers enough pixels to read.  At
            // coarse distance the texel holes in leaves and other cutouts become white noise;
            // Voxy's face average is the stable representation for that footprint.
            if (detail > 1.0 / 255.0 && sampleColour.a <= 1.0 / 255.0) discard;
            vec3 ratio = sampleColour.rgb / max(average.rgb, vec3(1.0 / 255.0));
            fragColour.rgb *= mix(vec3(1.0), ratio, detail);
            float alphaRatio = clamp(sampleColour.a / max(average.a, 1.0 / 255.0), 0.0, 1.0);
            fragColour.a *= mix(1.0, alphaRatio, detail);
            #endif
        }
        """;
    private static final ShaderSource SOURCE = GraphicsApi.source((id, type) -> {
        if (!SHADER.equals(id) && !DETAIL_SHADER.equals(id)) return null;
        String source = type == ShaderType.VERTEX ? VERTEX : type == ShaderType.FRAGMENT ? FRAGMENT : null;
        return source != null && DETAIL_SHADER.equals(id) ? source.replace("#version 460 core", "#version 460 core\n#define TEXTURED") : source;
    });
    private static RenderPipeline pipeline;
    private static final RenderPipeline[] pipelines = new RenderPipeline[4];
    private static GpuFormat colourFormat;
    private static int width, height, capacity;
    private static MappableRingBuffer uniforms;
    private static boolean reported, failed;
    static boolean draw(boolean opaque, List<DistantTerrainSection> sections) {
        // Water needs an opaque half first; provider terrain can use the world's real depth once
        // Sodium has completed its solid pass.
        if (failed || TerrainDraw.drawingShadow() || sections.isEmpty()) return false;
        Minecraft mc = Minecraft.getInstance();
        GpuDevice device = RenderSystem.tryGetDevice();
        if (mc == null || mc.level == null || device == null) return false;
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        if (target == null || target.getColorTextureView() == null
                || target.getDepthTextureView() == null || mc.gameRenderer.lightmap() == null) return false;
        try {
            GeometryHold.flush(() -> "plain distant terrain");
            GpuFormat format = target.getColorTexture().getFormat();
            if (colourFormat != format) forgetPipelines();
            for (boolean detailed : new boolean[]{false, true}) {
            int pipelineIndex = (opaque ? 0 : 1) + (detailed ? 2 : 0);
            pipeline = pipelines[pipelineIndex];
            if (pipeline == null) {
                colourFormat = format;
                var builder = RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID,
                                (opaque ? "plain_lod_opaque" : "plain_lod_translucent") + (detailed ? "_detail" : "")))
                        .withVertexShader(detailed ? DETAIL_SHADER : SHADER).withFragmentShader(detailed ? DETAIL_SHADER : SHADER)
                        .withBindGroupLayout(detailed
                                ? GraphicsApi.blockAndSamplers("PlainLod", "Lightmap", "LodAtlas")
                                : GraphicsApi.blockAndSamplers("PlainLod", "Lightmap"))
                        .withVertexBinding(0, DistantTerrainFormat.format(
                                List.of("vPosition", "meta", "vColor")))
                        .withColorTargetState(new ColorTargetState(opaque ? Optional.empty() : Optional.of(BlendFunction.TRANSLUCENT), format, ColorTargetState.WRITE_ALL))
                        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN, opaque))
                        // Provider quads can arrive with either winding after axis/tile splitting.
                        // Two-sided rasterisation prevents a whole compass direction disappearing.
                        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
                if (detailed) builder.withVertexBinding(1, com.mojang.blaze3d.vertex.VertexFormat.builder(0)
                        .addAttribute("detailUv", 8, GpuFormat.RG32_FLOAT)
                        .addAttribute("detailTile", 4, GpuFormat.R32_UINT)
                        .addAttribute("detailAverage", 4, GpuFormat.RGBA8_UNORM).build());
                pipeline = builder.build();
                pipelines[pipelineIndex] = pipeline;
                if (!GraphicsApi.valid(GraphicsApi.compile(device, pipeline, SOURCE))) {
                    failed = true;
                    Vitrail.logger().error("Plain LOD pipeline compilation failed");
                    return false;
                }
            }
            }
            width = target.width;
            height = target.height;
            int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
            int stride = ((144 + alignment - 1) / alignment) * alignment;
            int needed = Math.multiplyExact(Math.max(1, sections.size()), stride);
            if (uniforms == null || capacity < needed) {
                if (uniforms != null) uniforms.close();
                capacity = needed;
                uniforms = new MappableRingBuffer(() -> "Plain LOD transforms",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, capacity);
            }
            var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            // This pass is called from Sodium's active terrain render. Read the matrix that
            // Sodium/RenderSystem is using at that instant instead of the extracted frame-state
            // copy, which can still describe the preceding direction during a fast turn.
            Matrix4f view = terrainPosition != null ? new Matrix4f(terrainView)
                    : RenderSystem.getModelViewMatrixCopy();
            Matrix4f original = terrainPosition != null ? new Matrix4f(terrainProjection)
                    : new Matrix4f(CapturedProjection.rendered(camera.projectionMatrix));
            Matrix4f vanillaMvp = new Matrix4f(original).mul(view);
            // Remove the finite far term from the complete depth row (including camera effects).
            float a = camera.projectionMatrix.m22();
            float b = camera.projectionMatrix.m32();
            float near = b / (1.0f + a);
            float scale = near / b;
            Matrix4f projection = new Matrix4f(original);
            projection.m02(scale * (original.m02() + a * original.m03()));
            projection.m12(scale * (original.m12() + a * original.m13()));
            projection.m22(scale * (original.m22() + a * original.m23()));
            projection.m32(scale * (original.m32() + a * original.m33()));
            Matrix4f mvp = projection.mul(view);
            var position = terrainPosition != null ? terrainPosition : mc.gameRenderer.mainCamera().position();
            float vanillaDistance = Math.max(32.0F,
                    mc.options.getEffectiveRenderDistance() * 16.0F);
            if (!opaque) sections = sections.stream().sorted(java.util.Comparator.comparingDouble(
                    (DistantTerrainSection s) -> -((s.x() + 64.0 - position.x) * (s.x() + 64.0 - position.x)
                            + (s.y() + 64.0 - position.y) * (s.y() + 64.0 - position.y)
                            + (s.z() + 64.0 - position.z) * (s.z() + 64.0 - position.z)))).toList();
            try (var mapped = uniforms.currentBuffer().map(false, true)) {
                var data = mapped.data();
                for (int i = 0; i < sections.size(); i++) {
                    var section = sections.get(i);
                    data.position(i * stride);
                    Std140Builder.intoBuffer(data).putMat4f(mvp).putMat4f(vanillaMvp).putVec4(
                            (float)(section.x() - position.x), (float)(section.y() - position.y),
                            (float)(section.z() - position.z), vanillaDistance);
                }
            }
            int pieces = 0;
            try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Plain Voxy LOD",
                    target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(),
                    OptionalDouble.empty())) {
                GraphicsApi.setPipeline(pass, pipeline);
                GraphicsApi.bindTexture(pass, "Lightmap", mc.gameRenderer.lightmap(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                for (int i = 0; i < sections.size(); i++) {
                    pass.setUniform("PlainLod", uniforms.currentBuffer().slice((long)i * stride, 144));
                    for (var piece : sections.get(i).pieces()) {
                        if (piece.vertices().isClosed() || piece.indices().isClosed() || piece.indexCount() == 0) continue;
                        boolean detailed = piece.detail() != null && !piece.detail().isClosed();
                        GraphicsApi.setPipeline(pass,
                                pipelines[(opaque ? 0 : 1) + (detailed ? 2 : 0)]);
                        if (detailed) {
                            pass.setVertexBuffer(1, piece.detail().slice());
                            GraphicsApi.bindTexture(pass, "LodAtlas", piece.atlas(),
                                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
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
        uniforms = null; pipeline = null;
        forgetPipelines();
        capacity = 0; reported = false; failed = false; terrainPosition = null;
    }

    private static void forgetPipelines() {
        for (int index = 0; index < pipelines.length; index++) {
            RenderPipeline held = pipelines[index];
            if (held != null) GraphicsApi.forget(held);
            pipelines[index] = null;
        }
    }
}



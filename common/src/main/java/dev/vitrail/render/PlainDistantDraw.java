package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.api.render.DistantTerrainFormat;
import dev.vitrail.api.render.DistantTerrainSection;
import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
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
        flat out uint solidLeaves;
        void main() {
            vec3 relative = vec3(vPosition) + offset.xyz;
            #ifdef TEXTURED
            relative += vec3(float((detailTile >> 14u) & 63u), float((detailTile >> 20u) & 63u), float((detailTile >> 26u) & 63u)) / 64.0;
            #endif
            gl_Position = mvp * vec4(relative, 1.0);
            relativePosition = relative;
            suppressNear = (meta >> 15u) & 1u;
            solidLeaves = (meta >> 14u) & 1u;
            vec2 lightUv = (vec2((meta >> 4u) & 15u, meta & 15u) + 0.5) / 16.0;
            colour = vec4(vColor.rgb * textureLod(Lightmap, lightUv, 0.0).rgb, vColor.a);
            #ifdef TEXTURED
            uv = detailUv;
            tile = detailTile & 16383u;
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
        flat in uint solidLeaves;
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
            // Do not clamp the tail to a fixed minimum: at high altitude/distance
            // that makes different surfaces share a depth and exposes buried faces.
            // Both terms decrease with distance. Their maximum also joins them
            // continuously at the finite far plane, without reversing depth order.
            gl_FragDepth = max(sceneDepth * 0.9999, infiniteDepth * 1e-7);
            // Thin/non-cubic proxies cannot faithfully replace nearby torches and flowers.
            if (suppressNear != 0u && length(relativePosition.xz) < abs(offset.w)) discard;
            fragColour = colour;
            #ifdef TEXTURED
            float footprint = max(length(dFdx(uv) * 16.0), length(dFdy(uv) * 16.0));
            float detail = 1.0 - smoothstep(0.75, 3.0, footprint);
            // Preserve the original repeating texture orientation.
            vec2 repeatUv = fract(uv);
            vec2 pixel = vec2(tile % 16u, tile / 16u) * 16.0 + clamp(repeatUv * 16.0, 0.5, 15.5);
            vec4 sampleColour = detail > 0.0 ? textureLod(LodAtlas, pixel / vec2(256.0, 12288.0), 0.0) : average;
            if (solidLeaves != 0u) {
                sampleColour.rgb = mix(average.rgb, sampleColour.rgb, sampleColour.a);
                sampleColour.a = 1.0;
                fragColour.a = 1.0;
            }
            // Choose texture detail by projected texel footprint, so zoom and FOV remain consistent.
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
    private static int width, height;
    private static final int[] capacities = new int[2];
    private static final MappableRingBuffer[] passUniforms = new MappableRingBuffer[2];
    private static List<DistantTerrainSection> waterInput, waterOrder;
    private static double waterX, waterY, waterZ;
    private static boolean reported, failed;
    private static long nextDrawReport;
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
            for (int variant = 0; variant < 2; variant++) {
            boolean detailed = variant != 0;
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
            int passIndex = opaque ? 0 : 1;
            MappableRingBuffer uniforms = passUniforms[passIndex];
            if (uniforms == null || capacities[passIndex] < needed) {
                if (uniforms != null) uniforms.close();
                // Grow geometrically so moving across section boundaries does not keep
                // retiring GPU uniform buffers for small increases in the visible set.
                int capacity = Math.multiplyExact(Integer.highestOneBit(needed - 1), 2);
                uniforms = new MappableRingBuffer(() -> "Plain LOD transforms",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, capacity);
                passUniforms[passIndex] = uniforms;
                capacities[passIndex] = capacity;
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
            if (!opaque) {
                // Ordering depends on position and the section snapshot, never camera rotation.
                if (waterInput != sections || waterX != position.x || waterY != position.y || waterZ != position.z) {
                    waterOrder = sections.stream().sorted(java.util.Comparator.comparingDouble(
                            (DistantTerrainSection section) -> -((section.x() + 64.0 - position.x) * (section.x() + 64.0 - position.x)
                                    + (section.y() + 64.0 - position.y) * (section.y() + 64.0 - position.y)
                                    + (section.z() + 64.0 - position.z) * (section.z() + 64.0 - position.z)))).toList();
                    waterInput = sections;
                    waterX = position.x; waterY = position.y; waterZ = position.z;
                }
                sections = waterOrder;
            }
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
            int pieces = 0, culled = 0;
            try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Plain Voxy LOD",
                    target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(),
                    OptionalDouble.empty())) {
                GraphicsApi.setPipeline(pass, pipeline);
                RenderPipeline activePipeline = pipeline;
                GpuTextureView activeAtlas = null;
                GraphicsApi.bindTexture(pass, "Lightmap", mc.gameRenderer.lightmap(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                for (int i = 0; i < sections.size(); i++) {
                    var section = sections.get(i);
                    float offsetX = (float)(section.x() - position.x);
                    float offsetY = (float)(section.y() - position.y);
                    float offsetZ = (float)(section.z() - position.z);
                    boolean sectionBound = false;
                    for (var piece : section.pieces()) {
                        if (piece.vertices().isClosed() || piece.indices().isClosed() || piece.indexCount() == 0) continue;
                        var bounds = piece.bounds();
                        if (bounds != null && !visible(mvp, bounds, offsetX, offsetY, offsetZ)) { culled++; continue; }
                        if (!sectionBound) {
                            pass.setUniform("PlainLod", uniforms.currentBuffer().slice((long)i * stride, 144));
                            sectionBound = true;
                        }
                        boolean detailed = piece.detail() != null && !piece.detail().isClosed();
                        RenderPipeline nextPipeline = pipelines[(opaque ? 0 : 1) + (detailed ? 2 : 0)];
                        if (activePipeline != nextPipeline) {
                            GraphicsApi.setPipeline(pass, nextPipeline);
                            activePipeline = nextPipeline;
                            activeAtlas = null;
                        }
                        if (detailed) {
                            pass.setVertexBuffer(1, piece.detail().slice());
                            if (activeAtlas != piece.atlas()) {
                                GraphicsApi.bindTexture(pass, "LodAtlas", piece.atlas(),
                                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                                activeAtlas = piece.atlas();
                            }
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
            long now = System.nanoTime();
            if (opaque && now >= nextDrawReport) {
                nextDrawReport = now + 5_000_000_000L;
                Vitrail.logger().info("Plain LOD visibility: {} opaque meshes submitted, {} outside view retained in cache",
                        pieces, culled);
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

    /** Infinite reversed-Z volume: omit the far plane and keep conservative AABB tests. */
    private static boolean visible(Matrix4f m, DistantTerrainSection.Bounds b, float x, float y, float z) {
        float minX = b.minX() + x, minY = b.minY() + y, minZ = b.minZ() + z;
        float maxX = b.maxX() + x, maxY = b.maxY() + y, maxZ = b.maxZ() + z;
        return inside(m.m03()+m.m00(),m.m13()+m.m10(),m.m23()+m.m20(),m.m33()+m.m30(),minX,minY,minZ,maxX,maxY,maxZ)
            && inside(m.m03()-m.m00(),m.m13()-m.m10(),m.m23()-m.m20(),m.m33()-m.m30(),minX,minY,minZ,maxX,maxY,maxZ)
            && inside(m.m03()+m.m01(),m.m13()+m.m11(),m.m23()+m.m21(),m.m33()+m.m31(),minX,minY,minZ,maxX,maxY,maxZ)
            && inside(m.m03()-m.m01(),m.m13()-m.m11(),m.m23()-m.m21(),m.m33()-m.m31(),minX,minY,minZ,maxX,maxY,maxZ)
            && inside(m.m03()-m.m02(),m.m13()-m.m12(),m.m23()-m.m22(),m.m33()-m.m32(),minX,minY,minZ,maxX,maxY,maxZ);
    }

    private static boolean inside(float a,float b,float c,float d,
            float minX,float minY,float minZ,float maxX,float maxY,float maxZ) {
        float support = a*(a>=0?maxX:minX)+b*(b>=0?maxY:minY)+c*(c>=0?maxZ:minZ)+d;
        return !Float.isFinite(support) || support >= -0.01f;
    }

    static void close() {
        for (int i = 0; i < passUniforms.length; i++) {
            if (passUniforms[i] != null) passUniforms[i].close();
            passUniforms[i] = null;
            capacities[i] = 0;
        }
        waterInput = null; waterOrder = null;
        pipeline = null;
        forgetPipelines();
        reported = false; failed = false; terrainPosition = null; nextDrawReport = 0;
    }

    private static void forgetPipelines() {
        for (int index = 0; index < pipelines.length; index++) {
            RenderPipeline held = pipelines[index];
            if (held != null) GraphicsApi.forget(held);
            pipelines[index] = null;
        }
    }
}



package dev.vitrail.render;

import java.util.regex.Pattern;

/** Optional Voxy face atlas. DH buffers without this stream keep their original program. */
final class DistantDetail {
    static final String SAMPLER = "ofVoxyAtlas";
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
    private static final Pattern COLOUR = Pattern.compile("\\b(\\w+)\\s*=\\s*of_Color\\s*;");

    private DistantDetail() {}

    static String colour(String vertex, String fragment) {
        var assignments = COLOUR.matcher(vertex);
        while (assignments.find()) {
            String name = assignments.group(1);
            if (Pattern.compile("\\bout\\s+vec4\\s+" + name + "\\s*;").matcher(vertex).find()
                    && input(name).matcher(fragment).find()) return name;
        }
        return null;
    }

    private static Pattern input(String name) {
        return Pattern.compile("\\bin\\s+vec4\\s+" + Pattern.quote(name) + "\\s*;");
    }

    private static String header(String source, String declarations) {
        // Keep version/extension directives ahead of declarations.
        var directives = Pattern.compile("(?m)^\\s*#(?:version|extension)\\b[^\\n]*\\n").matcher(source);
        int end = 0;
        while (directives.find()) end = directives.end();
        return source.substring(0, end) + declarations + source.substring(end);
    }

    static String vertex(String source, String colour) {
        source = source.replace("vec3(vPosition)", "(vec3(vPosition) + vec3(float((detailTile >> 14u) & 63u), float((detailTile >> 20u) & 63u), float((detailTile >> 26u) & 63u)) / 64.0)");
        source = Pattern.compile("\\bout\\s+vec4\\s+" + Pattern.quote(colour) + "\\s*;").matcher(source)
                .replaceAll("out vec4 ofLodRawColour; vec4 " + colour + ";");
        source = header(source, """
                in vec2 detailUv;
                in uint detailTile;
                in vec4 detailAverage;
                out vec2 ofLodUv;
                flat out uint ofLodTile;
                flat out vec4 ofLodAverage;
                """);
        source = MAIN.matcher(source).replaceAll("void ofLodPackVertex()");
        return source + "\nvoid main() {\n ofLodPackVertex();\n ofLodRawColour = " + colour + ";\n" + """
                    ofLodUv = detailUv;
                    ofLodTile = detailTile & 16383u;
                    ofLodAverage = detailAverage;
                }
                """;
    }

    static String fragment(String source, String colour) {
        source = header(source, """
                uniform sampler2D ofVoxyAtlas;
                in vec2 ofLodUv;
                flat in uint ofLodTile;
                flat in vec4 ofLodAverage;
                vec4 ofLodFactor = vec4(1.0);
                """);
        // Keep the original varying's rank; use a mutable global for the pack's colour.
        // Unlike a macro, this preserves helper parameters/locals with the same name (BSL).
        source = input(colour).matcher(source).replaceAll("in vec4 ofLodRawColour; vec4 " + colour + ";");
        source = MAIN.matcher(source).replaceAll("void ofLodPackFragment()");
        return source + """
                \nvoid main() {
                    float footprint = max(length(dFdx(ofLodUv) * 16.0), length(dFdy(ofLodUv) * 16.0));
                    float detail = 1.0 - smoothstep(0.75, 3.0, footprint);
                    // Distant subpixel faces use their stable average without an atlas fetch.
                    if (detail > 1.0 / 255.0) {
                        vec2 pixel = vec2(ofLodTile % 16u, ofLodTile / 16u) * 16.0
                            + clamp(fract(ofLodUv) * 16.0, 0.5, 15.5);
                        vec4 texel = textureLod(ofVoxyAtlas, pixel / vec2(256.0, 12288.0), 0.0);
                        // Discard before the pack can replace alpha with 1 for opaque DH terrain.
                        if (texel.a <= 1.0 / 255.0) discard;
                        ofLodFactor.rgb = mix(vec3(1.0), texel.rgb / max(ofLodAverage.rgb, vec3(1.0 / 255.0)), detail);
                        ofLodFactor.a = mix(1.0, clamp(texel.a / max(ofLodAverage.a, 1.0 / 255.0), 0.0, 1.0), detail);
                    }
                    """ + colour + " = ofLodRawColour * ofLodFactor;\n" + """
                    ofLodPackFragment();
                }
                """;
    }
}

#version 150
#moj_import <light.glsl>
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform int FogShape;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;
out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec4 normal;
void main() {
    vec4 eye = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * eye;
    // Unlike entity-local fog_distance(), split AFTER applying region translation.
    // A tunnel and camera both at Y=350 must not acquire 350 blocks of phantom fog.
    vec3 relative = IViewRotMat * eye.xyz;
    vertexDistance = FogShape == 0 ? length(relative) : max(length(relative.xz), abs(relative.y));
    // World pavement joins chunk-baked asphalt: use block diffuse shading,
    // not entity/GUI directional lights which give the same texture another tint.
    float shade = min(Normal.x * Normal.x * 0.6 + Normal.y * Normal.y * (Normal.y < 0.0 ? 0.5 : 1.0) + Normal.z * Normal.z * 0.8, 1.0);
    vertexColor = vec4(Color.rgb * shade, Color.a);
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
    normal = ProjMat * ModelViewMat * vec4(Normal, 0.0);
}

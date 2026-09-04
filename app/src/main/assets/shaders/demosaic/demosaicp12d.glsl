precision highp float;
precision highp sampler2D;
uniform sampler2D RawBuffer;

out float Output;
float dx(ivec2 xy){
return (texelFetch(RawBuffer,xy+ivec2(1,0),0).r - texelFetch(RawBuffer,xy+ivec2(-1,0),0).r);
}

float dy(ivec2 xy){
return (texelFetch(RawBuffer,xy+ivec2(0,1),0).r - texelFetch(RawBuffer,xy+ivec2(0,-1),0).r);
}
float normpdf(in float x, in float sigma)
{
return 0.39894*exp(-0.5*x*x/(sigma*sigma))/sigma;
}

void main()
{
    ivec2 xy = ivec2(gl_FragCoord.xy);
    float central = texelFetch(RawBuffer,xy,0).r;
    Output = central;
    if((xy.x+xy.y)%2 == 1) return;
    //Output = vec4(0.5 + 0.5*cos(uTime+uv.yxy),1.0);
    //Output = texelFetch(RawBuffer,xy,0);
    vec2 hv = vec2(0.0);
    hv = vec2(dx(xy),dy(xy));
    //hv.x = dFdx(texelFetch(RawBuffer,xy+ivec2(-1,0),0).r);
    //hv.y = dFdy(texelFetch(RawBuffer,xy+ivec2(0,-1		),0).r);
    hv = abs(hv);
    hv *= 0.0;
    float sigX = 1.5;
    float sigY = (0.5);
    float Z = 0.0;
    // Channel SNN
    /*
    for(int i=0;i<2;i++){
        for(int j=0;j<2;j++){
            ivec2 pos = ivec2(i*2,j*2);
            ivec2 pos2 = ivec2(-i*2,-j*2);
            float cc1 = texelFetch(RawBuffer, xy+pos, 0).r;
            float cc2 = texelFetch(RawBuffer, xy+pos2, 0).r;
            vec2 hv1 = vec2(dx(xy+pos),dy(xy+pos));
            vec2 hv2 = vec2(dx(xy+pos2),dy(xy+pos2));
            if (length(cc1-central) < length(cc2-central)){
                hv += abs(hv1);
            } else {
                hv += abs(hv2);
            }

        }
    }*/
    for(int i=0;i<8;i++){
        for(int j=0;j<8;j++){
            ivec2 pos = ivec2(i,j);
            ivec2 pos2 = ivec2(-i,-j);
            ivec2 pos3 = ivec2(i,-j);
            ivec2 pos4 = ivec2(-i,j);
            vec3 cc1 = vec3(texelFetch(RawBuffer, xy+pos, 0).rgb);
            vec3 cc2 = vec3(texelFetch(RawBuffer, xy+pos2, 0).rgb);
            vec3 cc3 = vec3(texelFetch(RawBuffer, xy+pos3, 0).rgb);
            vec3 cc4 = vec3(texelFetch(RawBuffer, xy+pos4, 0).rgb);
            // Compute the weights
            float d1 = length(abs(cc1-central));
            float d2 = length(abs(cc2-central));
            float d3 = length(abs(cc3-central));
            float d4 = length(abs(cc4-central));
            float w1 = (1.0-d1*d1/(d1*d1 + sigY));
            float w2 = (1.0-d2*d2/(d2*d2 + sigY));
            float w3 = (1.0-d3*d3/(d3*d3 + sigY));
            float w4 = (1.0-d4*d4/(d4*d4 + sigY));
            float wm = min(min(min(w1,w2),w3),w4);
            w1 -= wm;
            w2 -= wm;
            w3 -= wm;
            w4 -= wm;
            float f1 = normpdf(float(i),sigX)*normpdf(float(j),sigX);
            float factor = 0.0;
            factor += f1*(w1);
            factor += f1*(w2);
            factor += f1*(w3);
            factor += f1*(w4);
            hv += f1*w1*abs(vec2(dx(xy+pos),dy(xy+pos)));
            hv += f1*w2*abs(vec2(dx(xy+pos2),dy(xy+pos2)));
            hv += f1*w3*abs(vec2(dx(xy+pos3),dy(xy+pos3)));
            hv += f1*w4*abs(vec2(dx(xy+pos4),dy(xy+pos4)));
            Z += factor;
        }
    }
    float g = 0.0;

    if (hv.x < hv.y) {
    g = texelFetch(RawBuffer,xy+ivec2(1,0),0).r + texelFetch(RawBuffer,xy+ivec2(-1,0),0).r;
    } else {
    g = texelFetch(RawBuffer,xy+ivec2(0,1),0).r + texelFetch(RawBuffer,xy+ivec2(0,-1),0).r;
    }
    float g1 = texelFetch(RawBuffer,xy+ivec2(1,0),0).r + texelFetch(RawBuffer,xy+ivec2(-1,0),0).r
    +texelFetch(RawBuffer,xy+ivec2(0,1),0).r + texelFetch(RawBuffer,xy+ivec2(0,-1),0).r;
    g1/=4.0;
    g/=2.0;
    float km = clamp(1.0*(hv.x-hv.y),0.0,1.0);
    //g = mix(g,g1,km);
    Output = g;
    //Output = vec4(abs(hv.x-hv.y));
    //Output*=0.0;
}

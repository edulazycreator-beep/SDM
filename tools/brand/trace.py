import numpy as np, potrace
from PIL import Image
im=np.asarray(Image.open('src.png').convert('RGB')).astype(float)
H,W,_=im.shape
ys,xs=np.mgrid[0:H,0:W]
inner=(xs>40)&(xs<960)&(ys>40)&(ys<960)
bgm=inner&(im[...,0]<110)
A=np.stack([np.ones(bgm.sum()),xs[bgm],ys[bgm],xs[bgm]*ys[bgm]],1)
coef=np.linalg.lstsq(A,im[...,1][bgm],rcond=None)[0]
bgG=coef[0]+coef[1]*xs+coef[2]*ys+coef[3]*xs*ys
alpha=np.clip((im[...,1]-bgG)/(242-bgG),0,1)*inner
# supersample: upscale alpha 2x for smoother trace
a=Image.fromarray((alpha*255).astype(np.uint8)).resize((2000,2000),Image.LANCZOS)
a.save('alpha.png')
bm=np.asarray(a)>127
def trace(mask, name):
    p=potrace.Bitmap(mask).trace(turdsize=8, alphamax=1.0, opticurve=True, opttolerance=0.2)
    out=[]
    for c in p:
        d=f"M{c.start_point.x/2:.1f},{c.start_point.y/2:.1f}"
        for s in c.segments:
            if s.is_corner: d+=f"L{s.c.x/2:.1f},{s.c.y/2:.1f}L{s.end_point.x/2:.1f},{s.end_point.y/2:.1f}"
            else: d+=f"C{s.c1.x/2:.1f},{s.c1.y/2:.1f} {s.c2.x/2:.1f},{s.c2.y/2:.1f} {s.end_point.x/2:.1f},{s.end_point.y/2:.1f}"
        d+="Z"
        pts=[c.start_point]+[s.end_point for s in c.segments]
        bx=(min(q.x for q in pts)/2,min(q.y for q in pts)/2,max(q.x for q in pts)/2,max(q.y for q in pts)/2)
        out.append((d,bx))
    print(name,len(out)); [print(' ',[round(v) for v in b]) for _,b in out]
    return out
mark=bm.copy(); mark[1170:,:]=False
sub=bm.copy(); sub[:1170,:]=False
import json
json.dump({'mark':trace(mark,'mark'),'sub':trace(sub,'sub')},open('paths.json','w'))

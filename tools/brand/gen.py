import json, cairosvg
P=json.load(open('paths.json'))
mark=[d for d,b in P['mark'] if b[2]-b[0]<900 and b[2]-b[0]>100]
arrow=[d for d,b in P['mark'] if b[2]-b[0]<100][0]
sub=[d for d,b in P['sub'] if b[2]-b[0]<900]
MARK=' '.join(mark); SUB=' '.join(sub)
R='/home/claude/sdm/app/src/main/res/drawable/'
HDR='<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated from the SDM logo (docs/brand/sdm-logo.png); see docs/brand/README.md. -->\n'
NS='xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"'
def grad_text(y1,y2):
    return f'''
            <aapt:attr name="android:fillColor">
                <gradient android:type="linear" android:startX="0" android:startY="{y1}" android:endX="0" android:endY="{y2}" android:startColor="#FFFFFFFF" android:endColor="#FFDCE8FC" />
            </aapt:attr>'''
def path(d, name=None, fill=None, grad=None, alpha=None):
    n=f' android:name="{name}"' if name else ''
    a=f' android:fillAlpha="{alpha}"' if alpha is not None else ''
    if grad: return f'        <path{n} android:fillType="evenOdd"{a} android:pathData="{d}">{grad}\n        </path>\n'
    return f'        <path{n} android:fillType="evenOdd"{a} android:fillColor="{fill}" android:pathData="{d}" />\n'
cx,cy=504,460
# launcher foreground / monochrome (108dp)
s=66/746; tx=54-cx*s; ty=54-cy*s
def launcher(fill, grad):
    return HDR+f'''<vector {NS}
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="{tx:.3f}" android:translateY="{ty:.3f}" android:scaleX="{s:.5f}" android:scaleY="{s:.5f}">
{path(MARK+' '+arrow, fill=fill, grad=grad)}    </group>
</vector>
'''
open(R+'ic_launcher_foreground.xml','w').write(launcher(None, grad_text(352,567)))
open(R+'ic_launcher_monochrome.xml','w').write(launcher('#FF000000', None))
open(R+'ic_launcher_background.xml','w').write(HDR+f'''<vector {NS}
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="108" android:startY="0" android:endX="0" android:endY="108">
                <item android:offset="0" android:color="#FF3A60A3" />
                <item android:offset="0.55" android:color="#FF2F5292" />
                <item android:offset="1" android:color="#FF10264D" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
''')
# animated splash icon (288dp, content inside the 192dp circle)
s2=170/746; tx2=144-cx*s2; ty2=144-cy*s2
open(R+'ic_splash.xml','w').write(HDR+f'''<animated-vector {NS}>
    <aapt:attr name="android:drawable">
        <vector android:width="288dp" android:height="288dp" android:viewportWidth="288" android:viewportHeight="288">
            <group android:translateX="{tx2:.3f}" android:translateY="{ty2:.3f}" android:scaleX="{s2:.5f}" android:scaleY="{s2:.5f}">
                <group android:name="mark" android:pivotX="{cx}" android:pivotY="{cy}">
        {path(MARK, name='markPath', fill='#FFFFFFFF').strip()}
                </group>
                <group android:name="arrow">
        {path(arrow, name='arrowPath', fill='#FFFFFFFF').strip()}
                </group>
            </group>
        </vector>
    </aapt:attr>
    <target android:name="mark">
        <aapt:attr name="android:animation">
            <set>
                <objectAnimator android:propertyName="scaleX" android:valueFrom="0.72" android:valueTo="1" android:duration="520" android:interpolator="@android:interpolator/overshoot" />
                <objectAnimator android:propertyName="scaleY" android:valueFrom="0.72" android:valueTo="1" android:duration="520" android:interpolator="@android:interpolator/overshoot" />
            </set>
        </aapt:attr>
    </target>
    <target android:name="markPath">
        <aapt:attr name="android:animation">
            <objectAnimator android:propertyName="fillAlpha" android:valueFrom="0" android:valueTo="1" android:duration="320" android:interpolator="@android:interpolator/fast_out_slow_in" />
        </aapt:attr>
    </target>
    <target android:name="arrow">
        <aapt:attr name="android:animation">
            <objectAnimator android:propertyName="translateY" android:valueFrom="-170" android:valueTo="0" android:startOffset="260" android:duration="560" android:interpolator="@android:interpolator/bounce" />
        </aapt:attr>
    </target>
    <target android:name="arrowPath">
        <aapt:attr name="android:animation">
            <objectAnimator android:propertyName="fillAlpha" android:valueFrom="0" android:valueTo="1" android:startOffset="260" android:duration="200" />
        </aapt:attr>
    </target>
</animated-vector>
''')
# full logo for onboarding / premium: body (mark+subtitle) and arrow, same viewport so they overlay
X0,Y0,X1,Y1=118,345,888,655
def full(content):
    return HDR+f'''<vector {NS}
    android:width="{(X1-X0)/4:.0f}dp" android:height="{(Y1-Y0)/4:.0f}dp" android:viewportWidth="{X1-X0}" android:viewportHeight="{Y1-Y0}">
    <group android:translateX="{-X0}" android:translateY="{-Y0}">
{content}    </group>
</vector>
'''
open(R+'logo_body.xml','w').write(full(path(MARK+' '+SUB, fill='#FFFFFFFF')))
open(R+'logo_arrow.xml','w').write(full(path(arrow, fill='#FFFFFFFF')))
# store icon 512 + feature graphic preview
def svg(w,h,content,gx=(1,0,0,1)):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">
<defs><linearGradient id="g" x1="1" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#3A60A3"/><stop offset="0.55" stop-color="#2F5292"/><stop offset="1" stop-color="#10264D"/></linearGradient>
<linearGradient id="t" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#DCE8FC"/></linearGradient></defs>
<rect width="{w}" height="{h}" fill="url(#g)"/>{content}</svg>'''
import os; os.makedirs('/home/claude/sdm/docs/brand',exist_ok=True)
k=512/1000
icon=svg(512,512,f'<g transform="scale({k})"><path d="{MARK} {arrow} {SUB}" fill="url(#t)" fill-rule="evenodd"/></g>')
cairosvg.svg2png(bytestring=icon.encode(), write_to='/home/claude/sdm/docs/brand/play-icon-512.png')
fg=svg(1024,500,f'<g transform="translate({512-503*1.05:.1f},{250-500*1.05:.1f}) scale(1.05)"><path d="{MARK} {arrow} {SUB}" fill="url(#t)" fill-rule="evenodd"/></g>')
cairosvg.svg2png(bytestring=fg.encode(), write_to='/home/claude/sdm/docs/brand/play-feature-1024x500.png')
# preview of launcher masks
lp=svg(108,108,f'<g transform="translate({tx},{ty}) scale({s})"><path d="{MARK} {arrow}" fill="url(#t)" fill-rule="evenodd"/></g>')
cairosvg.svg2png(bytestring=lp.encode(), write_to='launcher_preview.png', output_width=432, output_height=432)

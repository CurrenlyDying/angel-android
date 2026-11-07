#!/usr/bin/env python3
"""Build editable Angel artwork and Android assets from native SVG geometry.

Run with python scripts/build_assets.py using the bundled requirements.
The SVG masters contain editable text. Portable SVG/PNG exports use font outlines.
"""
from pathlib import Path
from io import BytesIO
import base64
import hashlib
import html
import json
import math
import xml.etree.ElementTree as ET
import zipfile

import cairosvg
import uharfbuzz as hb
from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen

ROOT = Path(__file__).resolve().parents[1]
NS = 'http://www.w3.org/2000/svg'
INK = 'http://www.inkscape.org/namespaces/inkscape'
ET.register_namespace('',NS)
ET.register_namespace('inkscape',INK)
ET.register_namespace('xlink','http://www.w3.org/1999/xlink')
COL = dict(night='#071F2C', petrol='#0E4256', ocean='#103AC7', rose='#D996B7', moon='#F3EBDD', teal='#36889F')
FONT_FILES = {
    'display': ROOT/'fonts/bodonimoda/BodoniModa[opsz,wght].ttf',
    'italic': ROOT/'fonts/bodonimoda/BodoniModa-Italic[opsz,wght].ttf',
    'mono': ROOT/'fonts/ibmplexmono/IBMPlexMono-Regular.ttf',
}
FONT_FAMILIES = {'display':'Bodoni Moda', 'italic':'Bodoni Moda', 'numeral':'IBM Plex Mono', 'mono':'IBM Plex Mono'}


class Type:
    def __init__(self, path, axes=None):
        data = path.read_bytes()
        self.face = hb.Face(data)
        self.hb = hb.Font(self.face)
        self.hb.scale = (self.face.upem, self.face.upem)
        font = TTFont(path)
        if 'fvar' in font:
            axes = axes or {'wght':400, 'opsz':96}
            self.hb.set_variations(axes)
            font = instantiateVariableFont(font, axes, inplace=False)
        self.font = font
        self.glyphs = font.getGlyphSet()
        self.order = font.getGlyphOrder()

    def shape(self, value):
        buf = hb.Buffer()
        buf.add_str(value)
        buf.guess_segment_properties()
        hb.shape(self.hb, buf)
        return list(zip(buf.glyph_infos, buf.glyph_positions))

    def width(self, value, size, tracking=0):
        shaped = self.shape(value)
        return sum(pos.x_advance for _, pos in shaped)*size/self.face.upem + max(0,len(shaped)-1)*tracking

    def paths(self, value, x, y, size, tracking=0):
        output = []
        scale = size / self.face.upem
        cursor = x
        for info, pos in self.shape(value):
            pen = SVGPathPen(self.glyphs)
            self.glyphs[self.order[info.codepoint]].draw(pen)
            d = pen.getCommands()
            if d:
                output.append(f'<path transform="translate({cursor+pos.x_offset*scale:.4f},{y-pos.y_offset*scale:.4f}) scale({scale:.6f},-{scale:.6f})" d="{d}"/>')
            cursor += pos.x_advance*scale + tracking
        return ''.join(output)


TYPES = {k:Type(p) for k,p in FONT_FILES.items()}
TYPES['numeral'] = Type(ROOT/'fonts/ibmplexmono/IBMPlexMono-Medium.ttf')


def text(value, x, y, size, family='mono', fill=None, tracking=0, editable=False, extra=''):
    fill = fill or COL['moon']
    common = f'fill="{fill}" {extra}'
    if editable:
        italic = 'font-style="italic"' if family=='italic' else ''
        axes = '&quot;opsz&quot; 96, &quot;wght&quot; 400'
        weight = 'font-weight="500"' if family=='numeral' else ''
        return f'<text x="{x}" y="{y}" font-family="{FONT_FAMILIES[family]}" font-size="{size}" letter-spacing="{tracking}" {italic} {weight} style="font-variation-settings: {axes}" {common}>{html.escape(value)}</text>'
    return f'<g {common} aria-label="{html.escape(value)}">{TYPES[family].paths(value,x,y,size,tracking)}</g>'


def layer(name, body):
    # Inkscape layer names are also useful as ordinary SVG group IDs in other editors.
    return f'<g id="{name}" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" inkscape:groupmode="layer" inkscape:label="{name}">{body}</g>'


def document(width, height, body, defs='', title='Angel — editable artwork'):
    return f'''<svg xmlns="{NS}" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
<title>{html.escape(title)}</title><desc>Original Angel identity. Round lunar image: NASA Scientific Visualization Studio / Ernie Wright (USRA). Geometry, typography, ocean and three numeral particles are separate editable layers.</desc>
<defs>{defs}</defs>{body}</svg>'''


def moon_uri():
    # Container conversion only. NASA's existing transparent Moon is used as supplied.
    target=ROOT/'references/nasa-full-moon.png'
    if not target.exists():
        image = Image.open(ROOT/'references/nasa-full-moon.tif').convert('RGBA')
        image.save(target)
    # Shared linked image keeps each editable master small. The entire source
    # folder and references/ image travel together in the distributable bundle.
    return '../references/nasa-full-moon.png'


MOON = moon_uri()
MOON_DATA='data:image/png;base64,'+base64.b64encode((ROOT/'references/nasa-full-moon.png').read_bytes()).decode()


def render_source(svg):
    # CairoSVG intentionally blocks external image reads by default. Embed only
    # this known local image in memory for export; editable files remain linked.
    return svg.replace(MOON,MOON_DATA)


def hero(aspect='square', wording='SYNTHESIS', editable=False, typography=True, particles=True):
    specs = {
        'square':dict(w=1200,h=1200,cx=934,cy=215,r=171,x=90,top=383,base=650,bottom=785,word_width=820,sea=919,pts=[(1100,458,68,12),(1073,652,75,-13),(918,823,68,10)]),
        'portrait':dict(w=1080,h=1920,cx=783,cy=340,r=184,x=85,top=690,base=965,bottom=1125,word_width=780,sea=1322,pts=[(943,620,72,12),(949,1136,82,-13),(805,1263,72,10)]),
        'wide':dict(w=2400,h=1172,cx=1928,cy=263,r=211,x=160,top=375,base=720,bottom=858,word_width=1410,sea=920,pts=[(2220,455,90,12),(2155,655,100,-13),(2000,818,90,10)]),
    }
    s=specs[aspect];w,h=s['w'],s['h'];sea=s['sea'];cx,cy,r=s['cx'],s['cy'],s['r']
    defs=f'''<linearGradient id="sky" x2="0.75" y2="1"><stop stop-color="#041B29"/><stop offset=".55" stop-color="#0B354A"/><stop offset="1" stop-color="#125279"/></linearGradient>
<linearGradient id="water" x2=".25" y2="1"><stop stop-color="#092274"/><stop offset=".55" stop-color="#08288E"/><stop offset="1" stop-color="#031442"/></linearGradient>
<radialGradient id="lunar-glow"><stop stop-color="#F6EBD5" stop-opacity=".18"/><stop offset=".66" stop-color="#F6EBD5" stop-opacity=".07"/><stop offset="1" stop-color="#F6EBD5" stop-opacity="0"/></radialGradient>
<radialGradient id="reflection"><stop stop-color="#F3EBDD" stop-opacity=".64"/><stop offset=".2" stop-color="#C4BCBA" stop-opacity=".22"/><stop offset="1" stop-color="#F3EBDD" stop-opacity="0"/></radialGradient>
<linearGradient id="seven-front" x2=".65" y2="1"><stop stop-color="#FFF0E6"/><stop offset=".45" stop-color="#E6B5CA"/><stop offset="1" stop-color="#B16D99"/></linearGradient>
<linearGradient id="facet" x2="1" y2="1"><stop stop-color="#4A8BA1" stop-opacity=".12"/><stop offset="1" stop-color="#0C2A41" stop-opacity=".02"/></linearGradient>'''
    body=layer('01-night-sky',f'<rect width="{w}" height="{h}" fill="url(#sky)"/>')
    body+=layer('02-sky-geometry',f'<path d="M0 {h*.23} L{w*.40} {h*.03} L{w*.18} {h*.45} Z M{w*.2} {h*.18} L{w*.74} {h*.60} L{w*.48} {h*.73} Z" fill="url(#facet)"/>')
    image_size=r*2/.857
    body+=layer('03-round-moon',f'<circle cx="{cx}" cy="{cy}" r="{r*1.52}" fill="url(#lunar-glow)"/><image x="{cx-image_size/2}" y="{cy-image_size/2}" width="{image_size}" height="{image_size}" xlink:href="{MOON}"/>')
    endx=w*.69
    orbit=f'M {cx+r*.65} {cy+r*.55} C {w*.995} {h*.40}, {w*.99} {sea*.89}, {endx} {sea+4}'
    body+=layer('04-lunar-orbit',f'<path d="{orbit}" stroke="#EFE4D5" stroke-opacity=".30" stroke-width="1.1" fill="none"/>')
    ocean=f'<rect y="{sea}" width="{w}" height="{h-sea}" fill="url(#water)"/><ellipse cx="{endx}" cy="{sea+10}" rx="{w*.16}" ry="{h*.055}" fill="url(#reflection)"/>'
    depth=h-sea
    for index in range(8):
        y=sea+depth*(.08+index*.115)
        amp=depth*(.025+index*.007)
        shift=(index%3-1)*w*.14
        ocean+=f'<path d="M {-w*.1} {y} Q {w*.22+shift} {y-amp} {w*.49} {y+amp*.6} T {w*1.1} {y-amp*.25} L {w*1.1} {y+depth*.14} Q {w*.62} {y+amp*.8} {w*.41} {y+depth*.12} T {-w*.1} {y+depth*.16} Z" fill="{["#0C2F9C","#1239AE","#062069","#1343B5"][index%4]}" fill-opacity=".64"/>'
        ocean+=f'<path d="M {-w*.1} {y} Q {w*.22+shift} {y-amp} {w*.49} {y+amp*.6} T {w*1.1} {y-amp*.25}" stroke="#7298E8" stroke-opacity="{.18+index*.025}" stroke-width="{.7+index*.11}" fill="none"/>'
    for index in range(10):
        y=sea+7+depth*(index/10)**1.32
        width=w*(.022+.08*(index/10))
        x=endx+math.sin(index*1.9)*w*.012
        ocean+=f'<path d="M{x-width/2:.2f},{y:.2f} q{width*.5:.2f},-2 {width:.2f},0" stroke="#F3E6D4" stroke-opacity="{.75-index*.05}" stroke-width="{.8+index*.13}" fill="none"/>'
    body+=layer('05-ocean-and-reflections',ocean)
    if particles:
        for i,(x,y,size,angle) in enumerate(s['pts'],1):
            size*=1.18
            item=f'<g transform="translate({x},{y}) rotate({angle}) skewX(-6)">'
            item+=text('7',-size*.3+3,3,size,'numeral','#774B6A',editable=editable)
            item+=text('7',-size*.3+1,1,size,'numeral','#F3C6D5',editable=editable)
            item+=text('7',-size*.3,0,size,'numeral','url(#seven-front)',editable=editable)
            item+='</g>'
            body+=layer(f'06-particle-7-{i}',item)
    if typography:
        x=s['x'];pad=83 if aspect!='wide' else 110
        size=s['word_width']/TYPES['display'].width('ANGEL',1)
        label_size=32 if aspect!='wide' else 38
        body+=layer('07-type-cruel',text('>CRUEL',x,s['top'],label_size,'mono',tracking=6,editable=editable))
        by=s['base'];left=x+pad
        chev=f'<path d="M{x} {by-size*.65} L{x+pad*.72} {by-size*.40} L{x} {by-size*.15}" stroke="{COL["rose"]}" stroke-width="{size*.043}" fill="none" stroke-linejoin="miter"/>'
        body+=layer('08-type-angel',chev+text('ANGEL',left,by,size,'display',COL['rose'],editable=editable))
        body+=layer('09-type-ending',text('>'+wording,x,s['bottom'],label_size,'mono',tracking=4.5,editable=editable))
    return document(w,h,body,defs,'Angel — '+wording.lower()+' / '+aspect)


def mark_paths(variant='prism', mono=False, small=False):
    colors=['#FFFFFF']*3 if mono else [COL['rose'],COL['moon'],('#61A4B9' if small else COL['teal'])]
    if variant=='prism':
        paths=['M54 24 L30 54 L54 84 L47 64 L61 55 L47 47 Z','M54 24 L47 47 L78 54 Z','M47 64 L54 84 L78 54 Z']
        silhouette='M54 24 L30 54 L54 84 L78 54 Z M47 47 L61 55 L47 64 L78 54 Z'
        if mono:
            paths=[silhouette];colors=['#FFFFFF']
        else:
            paths=[silhouette]+paths;colors=[COL['rose']]+colors
    elif variant=='lunar-gate':
        paths=['M73 28 A30 30 0 1 0 75 79 A24 24 0 1 1 73 28 Z','M46 36 L71 54 L46 72 L46 62 L57 54 L46 46 Z','M76 38 L81 43 L76 48 L71 43 Z']
    else:
        paths=['M29 31 L55 52 L29 73 L29 61 L40 52 L29 43 Z','M45 25 L75 49 L75 57 L45 81 L45 68 L65 53 L45 38 Z','M66 27 L80 39 L80 51 L66 39 Z']
    return ''.join(f'<path fill="{color}" fill-rule="evenodd" d="{path}"/>' for path,color in zip(paths,colors)),paths


def icon_svg(variant='prism', mono=False, adaptive=False, small=False, background=True):
    paths,_=mark_paths(variant,mono,small)
    view='0 0 108 108' if adaptive else '18 18 72 72'
    bg='<rect width="108" height="108" fill="#071F2C"/>' if background else ''
    return f'<svg xmlns="{NS}" width="108" height="108" viewBox="{view}"><title>Angel {variant} mark</title>{bg}<g id="editable-mark-facets">{paths}</g></svg>'


def vector_xml(paths, colors, viewport=108, size=108):
    children=''.join(f'<path android:fillType="evenOdd" android:fillColor="{color}" android:pathData="{path}"/>' for path,color in zip(paths,colors))
    return f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{size}dp" android:height="{size}dp" android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">{children}</vector>'


def capability_icons():
    shapes={
        'models':['M6 6H18V18H6Z','M9 3V6 M15 3V6 M9 18V21 M15 18V21 M3 9H6 M3 15H6 M18 9H21 M18 15H21','M12 9L15 12L12 15L9 12Z'],
        'terminal':['M3 5H21V19H3Z','M7 9L10 12L7 15','M13 15H17'],
        'projects':['M3 5H10L12 8H21V20H3Z','M12 12L15 14L12 16'],
        'connectors':['M6 7V10L12 14L18 10V7 M12 14V17','M8 5A2 2 0 1 1 4 5A2 2 0 1 1 8 5Z','M20 5A2 2 0 1 1 16 5A2 2 0 1 1 20 5Z','M14 19A2 2 0 1 1 10 19A2 2 0 1 1 14 19Z'],
        'skills':['M4 5H14V9H4Z M7 10H17V14H7Z M10 15H20V19H10Z'],
        'reverse':['M6 6L2 12L6 18','M18 6L22 12L18 18','M12 8L16 12L12 16L8 12Z'],
    }
    folder=ROOT/'icons/capabilities';folder.mkdir(exist_ok=True)
    for name,paths in shapes.items():
        body=''.join(f'<path d="{p}"/>' for p in paths)
        svg=f'<svg xmlns="{NS}" width="24" height="24" viewBox="0 0 24 24"><title>Angel {name}</title><g fill="none" stroke="#F3EBDD" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">{body}</g></svg>'
        (folder/f'{name}.svg').write_text(svg)
        render(svg,folder/f'{name}-96.png',96,96)
        xml='<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">'+''.join(f'<path android:fillColor="#00000000" android:strokeColor="#FFFFFF" android:strokeWidth="1.8" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{p}"/>' for p in paths)+'</vector>'
        (ROOT/'android/res/drawable'/f'ic_angel_{name}.xml').write_text(xml)


def particle_sprites():
    defs='<linearGradient id="seven-front" x2=".65" y2="1"><stop stop-color="#FFF0E6"/><stop offset=".45" stop-color="#E6B5CA"/><stop offset="1" stop-color="#B16D99"/></linearGradient>'
    folder=ROOT/'source/particles';folder.mkdir(exist_ok=True)
    export=ROOT/'exports/particles';export.mkdir(exist_ok=True)
    for index,angle in enumerate([12,-13,10],1):
        for editable in [True,False]:
            body=f'<g id="particle-7-{index}" transform="translate(80,144) rotate({angle}) skewX(-6)">'
            body+=text('7',-38+4,4,132,'numeral','#774B6A',editable=editable)
            body+=text('7',-38+1,1,132,'numeral','#F3C6D5',editable=editable)
            body+=text('7',-38,0,132,'numeral','url(#seven-front)',editable=editable)+'</g>'
            svg=document(192,192,body,defs,f'Angel numeral particle {index}')
            path=folder/f'seven-{index}-{"editable" if editable else "outlined"}.svg'
            path.write_text(svg)
            if not editable:render(svg,export/f'seven-{index}.png',384,384)


def wordmarks():
    folder=ROOT/'source/wordmarks';folder.mkdir(exist_ok=True)
    exports=ROOT/'exports/wordmarks';exports.mkdir(exist_ok=True)
    for ending in ['SYNTHESIS','INFERENCE','THESIS']:
        for editable in [True,False]:
            size=850/TYPES['display'].width('ANGEL',1)
            body=text('>CRUEL',40,74,30,tracking=6,editable=editable)
            body+=f'<path d="M40 {360-size*.65} L105 {360-size*.40} L40 {360-size*.15}" fill="none" stroke="{COL["rose"]}" stroke-width="10"/>'
            body+=text('ANGEL',132,360,size,'display',COL['rose'],editable=editable)
            body+=text('>'+ending,40,493,30,tracking=4.5,editable=editable)
            svg=document(1050,540,body,title='Cruel Angel '+ending.lower())
            (folder/f'{ending.lower()}-{"editable" if editable else "outlined"}.svg').write_text(svg)
            if not editable:render(svg,exports/f'{ending.lower()}.png',1050,540)


def openraster_master():
    source=hero()
    tree=ET.fromstring(source)
    defs=next(child for child in tree if child.tag==f'{{{NS}}}defs')
    groups=[child for child in tree if child.tag==f'{{{NS}}}g']
    image=ET.Element('image',{'version':'0.0.3','w':'1200','h':'1200','name':'ANGEL / Cruel Angel Synthesis'})
    stack=ET.SubElement(image,'stack')
    layers=[]
    for index,group in enumerate(groups):
        svg=document(1200,1200,ET.tostring(group,encoding='unicode'),ET.tostring(defs,encoding='unicode').split('>',1)[1].rsplit('</',1)[0])
        png=cairosvg.svg2png(bytestring=render_source(svg).encode(),url=str(ROOT/'source/render-base.svg'))
        layers.append((group.attrib['id'],f'data/layer-{index:02}.png',png))
    with zipfile.ZipFile(ROOT/'source/angel-square-layered.ora','w') as out:
        out.writestr('mimetype','image/openraster',compress_type=zipfile.ZIP_STORED)
        for name,path,data in reversed(layers):
            ET.SubElement(stack,'layer',{'name':name,'src':path,'x':'0','y':'0','opacity':'1.0','visibility':'visible','composite-op':'svg:src-over'})
            out.writestr(path,data,compress_type=zipfile.ZIP_STORED)
        out.writestr('stack.xml',ET.tostring(image,encoding='utf-8',xml_declaration=True))
        out.writestr('mergedimage.png',(ROOT/'exports/angel-square.png').read_bytes())
        thumb=Image.open(ROOT/'exports/angel-square.png');thumb.thumbnail((256,256));buf=BytesIO();thumb.save(buf,format='PNG')
        out.writestr('Thumbnails/thumbnail.png',buf.getvalue())


def render(svg,path,width=None,height=None):
    cairosvg.svg2png(bytestring=render_source(svg).encode(),url=str(ROOT/'source/render-base.svg'),write_to=str(path),output_width=width,output_height=height)


def main():
    for name in ['source','exports','icons','android/res/drawable','android/res/mipmap-anydpi-v26','review']:(ROOT/name).mkdir(parents=True,exist_ok=True)
    for aspect in ['square','portrait','wide']:
        for editable in [True,False]:
            svg=hero(aspect,editable=editable)
            name=f'angel-{aspect}-'+('editable' if editable else 'outlined')+'.svg'
            (ROOT/'source'/name).write_text(svg)
            if not editable:
                width={'square':1200,'portrait':1080,'wide':2048}[aspect]
                render(svg,ROOT/'exports'/f'angel-{aspect}.png',width)
    for wording in ['INFERENCE','THESIS']:
        svg=hero(wording=wording)
        (ROOT/'source'/f'angel-square-{wording.lower()}-outlined.svg').write_text(svg)
        (ROOT/'source'/f'angel-square-{wording.lower()}-editable.svg').write_text(hero(wording=wording,editable=True))
        render(svg,ROOT/'exports'/f'angel-square-{wording.lower()}.png',1200)
    blank=hero(typography=False,particles=False)
    (ROOT/'source/angel-ocean-background.svg').write_text(blank)
    render(blank,ROOT/'exports/angel-ocean-background.png',1200)
    render(hero('wide'),ROOT/'exports/angel-feature-graphic-1024x500.png',1024,500)
    for variant in ['prism','lunar-gate','threefold']:
        (ROOT/'icons'/f'angel-{variant}.svg').write_text(icon_svg(variant))
        for size in [16,24,32,48,64,96,192,512]:
            render(icon_svg(variant,small=size<=32),ROOT/'icons'/f'angel-{variant}-{size}.png',size,size)
    (ROOT/'icons/angel-prism-monochrome.svg').write_text(icon_svg(mono=True,background=False))
    (ROOT/'icons/angel-prism-monochrome-ink.svg').write_text(icon_svg(mono=True,background=False).replace('#FFFFFF',COL['night']))
    (ROOT/'icons/angel-prism-foreground.svg').write_text(icon_svg(adaptive=True,background=False))
    for size in [16,24,32,48]:
        (ROOT/'icons'/f'angel-prism-optical-{size}.svg').write_text(icon_svg(small=size<=32))
    _,paths=mark_paths()
    _,mono_paths=mark_paths(mono=True)
    (ROOT/'android/res/drawable/ic_angel_foreground.xml').write_text(vector_xml(paths,[COL['rose'],COL['rose'],COL['moon'],COL['teal']]))
    (ROOT/'android/res/drawable/ic_angel_monochrome.xml').write_text(vector_xml(mono_paths,['#FFFFFF']))
    # Compact app/header/service version has a 24dp viewport and no background.
    compact='<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="72" android:viewportHeight="72"><group android:translateX="-18" android:translateY="-18">'+''.join(f'<path android:fillType="evenOdd" android:fillColor="#FFFFFF" android:pathData="{p}"/>' for p in mono_paths)+'</group></vector>'
    (ROOT/'android/res/drawable/ic_angel_mark.xml').write_text(compact)
    (ROOT/'android/res/drawable/ic_angel_background.xml').write_text('<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle"><solid android:color="#071F2C"/></shape>')
    adaptive='<?xml version="1.0" encoding="utf-8"?><adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/ic_angel_background"/><foreground android:drawable="@drawable/ic_angel_foreground"/><monochrome android:drawable="@drawable/ic_angel_monochrome"/></adaptive-icon>'
    for name in ['ic_launcher.xml','ic_launcher_round.xml']:(ROOT/'android/res/mipmap-anydpi-v26'/name).write_text(adaptive)
    for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
        folder=ROOT/'android/res'/f'mipmap-{density}';folder.mkdir(exist_ok=True)
        png=cairosvg.svg2png(bytestring=icon_svg().encode(),output_width=size,output_height=size)
        im=Image.open(BytesIO(png)).convert('RGBA');im.save(folder/'ic_launcher.webp',format='WEBP',lossless=True)
        # Legacy round resource is an actual circle; adaptive layers stay unmasked.
        mask=Image.new('L',(size*4,size*4));ImageDraw.Draw(mask).ellipse((0,0,size*4-1,size*4-1),fill=255)
        im.putalpha(mask.resize((size,size),Image.Resampling.LANCZOS));im.save(folder/'ic_launcher_round.webp',format='WEBP',lossless=True)
    capability_icons()
    particle_sprites()
    wordmarks()
    openraster_master()
    build_review()
    records=[]
    for p in sorted(ROOT.rglob('*')):
        if p.is_file() and p.suffix in ['.svg','.png','.webp','.xml','.ora'] and p.relative_to(ROOT).parts[0]!='references':
            records.append({'file':str(p.relative_to(ROOT)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
    (ROOT/'asset-manifest.json').write_text(json.dumps(records,indent=2)+'\n')
    print('Built',len(records),'artwork/icon/resource files at',ROOT)


def build_review():
    font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',17)
    small=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',12)
    sheet=Image.new('RGB',(960,510),'#e9e8e4');d=ImageDraw.Draw(sheet)
    d.text((24,18),'ANGEL / editable mark comparison',font=font,fill=COL['night'])
    for i,(variant,label) in enumerate([('prism','PRISM GATE'),('lunar-gate','LUNAR GATE'),('threefold','THREEFOLD')]):
        x=24+i*316
        d.text((x,60),label,font=font,fill=COL['night'])
        im=Image.open(ROOT/'icons'/f'angel-{variant}-192.png').convert('RGB');sheet.paste(im,(x,95))
        for j,size in enumerate([16,24,32,48,64]):
            sx=x+j*55;icon=Image.open(ROOT/'icons'/f'angel-{variant}-{size}.png').convert('RGB')
            sheet.paste(icon,(sx,322+(64-size)//2));d.text((sx,399),str(size)+'px',font=small,fill=COL['night'])
        d.text((x,449),'Native sizes / vector-authored',font=small,fill='#526071')
    sheet.save(ROOT/'review/mark-comparison.png')
    masks=Image.new('RGB',(1020,350),'#e9e8e4');d=ImageDraw.Draw(masks)
    d.text((24,18),'ANGEL / Android adaptive mask and themed previews',font=font,fill=COL['night'])
    # The visible mask is 72dp inside the 108dp layer. A conservative 66dp
    # circular boundary is drawn in the construction preview.
    raw=Image.open(BytesIO(cairosvg.svg2png(bytestring=icon_svg(adaptive=True).encode(),output_width=324,output_height=324))).convert('RGBA')
    for i,kind in enumerate(['circle','squircle','rounded','themed']):
        x=24+i*246
        visible=raw.crop((54,54,270,270))
        if kind=='themed':
            svg=icon_svg(mono=True,adaptive=True).replace('#071F2C','#E8DCEF').replace('#FFFFFF','#432652')
            visible=Image.open(BytesIO(cairosvg.svg2png(bytestring=svg.encode(),output_width=324,output_height=324))).convert('RGBA').crop((54,54,270,270))
        alpha=Image.new('L',(216,216));a=ImageDraw.Draw(alpha)
        if kind in ['circle','themed']:a.ellipse((0,0,215,215),fill=255)
        else:a.rounded_rectangle((0,0,215,215),radius=65 if kind=='squircle' else 40,fill=255)
        masks.paste(visible,(x,70),alpha);d.text((x,305),kind,font=font,fill=COL['night'])
    masks.save(ROOT/'review/adaptive-masks.png')


if __name__=='__main__':main()

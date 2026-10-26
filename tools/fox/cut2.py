import os, json, numpy as np
from PIL import Image
from scipy import ndimage as nd
SRC='/home/claude/fox/src'; OUT='/home/claude/fox/out'; os.makedirs(OUT,exist_ok=True)
def tones(a):
  # dominant neutral tones of the sheet border area = checker colors
  r,g,b=[a[...,i].astype(int) for i in range(3)]
  mx=np.maximum(np.maximum(r,g),b); mn=np.minimum(np.minimum(r,g),b)
  m=np.zeros(mx.shape,bool); m[:25]=m[-25:]=True; m[:,:25]=m[:,-25:]=True
  gr=(g>120)&(g-np.maximum(r,b)>60)
  if (gr&m).sum()>m.sum()*.3: return []
  v=mx[m&(mx-mn<10)]
  if len(v)<500: return []
  h=np.bincount(v//4,minlength=70)
  pk=[]
  for i in np.argsort(h)[::-1]:
    if h[i]<len(v)*.03: break
    if all(abs(i-p)>2 for p in pk): pk.append(i)
    if len(pk)==4: break
  return [p*4+2 for p in pk if 70<p*4+2]
TONES=[]
def cell_alpha(c):
  r,g,b=[c[...,i].astype(int) for i in range(3)]
  mx=np.maximum(np.maximum(r,g),b); mn=np.minimum(np.minimum(r,g),b)
  green=(g>120)&(g-np.maximum(r,b)>60)
  if TONES:
    lo,hi=min(TONES)-16,max(TONES)+16
    neutral=(mx-mn<12)&(mx>=lo)&(mx<=hi)
  else:
    neutral=np.zeros_like(green)
  dark=mx<(min(85,min(TONES)-25) if TONES else 85)
  barrier=nd.binary_dilation(dark&~np.zeros_like(dark),iterations=1)
  H,W=mx.shape
  cand=green|neutral
  # remove long dark lines (grid/ground) -> treat as background-passable
  line=np.zeros_like(dark)
  for y in range(H):
    d=dark[y]
    if d.sum()>W*.35:
      idx=np.where(np.diff(np.r_[0,d.astype(int),0]))[0].reshape(-1,2)
      for s,e in idx:
        if e-s>W*.35: line[max(0,y-2):y+3,s:e]=True
  for x in range(W):
    d=dark[:,x]
    if d.sum()>H*.35:
      idx=np.where(np.diff(np.r_[0,d.astype(int),0]))[0].reshape(-1,2)
      for s,e in idx:
        if e-s>H*.35: line[s:e,max(0,x-2):x+3]=True
  cand=(cand&~barrier)|line
  seed=np.zeros_like(cand); seed[0,:]=seed[-1,:]=seed[:,0]=seed[:,-1]=True
  lab,n=nd.label(cand)
  ids=np.unique(lab[seed&cand]); ids=ids[ids>0]
  bg=np.isin(lab,ids)
  if TONES:
    # enclosed checker islands: multi-tone neutral regions (fox shading is single-tone)
    tm=[(mx-mn<10)&(np.abs(mx-t)<12) for t in TONES]
    anyt=np.any(tm,axis=0)&~barrier
    tl,tn=nd.label(anyt)
    for i,sl in enumerate(nd.find_objects(tl)):
      m=tl[sl]==i+1; n=m.sum()
      if n<60: continue
      cnt=sorted([(t[sl]&m).sum() for t in tm],reverse=True)
      if len(cnt)>1 and cnt[1]>=.12*n: bg[sl]|=m
  fg=~bg
  fg=nd.binary_opening(fg,iterations=1)
  lab,n=nd.label(fg)
  if n:
    objs=nd.find_objects(lab); s=nd.sum(fg,lab,range(1,n+1))
    ok=[i+1 for i,o in enumerate(objs) if s[i]>=40 and not ((o[0].stop-o[0].start)<8 and (o[1].stop-o[1].start)>30)]
    fg=np.isin(lab,ok)
    # grow back 1px over the outline barrier that touched the fox
    fg=fg|(nd.binary_dilation(fg,iterations=1)&dark)
    # drop small greyish strays not touching the main body
    lab,n=nd.label(fg)
    if n>1:
      s2=nd.sum(fg,lab,range(1,n+1)); main=lab==(1+int(np.argmax(s2)))
      near=nd.binary_dilation(main,iterations=4)
      chroma=(mx-mn)
      for i,sl in enumerate(nd.find_objects(lab)):
        m=lab[sl]==i+1
        if s2[i]<600 and not (near[sl]&m).any() and chroma[sl][m].mean()<22: fg[sl]&=~m
  return fg
def run():
  S=json.load(open('/home/claude/fox/tools/specs.json')); meta={}
  for name,(fid,xs,ys) in S.items():
    a=np.asarray(Image.open(f'{SRC}/{fid}-image.jpg').convert('RGB'))
    global TONES; TONES=tones(a); multi=len(ys)>2
    frames=[]
    for yi in range(len(ys)-1):
      for xi in range(len(xs)-1):
        y0,y1,x0,x1=ys[yi]+3,ys[yi+1]-3,xs[xi]+3,xs[xi+1]-3
        c=a[y0:y1,x0:x1]; m=cell_alpha(c)
        if m.sum()<300: continue
        c=c.copy(); ci=c.astype(int)
        spill=m&(ci[...,1]>np.maximum(ci[...,0],ci[...,2])+20)
        avg=((ci[...,0]+ci[...,2])//2).clip(0,255).astype(np.uint8)
        c[spill,1]=avg[spill]
        rgba=np.dstack([c,(m*255).astype(np.uint8)])
        if multi:
          yy=np.where(m.any(1))[0]; bottom=yy.max()+1
          rgba=rgba[:bottom]
        frames.append(rgba)
    # common bbox over all frames (same cell size assumed -> pad to max)
    ch=max(f.shape[0] for f in frames); cw=max(f.shape[1] for f in frames)
    pad=[np.pad(f,((ch-f.shape[0],0),(0,cw-f.shape[1]),(0,0))) for f in frames]
    A=np.any([p[...,3]>0 for p in pad],axis=0); yy,xx=np.where(A)
    by0,by1,bx0,bx1=yy.min(),yy.max()+1,xx.min(),xx.max()+1
    H=160 if name!='icons' else 64
    sc=H/(by1-by0); w=max(1,round((bx1-bx0)*sc))
    strip=Image.new('RGBA',(w*len(pad),H))
    for i,p in enumerate(pad):
      im=Image.fromarray(p[by0:by1,bx0:bx1]).resize((w,H),Image.LANCZOS)
      ar=np.asarray(im).copy(); ar[...,3]=np.where(ar[...,3]>120,255,0); strip.paste(Image.fromarray(ar),(i*w,0))
    strip.save(f'{OUT}/{name}.png',optimize=True); meta[name]={'w':w,'h':H,'n':len(pad)}
    print(name,meta[name],os.path.getsize(f'{OUT}/{name}.png'))
  json.dump(meta,open(f'{OUT}/meta.json','w'))
run()

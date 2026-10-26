import json, glob, os, sys
D=os.path.dirname(os.path.abspath(__file__))
src=open(os.path.join(D,'..','app/src/main/assets/words.js')).read()
W=[l.split('|')[0] for l in json.loads(src[src.index('=')+1:].strip().rstrip(';')).split('\n') if l.strip()]
have={}; bad=[]
for f in sorted(glob.glob(os.path.join(D,'dict','*.txt'))):
  for n,l in enumerate(open(f),1):
    l=l.rstrip('\n')
    if not l.strip(): continue
    p=l.split('|')
    if len(p)<4 or len(p)%2: bad.append((os.path.basename(f),n,l[:60])); continue
    if p[0] not in W: bad.append((os.path.basename(f),n,'unknown word '+p[0])); continue
    if p[0] in have: bad.append((os.path.basename(f),n,'dup '+p[0]))
    have[p[0]]=p
missing=[w for w in W if w not in have]
print('entries',len(have),'of',len(W),'bad',len(bad))
for b in bad[:20]: print(' ',b)
if len(sys.argv)>1: print('next missing:',missing[:int(sys.argv[1])])

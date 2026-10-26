import json, glob, os
import lem
P=[];seen=set();DROP=[]
for f in [*sorted(glob.glob(os.path.join(os.path.dirname(__file__),'phrases','*.txt')))]:
  for l in open(f):
    if '|' not in l: continue
    en,ru=l.strip().split('|',1)
    if en.lower() in seen: continue
    if not lem.strict_ok(en): DROP.append(en); continue
    seen.add(en.lower()); k=lem.keys(en)
    if not k: continue
    P.append([en,ru,k,max(lem.IDX[x] for x in k)])
P.sort(key=lambda p:p[3])
open(os.path.join(os.path.dirname(__file__),'..','app/src/main/assets/phrases.js'),'w').write('window.PHRASES = '+json.dumps([p[:3] for p in P],ensure_ascii=False)+';\n')
known=set(w.lower() for w in lem.W[:100]); learned={'include','serve','suggest','raise','pass','sell','require','report','decide','pull','return','develop','carry','receive','support','hit','realize'}
ok=[p[0] for p in P if all(k in known or k in learned for k in p[2]) and any(k in learned for k in p[2])]
print(len(P), 'sergey:',len(ok), 'dropped (unknown words):', len(DROP)); print(DROP[:40])

import json,re,sys
src=open(__import__('os').path.join(__import__('os').path.dirname(__file__),'..','app/src/main/assets/words.js')).read()
W=[l.split('|')[0] for l in json.loads(src[src.index('=')+1:].strip().rstrip(';')).split('\n') if l.strip()]
IDX={w.lower():i for i,w in enumerate(W)}
MULTI=sorted([w.lower() for w in W if ' ' in w],key=len,reverse=True)
IRR=dict(x.split(':') for x in "is:be am:be are:be was:be were:be been:be being:be 's:be 're:be 'm:be has:have had:have having:have did:do does:do done:do doing:do went:go gone:go goes:go got:get gotten:get made:make knew:know known:know thought:think took:take taken:take saw:see seen:see came:come found:find gave:give given:give told:tell felt:feel left:leave paid:pay met:meet sold:sell bought:buy built:build brought:bring kept:keep held:hold wrote:write written:write sent:send spent:spend said:say ran:run lost:lose won:win understood:understand began:begin begun:begin stood:stand sat:sit lay:lie led:lead read:read meant:mean put:put let:let set:set cut:cut hit:hit broke:break broken:break chose:choose chosen:choose drove:drive driven:drive ate:eat eaten:eat fell:fall fallen:fall forgot:forget forgotten:forget grew:grow grown:grow heard:hear hid:hide hung:hang slept:sleep spoke:speak spoken:speak taught:teach threw:throw woke:wake wore:wear children:child men:man women:woman people:people feet:foot teeth:tooth mice:mouse better:good best:good worse:bad worst:bad won't:will can't:can don't:do doesn't:do didn't:do isn't:be aren't:be wasn't:be i'm:be it's:be that's:be let's:let i'll:will we're:be you're:be they're:be i.ve:have what.s:be became:become stole:steal bit:bite froze:freeze swore:swear flew:fly drank:drink sang:sing swam:swim rode:ride rose:rise shook:shake died:die".split())
def lem(t):
  t=t.lower()
  if t in IRR: return IRR[t]
  if t in IDX: return t
  for suf,rep in (('ies','y'),('ves','f'),('es',''),('s',''),('ied','y'),('ed',''),('ed','e'),('d',''),('ing',''),('ing','e'),('er',''),('est',''),('ly','')):
    if t.endswith(suf) and len(t)-len(suf)>=2:
      b=t[:-len(suf)]+rep
      if b in IDX: return b
      if len(b)>2 and b[-1]==b[-2] and b[:-1] in IDX: return b[:-1]
  return None
FREE=set("can can't cannot will won't would could should must may might shall one".split())
BASIC=set("""a an the of in to i my me you your yours it its on for at he him his she her we us our they them their
and or no not yes so as by from into out up off this these that those please hi oh tv qr ok per st bon
abakan moscow russia russian khakassia siberian siberia lenin sasha europe soviet british french german african
halloween catholic than igor england""".split())
SUF=("'s","'ve","'ll","'re","'m","'d","s'")
def base(t):
  if t in IRR or t in IDX: return t
  for suf in SUF:
    if t.endswith(suf): return t[:-len(suf)]
  return t
def strict_ok(en):
  """True, если в фразе нет ни одного слова вне словаря (служебные слова и имена — можно)."""
  s=' '+re.sub(r"[^a-z' -]",' ',en.lower())+' '
  for m in MULTI: s=s.replace(' '+m+' ','  ')
  for t in s.split():
    t=t.strip("'-")
    if not t or t in FREE or t in BASIC: continue
    b=base(t)
    if b and b not in BASIC and b not in FREE and not lem(b): return False
  return True
def keys(en):
  s=' '+re.sub(r"[^a-z' -]",' ',en.lower())+' '; ks=set()
  for m in MULTI:
    if ' '+m+' ' in s: ks.add(m); s=s.replace(' '+m+' ','  ')
  for t in s.split():
    t=t.strip("'")
    if not t or t in FREE: continue
    k=lem(t) or lem(base(t))
    if k: ks.add(k)
  return sorted(ks,key=lambda k:IDX[k])
if __name__=='__main__':
  P=[]
  for f in sys.argv[1:]:
    for l in open(f):
      if '|' in l: en,ru=l.strip().split('|',1); P.append((en,ru,keys(en)))
  ens=[p[0].lower() for p in P]; dups={e for e in ens if ens.count(e)>1}
  print('phrases',len(P),'dups',len(dups),list(dups)[:10])
  nok=[p[0] for p in P if not p[2]]; print('no keys',len(nok),nok[:10])
  rank=[max(IDX[k] for k in p[2]) for p in P if p[2]]
  for n in (100,300,500,1000,1500,2000,3000,5000): print(n, sum(r<n for r in rank))

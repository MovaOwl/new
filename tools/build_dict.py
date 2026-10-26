"""Собирает assets/dict.js из tools/dict/*.txt и кладёт примеры в phrases/x_dict.txt.
Пример попадает во фразы, только если КАЖДОЕ его слово есть в словаре (никаких «слов с потолка»)."""
import json, glob, os, re
import lem
D = os.path.dirname(os.path.abspath(__file__))
DICT = {}
for f in sorted(glob.glob(os.path.join(D, 'dict', '*.txt'))):
  for l in open(f):
    p = l.rstrip('\n').split('|')
    if len(p) < 4: continue
    DICT[p[0]] = [p[1]] + [[p[i], p[i + 1]] for i in range(2, len(p) - 1, 2)]
open(os.path.join(D, '..', 'app/src/main/assets/dict.js'), 'w').write(
  'window.DICT = ' + json.dumps(DICT, ensure_ascii=False, separators=(',', ':')) + ';\n')

# служебные слова и имена собственные — их не учат как карточки, они не «с потолка»
BASIC = set('''a an the of in to i my me you your yours it its on for at he him his she her we us our they them their
and or no not yes so as by from into out up off this these that those please hi oh tv qr ok per st bon
abakan moscow russia russian khakassia siberian siberia lenin sasha europe soviet british french german african
halloween catholic'''.split())
def ok_tok(t):
  if t in lem.FREE or t in BASIC: return True
  for suf in ("'s", "'ve", "'ll", "'re", "'m", "'d", "s'"):
    if t.endswith(suf): t = t[:-len(suf)]; break
  return not t or t in BASIC or t in lem.FREE or bool(lem.lem(t))

def clean(en):
  s = ' ' + re.sub(r"[^a-z' -]", ' ', en.lower()) + ' '
  for m in lem.MULTI:
    s = s.replace(' ' + m + ' ', '  ')
  toks = [t.strip("'-") for t in s.split()]
  toks = [t for t in toks if t]
  return all(ok_tok(t) for t in toks)

out, seen, skip = [], set(), 0
for w, v in DICT.items():
  for en, ru in v[1:]:
    k = en.lower().strip()
    if k in seen or len(en.split()) < 2: continue
    if not lem.strict_ok(en) or not lem.keys(en): skip += 1; continue
    seen.add(k); out.append(en + '|' + ru)
open(os.path.join(D, 'phrases', 'x_dict.txt'), 'w').write('\n'.join(out) + '\n')
print('dict', len(DICT), 'examples->phrases', len(out), 'skipped(unknown words)', skip)

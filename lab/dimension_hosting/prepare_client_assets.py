import concurrent.futures, hashlib, json, pathlib, urllib.request
root=pathlib.Path('/workspace/.gradle/caches/fabric-loom/assets')
assets=json.loads((root/'indexes/26.2-32.json').read_text())['objects']
unique={v['hash']:v['size'] for v in assets.values()}
def fetch(pair):
 h,size=pair; p=root/'objects'/h[:2]/h
 if p.exists() and p.stat().st_size==size and hashlib.sha1(p.read_bytes()).hexdigest()==h: return
 for attempt in range(3):
  try:
   with urllib.request.urlopen('https://resources.download.minecraft.net/'+h[:2]+'/'+h,timeout=45) as r: b=r.read()
   if len(b)!=size or hashlib.sha1(b).hexdigest()!=h: raise ValueError('Asset hash mismatch '+h)
   p.parent.mkdir(parents=True,exist_ok=True); p.write_bytes(b); return
  except Exception:
   if attempt==2: raise
with concurrent.futures.ThreadPoolExecutor(max_workers=24) as ex:
 for i,_ in enumerate(ex.map(fetch,unique.items())):
  if i%500==0: print('Verified assets',i,'/',len(unique),flush=True)
print('All Minecraft assets verified',len(unique),flush=True)

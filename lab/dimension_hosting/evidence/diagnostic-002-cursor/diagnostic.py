import json,re,sys,time
from pathlib import Path
sys.path.insert(0,'/workspace/mtmc-prototype/mtmc/lab/dimension_hosting')
from common import manifest,result
from client import LabClient
from server import LabServer
root=Path(__file__).parent
config=json.loads(Path('/workspace/mtmc-prototype/matrix-config-002.json').read_text())
frozen=json.loads(Path('/workspace/mtmc-prototype/runs/run-002/freeze.json').read_text())
assert manifest(config)['digest']==frozen['digest']
case={'id':'DIAGNOSTIC_CURSOR','scenario':'Unchanged post-run diagnostic: smoke UUID-format assertion prevented player/menu capture; exercise actual cursor capture using real client','expected':'Actual player NBT and cursor count7 captured, then 7 items returned to inventory on close','scope':'targeted unchanged diagnostic, not approved transfer/capacity coverage'}
server=None;client=None;observed={};started=time.time()
try:
 server=LabServer(config,root/'server')
 assert server.ready(),'Server readiness failed'
 client=LabClient(config,root/'client')
 assert client.ready(),'Actual client join failed'
 server.cmd('op '+client.name)
 server.cmd('effect give '+client.name+' minecraft:resistance infinite 255 true')
 server.cmd('give '+client.name+' minecraft:diamond 7')
 client.wait('state',lambda r: sum(i['count'] for i in r.get('inventory',[]) if i['id']=='minecraft:diamond')==7)
 client.cmd('open_inventory')
 screen=client.wait('screen',lambda r: r.get('open') and any(s['id']=='minecraft:diamond' for s in r.get('slots',[])))
 slot=next(s for s in screen['slots'] if s['id']=='minecraft:diamond' and s['player_inv'])
 client.cmd('click',slot=slot['slot'])
 client.wait('state',lambda r:not any(i['id']=='minecraft:diamond' for i in r.get('inventory',[])))
 capture=server.cmd('mtmc player-snapshot '+client.name)
 observed['snapshot']=capture
 assert 'Player diagnostic snapshot OK' in capture,capture
 assert re.search(r'cursor:\{count:7,id:"minecraft:diamond"\}',capture),capture
 assert 'menu_slots:' in capture and 'player:' in capture,capture
 client.cmd('close')
 observed['returned_state']=client.wait('state',lambda r:sum(i['count'] for i in r.get('inventory',[]) if i['id']=='minecraft:diamond')==7)
 record=result(case,config,'PASS','Real cursor/player/menu diagnostic verified; no distributed handoff exercised',observed,evidence=[str(root/'client/actions.jsonl'),str(root/'client/client.log'),str(root/'server/commands.jsonl'),str(root/'server/server.log')])
except Exception as e:
 observed['exception']=repr(e)
 record=result(case,config,'FAIL','Unchanged diagnostic mismatch',observed,evidence=[str(root)])
finally:
 if client: client.close()
 if server: server.close()
record['started_unix']=started;record['duration_seconds']=time.time()-started
record['freeze_unchanged']=manifest(config)['digest']==frozen['digest']
(root/'result.json').write_text(json.dumps(record,indent=2)+'\n')
print(record['status'],record['observed'])
raise SystemExit(record['exit_code'])

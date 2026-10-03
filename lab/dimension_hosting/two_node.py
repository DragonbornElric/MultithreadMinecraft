"""Two genuine server JVMs, diagnostic assigned tick dimensions, direct authenticated peer traffic."""
import json
import socket
import subprocess
import time
from pathlib import Path
from server import LabServer
from common import result


def execute_two_node(case, config, directory):
    for port in [25680,25681,25682,25683,25780,25782]:
        with socket.socket() as s:
            s.bind(("127.0.0.1",port))
    pki=directory/'pki'
    p=subprocess.run(['bash',str(Path(__file__).parent/'create_peer_pki.sh'),str(pki)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30,text=True)
    (directory/'pki.log').write_text(p.stdout)
    if p.returncode: raise RuntimeError('Disposable PKI preparation failed')
    def worker(node, folder):
        is_a=node=='a'
        return LabServer(config,directory/folder,ports=(25680,25681) if is_a else (25682,25683),lab_peer={
            'node':node,'other':'b' if is_a else 'a','dimension':'minecraft:overworld' if is_a else 'minecraft:the_nether',
            'otherDimension':'minecraft:the_nether' if is_a else 'minecraft:overworld','port':25780 if is_a else 25782,'otherPort':25782 if is_a else 25780,'pki':pki})
    def state(server): return json.loads(server.cmd('mtmc-lab-peer'))
    def await_state(server,predicate,timeout=30):
        end=time.monotonic()+timeout
        while True:
            value=state(server)
            if predicate(value):return value
            if time.monotonic()>end:raise AssertionError('Peer condition unmet: '+json.dumps(value))
            time.sleep(.5)
    a=b=restarted=None
    observed={};measurements={}
    try:
        a=worker('a','worker-a');b=worker('b','worker-b')
        observed['distinct_processes']=[a.process.pid,b.process.pid]
        assert a.process.pid!=b.process.pid
        assert a.ready() and b.ready(),'Two actual stacks failed simultaneous readiness'
        before_a=await_state(a,lambda s:s.get('peer',{}).get('node')=='b' and s['received']>=3 and s['completed_ticks']>=20)
        before_b=await_state(b,lambda s:s.get('peer',{}).get('node')=='a' and s['received']>=3 and s['completed_ticks']>=20)
        assert before_a['dimension']=='minecraft:overworld' and before_b['dimension']=='minecraft:the_nether'
        assert before_a['peer']['dimension']==before_b['dimension'] and before_b['peer']['dimension']==before_a['dimension']
        assert before_a['peer']['authenticated_requester']=='a' and before_b['peer']['authenticated_requester']=='b'
        assert before_a['blocked_dimension_ticks'].get('minecraft:the_nether',0)>0
        assert before_b['blocked_dimension_ticks'].get('minecraft:overworld',0)>0
        observed['both_ready_authenticated']={'a':before_a,'b':before_b}
        # Live progress in actual selected dimensions, not just successful socket opens.
        after_a=await_state(a,lambda s:s['completed_ticks']>=before_a['completed_ticks']+40 and s.get('peer',{}).get('completed_ticks',0)>before_a['peer']['completed_ticks'])
        after_b=await_state(b,lambda s:s['completed_ticks']>=before_b['completed_ticks']+40)
        observed['progress']={'a':after_a,'b':after_b}
        observed['ticks_queries']={'a':a.cmd('tick query'),'b':b.cmd('tick query')}
        old_boot=before_b['boot']
        b.close(); b=None
        isolated_before=state(a)
        isolated_after=await_state(a,lambda s:s['completed_ticks']>=isolated_before['completed_ticks']+60 and s['failures']>isolated_before['failures'],timeout=20)
        observed['peer_down_tick_progress']={'before':isolated_before,'after':isolated_after}
        restarted=worker('b','worker-b-restarted')
        assert restarted.ready(),'Second real worker failed fresh restart'
        rejoined_b=await_state(restarted,lambda s:s.get('peer',{}).get('node')=='a' and s['received']>=3)
        rejoined_a=await_state(a,lambda s:s.get('peer',{}).get('boot')==rejoined_b['boot'])
        assert rejoined_b['boot']!=old_boot
        observed['reconnected_new_boot']={'a':rejoined_a,'b':rejoined_b}
        measurements['a_tick_delta_during_peer_outage']=isolated_after['completed_ticks']-isolated_before['completed_ticks']
        measurements['peer_rpc_failures_detected_during_outage']=isolated_after['failures']-isolated_before['failures']
        status,reason='PASS','Two actual Fabric JVMs exchange authenticated dimension/tick reports and reconnect without blocking the survivor tick loop'
    except Exception as error:
        observed['exception']=repr(error);status,reason='FAIL','Two-node real server communication/isolation outcome mismatch'
    finally:
        for server in [restarted,b,a]:
            if server is not None: server.close()
    (directory/'observations.json').write_text(json.dumps(observed,indent=2)+'\n')
    evidence=[str(directory/'observations.json')]
    for folder in ['worker-a','worker-b','worker-b-restarted']:
        for name in ['server.log','commands.jsonl','gc.log','launch.json']:
            f=directory/folder/name
            if f.is_file():evidence.append(str(f))
    return result(case,config,status,reason,observed,measurements,evidence)

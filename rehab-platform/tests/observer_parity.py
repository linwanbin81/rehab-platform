"""Check browser immediate-feedback state machine against authoritative Python."""
import copy
import json
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'algorithm'))
from test_observation import frames,item
from clinical_analysis import feature,CycleObserver

fixtures=[]
for code in ('elbow_flexion','shoulder_flexion','shoulder_abduction'):
    for occluded in (False,True):
        f=frames(code)
        if occluded:
            for n in range(22,36): f[n]['landmarks'][14]['visibility']=.1
        fixtures.append(dict(item=item(code),frames=f,evaluate=True))
result=subprocess.run(['node',str(ROOT/'tests/observer_parity.mjs')],input=json.dumps(fixtures),text=True,capture_output=True,check=True)
actual=json.loads(result.stdout)
for fixture,states in zip(fixtures,actual):
    observer=CycleObserver(fixture['item'],True)
    for frame,state in zip(fixture['frames'],states):
        f=feature(frame,640,480,fixture['item']['code'],'RIGHT');observer.update(frame['time'],f)
        assert state['valid']==f['valid']
        assert state['phase']==observer.phase,(frame['time'],state,observer.phase)
        assert state['count']==len(observer.results)
        if f['valid']: assert abs(state['angle']-f['angle'])<1e-7
print('PASS: browser/Python parity across 540 synthetic frames, three actions and occlusion resets.')

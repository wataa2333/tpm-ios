import sys,json,pathlib
sys.path.insert(0,'tools');from local_login import api
p=pathlib.Path('.private/read-only');t=json.loads((p/'process_pageUserTodoTask.json').read_text(encoding='utf-8'))['content'][0]
for path,query in [('fault/getAssetFaultDetailById',{'faultId':t['businessKey']}),('fault/getLatestRepairByFaultId',{'faultId':t['businessKey']})]:
 d=api(path,query=query);(p/(path.replace('/','_')+'_current.json')).write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8');print(path,'KEYS',list(d))
 if 'repairId'in d:
  r=api('erpOutbound/getErpRepairMaterialsByRepairId',query={'repairId':d['repairId']});(p/'erpRepairMaterials_current.json').write_text(json.dumps(r,ensure_ascii=False,indent=2),encoding='utf-8');print('REPAIR_MATERIAL_KEYS',list(r[0]) if r else [])

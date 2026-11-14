"""Synthetic host-only experiment reproducing the stage SQL; not an Android/deadline verdict."""
from pathlib import Path
import sqlite3,tempfile,hashlib,time,json
COUNT=100000
with tempfile.TemporaryDirectory(prefix='whereday-layout-') as directory:
 for without in [True,False]:
  db=sqlite3.connect(str(Path(directory)/('without.db' if without else 'rowid.db')))
  for pragma in ['journal_mode=DELETE','synchronous=OFF','cache_size=-1024','temp_store=FILE']:db.execute('PRAGMA '+pragma).fetchall()
  db.execute('CREATE TABLE records(kind INTEGER NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,baseline TEXT,provided_id INTEGER NOT NULL,PRIMARY KEY(kind,id))'+(' WITHOUT ROWID' if without else ''))
  db.execute('CREATE TABLE google_order(kind INTEGER NOT NULL,id TEXT NOT NULL,seconds INTEGER NOT NULL,nanos INTEGER NOT NULL,sequence INTEGER NOT NULL,PRIMARY KEY(kind,id)) WITHOUT ROWID')
  db.execute('CREATE INDEX google_chronology ON google_order(kind,seconds,nanos,sequence)')
  started=time.perf_counter()
  for first in range(0,COUNT,200):
   for i in range(first,min(first+200,COUNT)):
    identity=hashlib.sha256(str(i).encode()).hexdigest();payload=json.dumps({'id':identity,'coordinate':{'latitude':18,'longitude':-66},'time':[1800000000+i,0],'accuracy':5,'countryCode':'PR','timeZone':'America/Puerto_Rico'},separators=(',',':'))
    assert db.execute('SELECT payload,provided_id FROM records WHERE kind=? AND id=?',(1,identity)).fetchone() is None
    db.execute('INSERT INTO records(kind,id,payload,provided_id) VALUES(?,?,?,?)',(1,identity,payload,1));db.execute('INSERT INTO google_order VALUES(?,?,?,?,?)',(1,identity,1800000000+i,0,i))
   db.commit()
  inserted=time.perf_counter()-started
  query='SELECT o.kind,o.id,r.payload,o.seconds,o.nanos,o.sequence,r.baseline,r.provided_id FROM google_order o JOIN records r ON r.kind=o.kind AND r.id=o.id {} ORDER BY o.kind,o.seconds,o.nanos,o.sequence LIMIT 200'
  output={'withoutRowid':without,'count':COUNT,'sqliteVersion':sqlite3.sqlite_version,'insertSeconds':inserted,'fileBytes':Path(db.execute('PRAGMA database_list').fetchone()[2]).stat().st_size,'queryPlan':list(db.execute('EXPLAIN QUERY PLAN '+query.format('WHERE (o.kind,o.seconds,o.nanos,o.sequence)>(?,?,?,?)'),(1,1800000000,0,0))),'scans':[]}
  for trial in range(3):
   after=None;seen=0;digest=hashlib.sha256();started=time.perf_counter()
   while True:
    rows=db.execute(query.format('' if after is None else 'WHERE (o.kind,o.seconds,o.nanos,o.sequence)>(?,?,?,?)'),() if after is None else after).fetchall()
    if not rows:break
    for kind,identity,payload,seconds,nanos,sequence,baseline,provided in rows:
     assert sequence==seen and seconds==1800000000+seen and nanos==0 and kind==1 and baseline is None and provided==1
     assert identity==hashlib.sha256(str(seen).encode()).hexdigest();digest.update(payload.encode());seen+=1
    last=rows[-1];after=(last[0],last[3],last[4],last[5])
   assert seen==COUNT;output['scans'].append({'seconds':time.perf_counter()-started,'payloadSha256':digest.hexdigest(),'rows':seen})
  print(json.dumps(output),flush=True);db.close()

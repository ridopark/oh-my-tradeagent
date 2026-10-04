import sys; sys.argv=[sys.argv[0],sys.argv[1]]
exec(open(f'{sys.argv[1]}/replay.py').read().split('rows=[]')[0])
rows=[]
exec('def run' + open(f'{sys.argv[1]}/replay.py').read().split('def run')[1].split('for _,r in T')[0])
for _,r in T[T.d.astype(str)>='2024-03-01'].iterrows():
    et=pd.Timestamp(f"{r.d} {r.sig}",tz='America/New_York')+pd.Timedelta(minutes=1)
    run('ANTI-ORB',str(r.d),-r.dir,et,'14:45')
for d in sorted(set(spy.d)):
    for dr,k in ((1,'CALL@10'),(-1,'PUT@10')):
        run(k,d,dr,pd.Timestamp(f"{d} 10:00",tz='America/New_York'),'14:45')
B=pd.DataFrame(rows); B.to_parquet(f'{S}/baseline.parquet'); print(len(B))

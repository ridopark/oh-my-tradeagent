import os,sys; exec(open(os.environ['CACHE']+'/it_a7.py').read().split("if __name__")[0])
F=pd.read_parquet(f'{S}/hold/orb30_feat_scored.parquet'); F.index=F.index.astype(str)
def fn(d):
    if d not in F.index or F.loc[d].score!=2: return []
    try: day=E.Day(d)
    except Exception: return []
    return trade(d,F.loc[d],((15,30),(20,30)),lambda a,dr:(('ATM',a),('ITM10',a-10*dr)),day,SPYB[d])
if __name__=='__main__':
    R=evaluate('A7 FROZEN rule',fn,holdout=True); R.to_parquet(f'{S}/a7_holdout.parquet')
    x=R[(R.variant=='T15/S30 ITM10')&(R.fill=='base')]
    print(x.groupby('how').pnl_r.agg(n='size',win=lambda s:(s>0).mean()*100,mean=lambda s:s.mean()*100).round(1).to_string())
    from scipy.stats import binomtest; w=(x.pnl>0).sum(); print('wins',w,'of',len(x),'  P(win>=55% true rate | observed) one-sided vs 0.55: p=',round(binomtest(w,len(x),0.55,alternative='greater').pvalue,3))

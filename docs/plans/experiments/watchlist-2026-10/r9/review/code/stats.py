import numpy as np, pandas as pd
def cl_se(y,g):
    y=np.asarray(y,float); g=np.asarray(g); n=len(y); e=y-y.mean()
    s=pd.Series(e).groupby(g).sum().values; G=len(s)
    return np.sqrt((s**2).sum()*G/(G-1))/n
def diff_ci(df,col,a,b,tag='tag',day='date'):
    # mean(a)-mean(b) with day-clustered SE via regression on indicator, cluster by day
    x=df[df[tag].isin([a,b])]
    if (x[tag]==a).sum()<1 or (x[tag]==b).sum()<1: return np.nan,np.nan,np.nan,0,0
    X=np.c_[np.ones(len(x)),(x[tag]==a).astype(float)]; y=x[col].values
    beta=np.linalg.lstsq(X,y,rcond=None)[0]; e=y-X@beta
    XtXi=np.linalg.inv(X.T@X); G=x[day].nunique(); meat=np.zeros((2,2))
    for _,idx in x.groupby(day).indices.items():
        u=X[idx].T@e[idx]; meat+=np.outer(u,u)
    V=XtXi@meat@XtXi*G/(G-1); se=np.sqrt(V[1,1])
    return beta[1],beta[1]-1.96*se,beta[1]+1.96*se,(x[tag]==a).sum(),(x[tag]==b).sum()

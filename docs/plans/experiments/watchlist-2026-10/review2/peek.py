import pandas as pd
R=pd.read_parquet('sr_decade.parquet'); print(R.head()); print(R.dtypes); print(R.groupby(['defn','kind','half']).size().unstack(['kind','half']))
A=pd.read_parquet('sr_author.parquet'); print(A.head()); print(A.columns.tolist(), len(A))
print(A.groupby(['kind']).size() if 'kind' in A else '')

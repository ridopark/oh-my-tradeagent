import re,datetime,sys
# Builds calendar/fomc_dates.txt from federalreserve.gov pages (curl fomccalendars.htm + fomchistorical{2016..2020}.htm into the cache dir first).
# The regex misses month-spanning meetings; these 6 were appended by hand: 2017-02-01 2017-11-01 2018-08-01 2023-02-01 2023-11-01 2024-05-01.
S=sys.argv[1]; months='January February March April May June July August September October November December'.split()
M='(?:'+'|'.join(months)+')'; out=set()
def add(y,mon,d):
    m=mon.split('/')[-1]; out.add(datetime.date(y,months.index(m)+1,int(d.split('-')[-1])))
for y in range(2016,2021):
    t=open(f'{S}/fomchistorical{y}.htm',encoding='utf8',errors='ignore').read()
    for mon,d in re.findall(rf'({M}(?:/{M})?)\s+(\d{{1,2}}(?:-\d{{1,2}})?)\s+(?:FOMC\s+)?Meeting',t): add(y,mon,d)
t=open(f'{S}/fomccalendars.htm',encoding='utf8',errors='ignore').read()
parts=re.split(r'(\d{4}) FOMC Meetings',t)
for i in range(1,len(parts),2):
    for mon,d in re.findall(rf'fomc-meeting__month[^>]*><strong>({M}(?:/{M})?)</strong>.*?fomc-meeting__date[^>]*>([\d\-]+)',parts[i+1],re.S):
        if '-' in d: add(int(parts[i]),mon,d)   # scheduled 2-day meetings only (statement on day 2)
ds=sorted(x for x in out if x<=datetime.date(2026,10,2))
open(f'{S}/fomc_dates.txt','w').write('\n'.join(map(str,ds))); print(len(ds)); print(' '.join(map(str,ds)))

#!/bin/bash
# run a script under python3 -I, adding user site-packages (pandas/pyarrow live there)
f=$1; shift
exec python3 -I -c "import sys; sys.path.append('/home/ridopark/.local/lib/python3.12/site-packages'); sys.argv=sys.argv[1:]; exec(compile(open(sys.argv[0]).read(), sys.argv[0], 'exec'))" "$f" "$@"

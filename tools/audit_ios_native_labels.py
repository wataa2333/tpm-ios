"""Compatibility entry point for the read-only native UI audit; never rewrites labels."""
from audit_ios_motion import main

if __name__ == '__main__':
    raise SystemExit(main())

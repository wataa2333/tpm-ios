#!/usr/bin/env python3
"""Monthly keys for the Android app: 6 letters and digits, not case sensitive, one key per month, same for everyone.

  python tools/license_keygen.py init          make the secret once; prints the two lines to paste into the app
  python tools/license_keygen.py issue 2026-11 the key for November 2026
  python tools/license_keygen.py list [N]      the keys of this month and the next N-1 months (default 6)

The key of a month is derived from the secret and the month: HMAC-SHA256(secret, "TPM|yyyy-MM|counter"), the first 30 bits
written as 6 symbols of ABCDEFGHJKLMNPQRSTUVWXYZ23456789 (no 0/O/1/I). If that has no letter or no digit, the counter goes up
by one until it has both. The app holds the same secret and the same algorithm and checks what is typed against the keys
of last month (only in the first 3 days of a month), this month and next month.

The secret is kept in `.private/license_secret.txt` (outside Git; back it up: with it lost, new months cannot be issued
for installed apps). This is a light lock, not strong protection: the secret is inside the app and can be dug out.
"""
import argparse, datetime, hashlib, hmac, secrets, sys
from pathlib import Path

SECRET_FILE = Path(__file__).resolve().parent.parent / ".private" / "license_secret.txt"
KOTLIN = "android/app/src/main/java/com/personal/tpm/license/LicenseSecret.kt"
ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def derive(secret: bytes, month: str, counter: int) -> str:
    digest = hmac.new(secret, f"TPM|{month}|{counter}".encode("utf-8"), hashlib.sha256).digest()
    bits = int.from_bytes(digest[:4], "big")
    return "".join(ALPHABET[(bits >> (27 - 5 * i)) & 31] for i in range(6))


def month_key(secret: bytes, month: str) -> str:
    counter = 0
    while True:
        code = derive(secret, month, counter)
        if any(c.isdigit() for c in code) and any(c.isalpha() for c in code):
            return code
        counter += 1


def load_secret() -> bytes:
    if not SECRET_FILE.exists():
        sys.exit(f"没有找到 {SECRET_FILE}，请先运行：python tools/license_keygen.py init")
    return bytes.fromhex(SECRET_FILE.read_text().strip())


def check_month(text: str) -> str:
    try:
        datetime.datetime.strptime(text, "%Y-%m")
    except ValueError:
        sys.exit("月份格式应为 yyyy-MM，例如 2026-11")
    return text


def add_months(year: int, month: int, n: int):
    index = year * 12 + (month - 1) + n
    return index // 12, index % 12 + 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("init")
    issue = sub.add_parser("issue"); issue.add_argument("month")
    lst = sub.add_parser("list"); lst.add_argument("count", nargs="?", type=int, default=6)
    args = parser.parse_args()

    if args.cmd == "init":
        if SECRET_FILE.exists():
            sys.exit(f"密钥种子已存在：{SECRET_FILE}。重新生成会让已安装的 App 全部失效，如确需重来请先手动删除它。")
        secret = secrets.token_bytes(32).hex()
        SECRET_FILE.parent.mkdir(parents=True, exist_ok=True)
        SECRET_FILE.write_text(secret + "\n")
        print(f"种子已保存到 {SECRET_FILE}（请备份，不要上传）。\n把下面两行替换进 {KOTLIN}：\n")
        print(f'    private const val PART_A = "{secret[:32]}"')
        print(f'    private const val PART_B = "{secret[32:]}"')
    elif args.cmd == "issue":
        print(month_key(load_secret(), check_month(args.month)))
    else:
        secret = load_secret()
        today = datetime.date.today()
        for n in range(args.count):
            year, month = add_months(today.year, today.month, n)
            label = f"{year}-{month:02d}"
            print(f"{label}  {month_key(secret, label)}")


if __name__ == "__main__":
    main()

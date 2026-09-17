#!/usr/bin/env python3
"""Verify that an APK is a non-debuggable build signed by the fixed identity."""

from __future__ import annotations

import argparse
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


PACKAGE_PATTERN = re.compile(
    r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'"
)
# apksigner 的行前缀不是稳定 API，官方文档也没规定输出格式，已经见过三种形态：
#   Build-Tools <= 36：Signer #1 certificate SHA-256 digest: <hex>
#   Build-Tools >= 37：V2 Signer: certificate SHA-256 digest: <hex>
#                      V3.0 Signer: certificate SHA-256 digest: <hex>
#   密钥轮换时：       Signer (minSdkVersion=24, maxSdkVersion=32) #1 certificate SHA-256 digest: <hex>
# 因此**不锚定前缀**，只认 "certificate SHA-256 digest:" 这个短语。
#
# 必须保留 "certificate" 这个词：同一份输出里还有 "public key SHA-256 digest:"，
# 那是公钥摘要，和证书指纹**不是同一个值**。放宽成匹配任意 "SHA-256 digest:" 会让校验
# 变成拿错值去比对，属于把安全检查反转，不是放宽。
CERTIFICATE_SHA256_PATTERN = re.compile(
    r"\bcertificate SHA-256 digest:[ \t]*([0-9A-Fa-f:][0-9A-Fa-f: \t]*)"
)


class ReleaseApkValidationError(ValueError):
    """Raised when an APK cannot satisfy the publication contract."""


@dataclass(frozen=True)
class ApkIdentity:
    package_name: str
    version_name: str
    version_code: str
    debuggable: bool


def normalize_sha256(value: str) -> str:
    """Normalize a SHA-256 fingerprint while rejecting non-fingerprint text."""

    if re.fullmatch(r"[0-9A-Fa-f:\s]+", value or "") is None:
        raise ReleaseApkValidationError("Certificate SHA-256 contains invalid characters")
    normalized = re.sub(r"[:\s]", "", value).lower()
    if re.fullmatch(r"[0-9a-f]{64}", normalized) is None:
        raise ReleaseApkValidationError(
            "Certificate SHA-256 must contain exactly 64 hexadecimal characters"
        )
    return normalized


def parse_aapt_badging(output: str) -> ApkIdentity:
    """Extract package identity and debuggable state from ``aapt dump badging``."""

    package_match = next(
        (PACKAGE_PATTERN.match(line) for line in output.splitlines() if line.startswith("package:")),
        None,
    )
    if package_match is None:
        raise ReleaseApkValidationError("aapt output does not contain a valid package line")
    debuggable = any(
        line.strip() == "application-debuggable" for line in output.splitlines()
    )
    return ApkIdentity(
        package_name=package_match.group(1),
        version_code=package_match.group(2),
        version_name=package_match.group(3),
        debuggable=debuggable,
    )


def parse_single_signer_sha256(output: str) -> str:
    """Return the only signer certificate digest reported by ``apksigner``.

    One certificate signing several schemes (v1/v2/v3) is printed once per scheme, so
    identical digests are collapsed. Two *distinct* certificate digests — key rotation,
    a lineage, or a genuinely multi-signed APK — still fail: the publication contract is
    a single fixed identity.
    """

    digests = {
        normalize_sha256(digest) for digest in CERTIFICATE_SHA256_PATTERN.findall(output)
    }
    if not digests:
        # 解析不出来 != 没有签名者。把 0 当成"签名者数量为 0"会让一次格式变更伪装成
        # "签名身份不对"，反而掩盖真正的原因（Build-Tools 37 就是这么炸的）。
        raise ReleaseApkValidationError(
            "Could not read any certificate SHA-256 digest from apksigner output; "
            "the output format may have changed. Raw apksigner output follows:\n"
            f"{output.strip()}"
        )
    if len(digests) != 1:
        raise ReleaseApkValidationError(
            "Expected exactly one APK signer certificate, found "
            f"{len(digests)}: {', '.join(sorted(digests))}"
        )
    return next(iter(digests))


def validate_release_identity(
    identity: ApkIdentity,
    signer_sha256: str,
    *,
    expected_package: str,
    expected_version_name: str,
    expected_version_code: str,
    expected_cert_sha256: str,
) -> str:
    """Validate all source-controlled and fixed-signing release invariants."""

    if identity.package_name != expected_package:
        raise ReleaseApkValidationError(
            f"APK package mismatch: expected {expected_package}, found {identity.package_name}"
        )
    if identity.version_name != expected_version_name:
        raise ReleaseApkValidationError(
            "APK versionName mismatch: "
            f"expected {expected_version_name}, found {identity.version_name}"
        )
    if identity.version_code != expected_version_code:
        raise ReleaseApkValidationError(
            "APK versionCode mismatch: "
            f"expected {expected_version_code}, found {identity.version_code}"
        )
    if identity.debuggable:
        raise ReleaseApkValidationError("Refusing to publish a debuggable APK")

    normalized_actual = normalize_sha256(signer_sha256)
    normalized_expected = normalize_sha256(expected_cert_sha256)
    if normalized_actual != normalized_expected:
        raise ReleaseApkValidationError(
            "APK signer certificate mismatch: "
            f"expected {normalized_expected}, found {normalized_actual}"
        )
    return normalized_actual


def run_checked(command: Sequence[str]) -> str:
    """Run an inspection command and preserve its diagnostic output on failure."""

    completed = subprocess.run(
        list(command),
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    output = "\n".join(part for part in (completed.stdout, completed.stderr) if part)
    if completed.returncode != 0:
        raise ReleaseApkValidationError(
            f"Inspection command failed with exit code {completed.returncode}: "
            f"{' '.join(command)}\n{output}"
        )
    return output


def write_github_output(
    path: Path,
    identity: ApkIdentity,
    signer_sha256: str,
) -> None:
    values = {
        "apk_package_name": identity.package_name,
        "apk_version_name": identity.version_name,
        "apk_version_code": identity.version_code,
        "apk_debuggable": "false",
        "apk_signer_certificate_sha256": signer_sha256,
    }
    with path.open("a", encoding="utf-8", newline="\n") as output_file:
        for key, value in values.items():
            output_file.write(f"{key}={value}\n")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True)
    parser.add_argument("--expected-package", required=True)
    parser.add_argument("--expected-version-name", required=True)
    parser.add_argument("--expected-version-code", required=True)
    parser.add_argument("--expected-cert-sha256", required=True)
    parser.add_argument("--github-output", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if not args.apk.is_file():
        raise ReleaseApkValidationError(f"APK does not exist: {args.apk}")
    for tool_name, tool_path in (("aapt", args.aapt), ("apksigner", args.apksigner)):
        if not tool_path.is_file():
            raise ReleaseApkValidationError(f"{tool_name} does not exist: {tool_path}")

    aapt_output = run_checked((str(args.aapt), "dump", "badging", str(args.apk)))
    apksigner_output = run_checked(
        (str(args.apksigner), "verify", "--verbose", "--print-certs", str(args.apk))
    )
    identity = parse_aapt_badging(aapt_output)
    signer_sha256 = parse_single_signer_sha256(apksigner_output)
    signer_sha256 = validate_release_identity(
        identity,
        signer_sha256,
        expected_package=args.expected_package,
        expected_version_name=args.expected_version_name,
        expected_version_code=args.expected_version_code,
        expected_cert_sha256=args.expected_cert_sha256,
    )
    if args.github_output is not None:
        write_github_output(args.github_output, identity, signer_sha256)

    print(f"Verified release APK: {args.apk}")
    print(f"Package: {identity.package_name}")
    print(f"versionName: {identity.version_name}")
    print(f"versionCode: {identity.version_code}")
    print(f"Signer certificate SHA-256: {signer_sha256}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

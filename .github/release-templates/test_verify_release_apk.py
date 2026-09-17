import tempfile
import unittest
from pathlib import Path

from verify_release_apk import (
    ApkIdentity,
    ReleaseApkValidationError,
    normalize_sha256,
    parse_aapt_badging,
    parse_single_signer_sha256,
    validate_release_identity,
    write_github_output,
)


CERTIFICATE_SHA256 = "0123456789abcdef" * 4


class VerifyReleaseApkTest(unittest.TestCase):
    def test_normalizes_colon_separated_uppercase_fingerprint(self) -> None:
        colon_separated = ":".join(
            CERTIFICATE_SHA256.upper()[index : index + 2]
            for index in range(0, len(CERTIFICATE_SHA256), 2)
        )
        self.assertEqual(CERTIFICATE_SHA256, normalize_sha256(colon_separated))

    def test_rejects_invalid_fingerprint_text(self) -> None:
        with self.assertRaisesRegex(ReleaseApkValidationError, "invalid characters"):
            normalize_sha256(f"sha256={CERTIFICATE_SHA256}")

    def test_parses_release_badging(self) -> None:
        identity = parse_aapt_badging(
            "package: name='com.example.module' versionCode='12' "
            "versionName='1.2.3-alpha.4' platformBuildVersionName=''\n"
            "sdkVersion:'27'\n"
        )
        self.assertEqual("com.example.module", identity.package_name)
        self.assertEqual("1.2.3-alpha.4", identity.version_name)
        self.assertEqual("12", identity.version_code)
        self.assertFalse(identity.debuggable)

    def test_detects_debuggable_badging(self) -> None:
        identity = parse_aapt_badging(
            "package: name='com.example.module' versionCode='12' versionName='1.2.3'\n"
            "application-debuggable\n"
        )
        self.assertTrue(identity.debuggable)

    def test_parses_exactly_one_signer(self) -> None:
        output = (
            "Signer #1 certificate DN: CN=Release\n"
            f"Signer #1 certificate SHA-256 digest: {CERTIFICATE_SHA256}\n"
        )
        self.assertEqual(CERTIFICATE_SHA256, parse_single_signer_sha256(output))

    def test_parses_scheme_tagged_signer_from_build_tools_37(self) -> None:
        # Build-Tools 37 起 apksigner 把行前缀从 "Signer #N" 换成按签名方案命名。
        # 同一张证书签了 v2 与 v3，会各打印一次，必须折叠成一个签名身份。
        output = (
            "Verifies\n"
            "Verified using v2 scheme (APK Signature Scheme v2): true\n"
            "Verified using v3 scheme (APK Signature Scheme v3): true\n"
            "Number of signers: 1\n"
            "V2 Signer: certificate DN: CN=Release\n"
            f"V2 Signer: certificate SHA-256 digest: {CERTIFICATE_SHA256}\n"
            f"V2 Signer: public key SHA-256 digest: {'a' * 64}\n"
            f"V3.0 Signer: certificate SHA-256 digest: {CERTIFICATE_SHA256}\n"
            f"V3.0 Signer: public key SHA-256 digest: {'a' * 64}\n"
        )
        self.assertEqual(CERTIFICATE_SHA256, parse_single_signer_sha256(output))

    def test_never_mistakes_a_public_key_digest_for_a_certificate_digest(self) -> None:
        # 公钥摘要和证书指纹不是同一个值；认错了会拿错值去比对，等于校验反转。
        output = (
            f"Signer #1 certificate SHA-256 digest: {CERTIFICATE_SHA256}\n"
            f"Signer #1 public key SHA-256 digest: {'b' * 64}\n"
        )
        self.assertEqual(CERTIFICATE_SHA256, parse_single_signer_sha256(output))

    def test_rejects_multiple_signers(self) -> None:
        output = (
            f"Signer #1 certificate SHA-256 digest: {CERTIFICATE_SHA256}\n"
            f"Signer #2 certificate SHA-256 digest: {'f' * 64}\n"
        )
        with self.assertRaisesRegex(
            ReleaseApkValidationError, "exactly one APK signer certificate"
        ):
            parse_single_signer_sha256(output)

    def test_rejects_rotated_signing_lineage(self) -> None:
        # 密钥轮换时 apksigner 按 SDK 区间分段打印；固定签名契约不接受两张不同证书。
        output = (
            "Signer (minSdkVersion=24, maxSdkVersion=32) #1 certificate SHA-256 digest: "
            f"{CERTIFICATE_SHA256}\n"
            "Signer (minSdkVersion=33, maxSdkVersion=2147483647) #1 certificate SHA-256 "
            f"digest: {'f' * 64}\n"
        )
        with self.assertRaisesRegex(
            ReleaseApkValidationError, "exactly one APK signer certificate"
        ):
            parse_single_signer_sha256(output)

    def test_unreadable_output_reports_a_format_change_not_zero_signers(self) -> None:
        # 这条正是 Build-Tools 37 的事故形态：解析不出来被当成"签名者 0 个"，
        # 真正原因（格式变了）被掩盖。报错必须点明格式并带上原始输出。
        output = "Verifies\nTotally unexpected apksigner output\n"
        with self.assertRaisesRegex(ReleaseApkValidationError, "output format may have changed"):
            parse_single_signer_sha256(output)
        with self.assertRaisesRegex(ReleaseApkValidationError, "Totally unexpected"):
            parse_single_signer_sha256(output)

    def test_validates_release_identity(self) -> None:
        identity = ApkIdentity("com.example.module", "1.2.3", "12", False)
        actual = validate_release_identity(
            identity,
            CERTIFICATE_SHA256,
            expected_package="com.example.module",
            expected_version_name="1.2.3",
            expected_version_code="12",
            expected_cert_sha256=CERTIFICATE_SHA256.upper(),
        )
        self.assertEqual(CERTIFICATE_SHA256, actual)

    def test_rejects_debuggable_release(self) -> None:
        identity = ApkIdentity("com.example.module", "1.2.3", "12", True)
        with self.assertRaisesRegex(ReleaseApkValidationError, "debuggable"):
            validate_release_identity(
                identity,
                CERTIFICATE_SHA256,
                expected_package="com.example.module",
                expected_version_name="1.2.3",
                expected_version_code="12",
                expected_cert_sha256=CERTIFICATE_SHA256,
            )

    def test_writes_public_provenance_outputs(self) -> None:
        identity = ApkIdentity("com.example.module", "1.2.3", "12", False)
        with tempfile.TemporaryDirectory() as directory:
            output_path = Path(directory) / "github-output.txt"
            write_github_output(output_path, identity, CERTIFICATE_SHA256)
            content = output_path.read_text(encoding="utf-8")
        self.assertIn("apk_package_name=com.example.module\n", content)
        self.assertIn("apk_debuggable=false\n", content)
        self.assertIn(
            f"apk_signer_certificate_sha256={CERTIFICATE_SHA256}\n",
            content,
        )


if __name__ == "__main__":
    unittest.main()

package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.*
import org.junit.Test

class HostAdmissionLocalIdentityTest {
    @Test fun hostCanVerifyItselfWhenModulePmCannotSeeTheHost() {
        val trust = HostAdmissionLocalIdentity.verify(
            modulePackageName = "com.module",
            hostUid = 10086,
            moduleUid = 10100,
            hostPackageUid = 10086,
            expectedModulePackage = "com.module",
        )
        assertEquals(HostAdmissionLocalIdentity.Trust.Verified(10086), trust)
    }

    @Test fun mismatchOrSameUidIsUnavailableNotDenied() {
        assertEquals(
            HostAdmissionLocalIdentity.Trust.Unavailable,
            HostAdmissionLocalIdentity.verify("other", 1, 2, 1, "com.module"),
        )
        assertEquals(
            HostAdmissionLocalIdentity.Trust.Unavailable,
            HostAdmissionLocalIdentity.verify("com.module", 10086, 10086, 10086, "com.module"),
        )
        assertEquals(
            HostAdmissionLocalIdentity.Trust.Unavailable,
            HostAdmissionLocalIdentity.verify("com.module", 10086, 10100, 10000, "com.module"),
        )
    }

    @Test fun missingUidsAreUnavailableNotDenied() {
        assertEquals(
            HostAdmissionLocalIdentity.Trust.Unavailable,
            HostAdmissionLocalIdentity.verify("com.module", null, 2, 1, "com.module"),
        )
        assertEquals(
            HostAdmissionLocalIdentity.Trust.Unavailable,
            HostAdmissionLocalIdentity.verify("com.module", 1, 2, null, "com.module"),
        )
    }
}

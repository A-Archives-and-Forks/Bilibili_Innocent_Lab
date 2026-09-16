package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAdmissionRoutePlanTest {
    @Test fun binderIsRequiredOnlyFromApi29() {
        assertFalse(HostAdmissionRoutePlan.binderRequired(28))
        assertTrue(HostAdmissionRoutePlan.binderRequired(29))
        assertTrue(HostAdmissionRoutePlan.binderRequired(37))
    }

    @Test fun localFailureStartsBroadcast() {
        assertEquals(
            HostAdmissionRoutePlan.FollowUp.START_BROADCAST,
            HostAdmissionRoutePlan.followUp(
                completedRoute = HostAdmissionRoutePlan.LOCAL,
                broadcastStarted = false,
            ),
        )
        assertEquals(
            HostAdmissionRoutePlan.FollowUp.NONE,
            HostAdmissionRoutePlan.followUp(
                completedRoute = HostAdmissionRoutePlan.LOCAL,
                broadcastStarted = true,
            ),
        )
    }

    @Test fun hangingProviderOrBinderDoesNotBlockOtherFallbacks() {
        assertEquals(
            HostAdmissionRoutePlan.FollowUp.NONE,
            HostAdmissionRoutePlan.followUp(
                completedRoute = HostAdmissionRoutePlan.PROVIDER,
                broadcastStarted = false,
            ),
        )
        assertEquals(
            HostAdmissionRoutePlan.FollowUp.NONE,
            HostAdmissionRoutePlan.followUp(
                completedRoute = HostAdmissionRoutePlan.BINDER,
                broadcastStarted = false,
            ),
        )
    }
}

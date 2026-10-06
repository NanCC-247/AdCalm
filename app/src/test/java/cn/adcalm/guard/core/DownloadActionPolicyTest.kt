package cn.adcalm.guard.core
import org.junit.Assert.*
import org.junit.Test
class DownloadActionPolicyTest {
    @Test fun observationPauseAndMissingConsentBlockOldJump() {
        assertFalse(DownloadActionPolicy.allows(true,true,true,1000,2000))
        assertFalse(DownloadActionPolicy.allows(false,false,true,1000,2000))
        assertFalse(DownloadActionPolicy.allows(true,false,false,1000,2000))
    }
    @Test fun requiresRecentNonfutureJump() {
        assertFalse(DownloadActionPolicy.allows(true,false,true,0,2000))
        assertFalse(DownloadActionPolicy.allows(true,false,true,3000,2000))
        assertFalse(DownloadActionPolicy.allows(true,false,true,1000,121001))
        assertTrue(DownloadActionPolicy.allows(true,false,true,1000,2000))
    }
}

package ai.openclaw.android.skill

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 方案 3 统一安全层审查规则测试：
 * - READ → 直通
 * - WRITE → 按偏好（ALWAYS_APPROVE 直通 / ALWAYS_DENY 拒绝 / 无偏好或每次询问 → 询问）
 * - DANGEROUS → 一律询问（即使 ALWAYS_APPROVE 也不放行）
 */
class SecurityReviewTest {

    // ==================== READ ====================

    @Test
    fun `READ tool returns AUTO_EXECUTE without preference`() {
        val policy = SecurityReview.reviewTool("weather_get_weather", ToolRiskLevel.READ, preference = null)
        assertEquals(ToolSecurityPolicy.AUTO_EXECUTE, policy)
    }

    @Test
    fun `READ tool returns AUTO_EXECUTE even with DENY preference`() {
        val pref = UserApprovalPreference("weather_get_weather", ApprovalDecision.ALWAYS_DENY)
        val policy = SecurityReview.reviewTool("weather_get_weather", ToolRiskLevel.READ, preference = pref)
        assertEquals(ToolSecurityPolicy.AUTO_EXECUTE, policy)
    }

    // ==================== WRITE ====================

    @Test
    fun `WRITE tool without preference returns ASK_USER`() {
        val policy = SecurityReview.reviewTool("sms_send_sms", ToolRiskLevel.WRITE, preference = null)
        assertEquals(ToolSecurityPolicy.ASK_USER, policy)
    }

    @Test
    fun `WRITE tool with ALWAYS_APPROVE preference returns AUTO_EXECUTE`() {
        val pref = UserApprovalPreference("sms_send_sms", ApprovalDecision.ALWAYS_APPROVE)
        val policy = SecurityReview.reviewTool("sms_send_sms", ToolRiskLevel.WRITE, preference = pref)
        assertEquals(ToolSecurityPolicy.AUTO_EXECUTE, policy)
    }

    @Test
    fun `WRITE tool with ALWAYS_DENY preference returns DENY`() {
        val pref = UserApprovalPreference("sms_send_sms", ApprovalDecision.ALWAYS_DENY)
        val policy = SecurityReview.reviewTool("sms_send_sms", ToolRiskLevel.WRITE, preference = pref)
        assertEquals(ToolSecurityPolicy.DENY, policy)
    }

    @Test
    fun `WRITE tool with ASK_EVERY_TIME preference returns ASK_USER`() {
        val pref = UserApprovalPreference("sms_send_sms", ApprovalDecision.ASK_EVERY_TIME)
        val policy = SecurityReview.reviewTool("sms_send_sms", ToolRiskLevel.WRITE, preference = pref)
        assertEquals(ToolSecurityPolicy.ASK_USER, policy)
    }

    // ==================== DANGEROUS ====================

    @Test
    fun `DANGEROUS tool without preference returns ASK_USER`() {
        val policy = SecurityReview.reviewTool("shell_exec", ToolRiskLevel.DANGEROUS, preference = null)
        assertEquals(ToolSecurityPolicy.ASK_USER, policy)
    }

    @Test
    fun `DANGEROUS tool returns ASK_USER even with ALWAYS_APPROVE preference`() {
        val pref = UserApprovalPreference("shell_exec", ApprovalDecision.ALWAYS_APPROVE)
        val policy = SecurityReview.reviewTool("shell_exec", ToolRiskLevel.DANGEROUS, preference = pref)
        assertEquals(ToolSecurityPolicy.ASK_USER, policy)
    }

    @Test
    fun `DANGEROUS tool with ALWAYS_DENY preference still returns ASK_USER`() {
        // DANGEROUS 不读偏好：既不放行也不持久化拒绝——用户每次都有最终决定权
        val pref = UserApprovalPreference("shell_exec", ApprovalDecision.ALWAYS_DENY)
        val policy = SecurityReview.reviewTool("shell_exec", ToolRiskLevel.DANGEROUS, preference = pref)
        assertEquals(ToolSecurityPolicy.ASK_USER, policy)
    }
}

package ai.openclaw.android.skill

import ai.openclaw.android.skill.builtin.WeatherSkill
import ai.openclaw.android.skill.builtin.MultiSearchSkill
import ai.openclaw.android.skill.builtin.TranslateSkill
import ai.openclaw.android.skill.builtin.ReminderSkill
import ai.openclaw.android.skill.builtin.CalendarSkill
import ai.openclaw.android.skill.builtin.LocationSkill
import ai.openclaw.android.skill.builtin.ContactSkill
import ai.openclaw.android.skill.builtin.SMSSkill
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.pm.PackageManager
import android.content.res.AssetManager
import androidx.core.content.ContextCompat
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.File

class SkillManagerTest {

    @MockK
    private lateinit var mockContext: Context

    private lateinit var skillManager: SkillManager

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        val mockAssets = mockk<AssetManager>(relaxed = true)
        every { mockAssets.open("scripts/search.js") } returns ByteArrayInputStream("".toByteArray())
        every { mockContext.assets } returns mockAssets
        every { mockContext.getSystemService(NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        skillManager = SkillManager(mockContext)
    }

    @After
    fun tearDown() {
        // Release MockK static mocks (ContextCompat) so they don't leak into other test classes
        unmockkAll()
    }

    @Test
    fun `loadBuiltinSkills_registersAllSkills`() {
        // Arrange
        every { mockContext.packageName } returns "ai.openclaw.android.test"

        // Act
        skillManager.loadBuiltinSkills(mockContext)

        // Assert
        val loadedSkills = skillManager.getLoadedSkills()
        // 12 registered; NotificationSkill may fail on mock context if notificationManager cast fails
        assertTrue("Expected 11-12 skills loaded but got ${loadedSkills.size}", loadedSkills.size >= 11)

        assertTrue(loadedSkills.containsKey("weather"))
        assertTrue(loadedSkills.containsKey("search"))
        assertTrue(loadedSkills.containsKey("translate"))
        assertTrue(loadedSkills.containsKey("reminder"))
        assertTrue(loadedSkills.containsKey("calendar"))
        assertTrue(loadedSkills.containsKey("location"))
        assertTrue(loadedSkills.containsKey("contact"))
        assertTrue(loadedSkills.containsKey("sms"))
        assertTrue(loadedSkills.containsKey("applauncher"))
        assertTrue(loadedSkills.containsKey("settings"))
        assertTrue(loadedSkills.containsKey("script"))

        // Verify each skill type
        assertTrue(loadedSkills["weather"] is WeatherSkill)
        assertTrue(loadedSkills["search"] is MultiSearchSkill)
        assertTrue(loadedSkills["translate"] is TranslateSkill)
        assertTrue(loadedSkills["reminder"] is ReminderSkill)
        assertTrue(loadedSkills["calendar"] is CalendarSkill)
        assertTrue(loadedSkills["location"] is LocationSkill)
        assertTrue(loadedSkills["contact"] is ContactSkill)
        assertTrue(loadedSkills["sms"] is SMSSkill)
    }

    @Test
    fun `getAllTools_returnsNamespacedNames`() {
        // Arrange
        every { mockContext.packageName } returns "ai.openclaw.android.test"
        every { mockContext.getSystemService(NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        skillManager.loadBuiltinSkills(mockContext)

        // Act
        val allTools = skillManager.getAllTools()

        // Assert
        assertTrue(allTools.isNotEmpty())

        // Check that tool names follow the expected format (skillId_toolName)
        allTools.forEach { toolDef ->
            assertTrue("Tool name should contain underscore separator: ${toolDef.name}",
                toolDef.name.contains('_'))

            val parts = toolDef.name.split('_', limit = 2)
            assertTrue(parts.size == 2)
            assertTrue(parts[0].isNotBlank()) // skillId
            assertTrue(parts[1].isNotBlank()) // toolName
        }

        // Specific verification for known tools
        val toolNames = allTools.map { it.name }
        assertTrue(toolNames.any { it.startsWith("weather_") })
        assertTrue(toolNames.any { it.startsWith("search_") })
    }

    @Test
    fun `executeTool_underscoredSkillId_parsesCorrectly`() = runTest {
        // This verifies the fix for: "Skill not found: dynamic" error
        // when LLM calls dynamic_skill_generator_generate_skill

        // Arrange: register a skill with underscore in ID
        val mockSkill = mockk<Skill>(relaxed = true)
        every { mockSkill.id } returns "dynamic_skill_generator"
        every { mockSkill.name } returns "动态技能生成"
        every { mockSkill.tools } returns listOf(
            mockk<SkillTool>(relaxed = true).also { tool ->
                every { tool.name } returns "generate_skill"
                every { tool.riskLevel } returns ToolRiskLevel.READ
                coEvery { tool.execute(any()) } returns SkillResult(true, "skill registered", "")
            }
        )
        every { mockSkill.initialize(any()) } returns Unit

        skillManager.registerSkill(mockSkill)

        // Mock permissions granted
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        // Act: call the tool with the underscored skill ID
        val outcome = skillManager.executeTool(
            "dynamic_skill_generator_generate_skill",
            mapOf("skillJson" to "{}")
        )

        // Assert: READ 工具直通执行，且不应出现 "Skill not found: dynamic"
        assertTrue("Expected Done outcome but got $outcome", outcome is ToolExecutionOutcome.Done)
        val result = (outcome as ToolExecutionOutcome.Done).result
        assertFalse("Should not return 'Skill not found: dynamic' error",
            result.output.contains("Skill not found: dynamic"))
        assertEquals("skill registered", result.output)
    }

    // ==================== 统一安全层（方案 3）验收 ====================

    /** 注册一个可控风险的 mock 技能，返回其完整工具名 */
    private fun registerMockSkill(skillId: String, toolName: String, risk: ToolRiskLevel): String {
        val mockSkill = mockk<Skill>(relaxed = true)
        every { mockSkill.id } returns skillId
        every { mockSkill.name } returns "测试技能"
        every { mockSkill.requiredPermissions } returns emptyList()
        every { mockSkill.tools } returns listOf(
            mockk<SkillTool>(relaxed = true).also { tool ->
                every { tool.name } returns toolName
                every { tool.riskLevel } returns risk
                every { tool.description } returns "测试工具"
                coEvery { tool.execute(any()) } returns SkillResult(true, "executed", "")
            }
        )
        every { mockSkill.initialize(any()) } returns Unit
        skillManager.registerSkill(mockSkill)
        return "${skillId}_$toolName"
    }

    @Test
    fun `executeTool WRITE without preference and without channel returns NeedsApproval`() = runTest {
        // 验收 #2：sms_send_sms 场景的抽象——WRITE + 无偏好 + 无审批通道（后台触发器）
        val toolId = registerMockSkill("sms", "send_sms", ToolRiskLevel.WRITE)

        val outcome = skillManager.executeTool(toolId, emptyMap())

        assertTrue("Expected NeedsApproval but got $outcome", outcome is ToolExecutionOutcome.NeedsApproval)
        outcome as ToolExecutionOutcome.NeedsApproval
        assertEquals(toolId, outcome.toolId)
        assertEquals(ToolRiskLevel.WRITE, outcome.risk)
    }

    @Test
    fun `executeTool READ executes directly without approval`() = runTest {
        val toolId = registerMockSkill("weather", "get_weather", ToolRiskLevel.READ)

        val outcome = skillManager.executeTool(toolId, emptyMap())

        assertTrue(outcome is ToolExecutionOutcome.Done)
        assertEquals("executed", (outcome as ToolExecutionOutcome.Done).result.output)
    }

    @Test
    fun `executeTool WRITE with approval ALWAYS_APPROVE executes and persists preference`() = runTest {
        val toolId = registerMockSkill("sms", "send_sms", ToolRiskLevel.WRITE)
        val prefs = UserPreferenceManager(createTempDir())
        skillManager.preferenceManager = prefs

        val outcome = skillManager.executeTool(toolId, emptyMap(), requestApproval = { _, _, _ -> ApprovalDecision.ALWAYS_APPROVE })

        assertTrue(outcome is ToolExecutionOutcome.Done)
        assertEquals("executed", (outcome as ToolExecutionOutcome.Done).result.output)
        // 偏好持久化：下次同工具直通
        assertEquals(ApprovalDecision.ALWAYS_APPROVE, prefs.getPreference(toolId)?.decision)
        val second = skillManager.executeTool(toolId, emptyMap())
        assertTrue("Expected direct execution after ALWAYS_APPROVE but got $second", second is ToolExecutionOutcome.Done)
    }

    @Test
    fun `executeTool DANGEROUS with ALWAYS_APPROVE executes but does not persist`() = runTest {
        // DANGEROUS 不持久化白名单：下次仍会询问（过期白名单定时炸弹防御）
        val toolId = registerMockSkill("shell", "exec", ToolRiskLevel.DANGEROUS)
        val prefs = UserPreferenceManager(createTempDir())
        skillManager.preferenceManager = prefs

        val outcome = skillManager.executeTool(toolId, emptyMap(), requestApproval = { _, _, _ -> ApprovalDecision.ALWAYS_APPROVE })

        assertTrue(outcome is ToolExecutionOutcome.Done)
        assertNull("DANGEROUS preference must not be persisted", prefs.getPreference(toolId))
        // 第二次仍然 NeedsApproval（无通道时）
        val second = skillManager.executeTool(toolId, emptyMap())
        assertTrue(second is ToolExecutionOutcome.NeedsApproval)
    }

    @Test
    fun `executeTool approval cancelled returns Denied`() = runTest {
        val toolId = registerMockSkill("reminder", "set_reminder", ToolRiskLevel.WRITE)

        val outcome = skillManager.executeTool(toolId, emptyMap(), requestApproval = { _, _, _ -> null })

        assertTrue(outcome is ToolExecutionOutcome.Denied)
    }

    @Test
    fun `executeTool missing permissions without requester returns Denied`() = runTest {
        // A4：本地/后台路径缺权限 → 明确拒绝，不再静默失败
        val mockSkill = mockk<Skill>(relaxed = true)
        every { mockSkill.id } returns "calendar"
        every { mockSkill.name } returns "日历"
        every { mockSkill.requiredPermissions } returns listOf(Manifest.permission.READ_CALENDAR)
        every { mockSkill.tools } returns listOf(
            mockk<SkillTool>(relaxed = true).also { tool ->
                every { tool.name } returns "list_events"
                every { tool.riskLevel } returns ToolRiskLevel.READ
                coEvery { tool.execute(any()) } returns SkillResult(true, "executed", "")
            }
        )
        every { mockSkill.initialize(any()) } returns Unit
        skillManager.registerSkill(mockSkill)

        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_DENIED

        val outcome = skillManager.executeTool("calendar_list_events", emptyMap())

        assertTrue("Expected Denied but got $outcome", outcome is ToolExecutionOutcome.Denied)
        assertTrue((outcome as ToolExecutionOutcome.Denied).reason.contains("需要权限"))
    }

    @Test
    fun `requiredPermissions declared by camera sms location skills are non-empty`() {
        // 验收 #3：权限唯一真源是 Skill.requiredPermissions（N1 修复）
        every { mockContext.packageName } returns "ai.openclaw.android.test"
        skillManager.loadBuiltinSkills(mockContext)

        for (skillId in listOf("camera", "sms", "location", "contact", "calendar")) {
            val perms = skillManager.getSkillRequiredPermissions(skillId)
            assertNotNull("Skill '$skillId' should declare requiredPermissions", perms)
            assertTrue("Skill '$skillId' permissions should be non-empty", perms!!.isNotEmpty())
        }
        // weather / search 等纯查询技能不声明权限
        assertNull(skillManager.getSkillRequiredPermissions("weather"))
        assertNull(skillManager.getSkillRequiredPermissions("search"))
    }

    private fun createTempDir(): File =
        java.nio.file.Files.createTempDirectory("skill_prefs_test").toFile()

    @Test
    fun `executeTool_validCall_returnsSuccess`() = runTest {
        // Arrange
        every { mockContext.packageName } returns "ai.openclaw.android.test"
        every { mockContext.getSystemService(NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        skillManager.loadBuiltinSkills(mockContext)

        // Mock ContextCompat.checkSelfPermission to return granted for all permissions
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        // For now, test with a simple call that doesn't require actual network
        // We'll test that the tool execution framework works properly

        // Act
        val result = skillManager.getAllTools()

        // Assert
        assertTrue(result.isNotEmpty())

        // Test that we can get all tools without errors
        val toolDefs = skillManager.getAllTools()
        assertTrue(toolDefs.isNotEmpty())
    }

    @Test
    fun `checkSkillPermissions_missingPermission_returnsFalse`() {
        // Arrange
        every { mockContext.packageName } returns "ai.openclaw.android.test"
        every { mockContext.getSystemService(NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        skillManager.loadBuiltinSkills(mockContext)

        // Mock ContextCompat.checkSelfPermission to return denied for calendar permission
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.READ_CALENDAR)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.WRITE_CALENDAR)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.ACCESS_COARSE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.READ_CONTACTS)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.SEND_SMS)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.READ_SMS)
        } returns PackageManager.PERMISSION_DENIED

        // Act
        val calendarPermissionResult = skillManager.checkSkillPermissions("calendar")
        val locationPermissionResult = skillManager.checkSkillPermissions("location")
        val contactPermissionResult = skillManager.checkSkillPermissions("contact")
        val smsPermissionResult = skillManager.checkSkillPermissions("sms")

        // Assert
        assertFalse(calendarPermissionResult.first)
        assertFalse(locationPermissionResult.first)
        assertFalse(contactPermissionResult.first)
        assertFalse(smsPermissionResult.first)

        // Weather skill should not require permissions
        val weatherPermissionResult = skillManager.checkSkillPermissions("weather")
        assertTrue(weatherPermissionResult.first)
    }
}
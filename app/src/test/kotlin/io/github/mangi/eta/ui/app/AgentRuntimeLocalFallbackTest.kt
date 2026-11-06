package io.github.mangi.eta.ui.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeLocalFallbackTest {
    @Test
    fun `fallback is limited to runtime handoff failures`() {
        assertTrue(AgentRuntimeLocalFallback.shouldUse("Agent Runtime 服务绑定失败"))
        assertTrue(AgentRuntimeLocalFallback.shouldUse("无法启动后台执行服务，请返回 Eta 后重试"))
        assertTrue(AgentRuntimeLocalFallback.shouldUse("Agent Runtime 请求发送失败（SecurityException）"))
        assertFalse(AgentRuntimeLocalFallback.shouldUse("HTTP 401 Unauthorized"))
        assertFalse(AgentRuntimeLocalFallback.shouldUse("请先在 Eta 中配置可用的模型"))
        assertFalse(AgentRuntimeLocalFallback.shouldUse(null))
    }
}

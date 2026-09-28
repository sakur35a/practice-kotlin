package com.example.diary

import org.jooq.DSLContext
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health
import org.springframework.stereotype.Component

// 앱이 실제로 붙어 있는 DB가 primary인지 보여준다. standby에 붙어 있으면 쓰기가 실패하므로 DOWN으로 둔다.
@Component("dbRole")
class DbRoleHealthIndicator(
    private val dsl: DSLContext,
) : AbstractHealthIndicator() {
    override fun doHealthCheck(builder: Health.Builder) {
        val record =
            requireNotNull(
                dsl.fetchOne("select pg_is_in_recovery() as standby, host(inet_server_addr()) as server"),
            )
        val standby = record.get("standby", Boolean::class.java)

        if (standby) builder.down() else builder.up()
        builder
            .withDetail("role", if (standby) "standby" else "primary")
            .withDetail("server", record.get("server", String::class.java) ?: "local")
    }
}

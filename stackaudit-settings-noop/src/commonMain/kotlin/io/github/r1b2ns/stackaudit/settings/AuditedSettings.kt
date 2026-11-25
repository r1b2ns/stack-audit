package io.github.r1b2ns.stackaudit.settings

import com.russhwolf.settings.Settings
import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.StorageRecorder

public fun Settings.audited(
    name: String,
    recorder: StorageRecorder = StackAudit,
): Settings = this.also { name.hashCode() + recorder.hashCode() }

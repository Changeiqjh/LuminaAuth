package com.luminaauth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TextField
import com.luminaauth.plugin.FieldType
import com.luminaauth.plugin.Plugin

/**
 * Builds the login form from a plugin's declared field schema.
 */
object SchemaBuilder {
    /** Label of the first missing required field, or null when valid. */
    fun firstMissing(plugin: Plugin, values: Map<String, String>): String? {
        return plugin.fields.firstOrNull { def ->
            def.required && values[def.id].isNullOrBlank()
        }?.label
    }
}

@Composable
fun PluginFields(
    plugin: Plugin,
    values: Map<String, String>,
    onChange: (id: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        plugin.fields.forEachIndexed { index, field ->
            TextField(
                value = values[field.id] ?: "",
                onValueChange = { onChange(field.id, it) },
                modifier = Modifier.fillMaxWidth(),
                label = field.label,
                visualTransformation = if (field.type == FieldType.PASSWORD) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
            )
            if (index < plugin.fields.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}
